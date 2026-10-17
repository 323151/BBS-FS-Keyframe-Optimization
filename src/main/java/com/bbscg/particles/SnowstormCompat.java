package com.bbscg.particles;

import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleParser;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The shared hub of the "full Snowstorm particle support" mode (the {@code snowstorm_particles}
 * setting, registered on the addon's "k帧优化" settings page).
 *
 * <p>While the setting is on, this class and its mixins extend BBS's built-in Bedrock particle
 * engine with everything Snowstorm can export that stock 2.7 ignores or degrades:</p>
 *
 * <ul>
 *     <li>{@code bezier} and {@code bezier_chain} curve types (stock silently falls back to
 *     {@code linear} because the curve enum only knows {@code linear}/{@code catmull_rom});</li>
 *     <li>{@code lookat_direction} and the three {@code emitter_transform_*} billboard facing
 *     modes (stock falls back to {@code rotate_xyz}), plus Snowstorm's {@code "custom"} spelling
 *     of the billboard direction mode (stock only knows {@code "custom_direction"});</li>
 *     <li>gradient interpolants spanning beyond 0..1 (Snowstorm encodes gradient stops in the
 *     emitter's Range space; stock clamps the interpolant to 0..1 so long gradients never
 *     finish);</li>
 *     <li>the event system — the top-level {@code particle_effect.events} table plus the
 *     {@code emitter_lifetime_events} and {@code particle_lifetime_events} components
 *     (creation/expiration/timeline/travel-distance events), and {@code particle_motion_collision.events};</li>
 *     <li>{@code emitter_rate_manual}.</li>
 * </ul>
 *
 * <p>Every code path here is a no-op while the setting is off, so stock behaviour is
 * completely preserved. All of it is implemented via mixins against BBS classes — BBS source
 * itself is never modified.</p>
 */
public class SnowstormCompat
{
    /**
     * Parsed top-level {@code particle_effect.events} tables, keyed weakly by scheme so parsed
     * schemes that get replaced can be garbage collected.
     */
    public static final Map<ParticleScheme, Map<String, ParticleEvent>> SCHEME_EVENTS = new WeakHashMap<>();

    /* Diagnostics — deliberately low frequency (one line per toggle flip / per scheme), so
     * these can stay in shipped builds and answer "did the support actually run?" from a log. */
    private static boolean diagLastEnabled;
    private static final java.util.Set<ParticleScheme> DIAG_UPDATE_LOGGED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<ParticleScheme, Boolean>());

    public static boolean isEnabled()
    {
        boolean enabled = CombinedGizmoMode.isSnowstormParticles();

        if (enabled != diagLastEnabled)
        {
            diagLastEnabled = enabled;
            System.out.println("[BBS-CG] Snowstorm support is now " + (enabled ? "ON" : "OFF"));
        }

        return enabled;
    }

    /**
     * Run the initialization component's {@code particle_update_expression} for the particle
     * whose variables are currently loaded into the parser. Mirrors Blockbuster, which runs
     * this block from {@code setParticleVariables} — i.e. right before the particle is
     * created (before the shape component consumes the variables), on every tick (before
     * the per-particle update components), and on every render frame (before the render
     * components evaluate sizes/colors). Serial per-particle execution makes the shared
     * parser variables act as if they were particle-local, exactly like in Blockbuster.
     */
    public static void runParticleUpdate(ParticleScheme scheme)
    {
        if (!isEnabled() || scheme == null)
        {
            return;
        }

        if (DIAG_UPDATE_LOGGED.add(scheme))
        {
            System.out.println("[BBS-CG] particle_update_expression is being run for '" + scheme.identifier + "'");
        }

        for (ParticleComponentBase component : scheme.components)
        {
            if (component instanceof IParticleInitializationAccess)
            {
                MolangExpression expression = ((IParticleInitializationAccess) component).bbscgGetParticleUpdate();

                if (expression != null)
                {
                    expression.get();
                }
            }
        }
    }

    /**
     * Replacement for {@code Particle.update} while the setting is on. Stock 2.7 has four
     * physics defects compared to Blockbuster 1.12 and Snowstorm's own preview (wintersky):
     *
     * <ul>
     *     <li>the angle is recomputed absolutely as {@code initialRotation + velocity * age},
     *     so any change of the rotation velocity (drag, acceleration) makes the angle jump
     *     instead of integrating smoothly;</li>
     *     <li>linear drag is merged into acceleration with no clamp, so a drag coefficient
     *     above 20 (including collision drag) reverses and explodes the velocity instead of
     *     stopping the particle;</li>
     *     <li>{@code acceleration} and {@code rotationAcceleration} are never cleared even
     *     though {@code particle_motion_dynamic} adds onto them every tick — acceleration
     *     converges to ~105% of the configured value and rotation acceleration grows without
     *     bound;</li>
     *     <li>the collision component accumulates {@code dragFactor += collisionDrag} every
     *     tick and nothing ever resets it, so a particle resting on a surface gains unlimited
     *     drag.</li>
     * </ul>
     *
     * This follows Blockbuster's integration order (incremental rotation with clamped drag,
     * separately clamped linear drag, then acceleration) while keeping BBS's own
     * {@code relativeVelocity}/{@code offset}/parametric-motion semantics. Clearing the fields
     * at the end turns the collision component's {@code +=} into Blockbuster's assignment
     * semantics, because the component re-sets it every tick after this update runs.
     */
    public static void updateParticle(Particle particle, ParticleEmitter emitter)
    {
        particle.prevRotation = particle.rotation;
        particle.prevPosition.set(particle.position);

        particle.setupMatrix(emitter);

        if (!particle.manualRotation)
        {
            /* Incremental rotation with clamped drag: drag may brake the spin to a stop
             * but never reverse it. The collision component's collision_rotation_drag is
             * stacked on top while the particle is touching a surface. */
            float rotationDrag = (particle.rotationDrag + ((IParticleCollisionAccess) particle).bbscgGetRotationCollisionDrag()) * particle.rotationVelocity;

            particle.rotationVelocity = MathUtils.clamp(
                particle.rotationVelocity - rotationDrag / 20F,
                Math.min(particle.rotationVelocity, 0),
                Math.max(particle.rotationVelocity, 0));
            particle.rotationVelocity += particle.rotationAcceleration / 20F;
            particle.rotation += particle.rotationVelocity;
        }

        if (!particle.manualPosition)
        {
            if (particle.relativeVelocity && particle.age == 0)
            {
                particle.matrix.transform(particle.speed);
            }

            /* Local space extras (Blockbuster's emitter_local_space direction/acceleration/
             * gravity): the initial speed and/or the acceleration vector are expressed in
             * the emitter's rotated frame, and gravity pulls within that frame. These use
             * the emitter quaternion directly — the matrix is only loaded for
             * relativePosition/relativeRotation particles. */
            IParticleLocalSpaceAccess localSpace = (IParticleLocalSpaceAccess) particle;

            if (localSpace.bbscgIsRelativeDirection() && particle.age == 0)
            {
                emitter.rotation.transform(particle.speed);
            }

            if (localSpace.bbscgIsRelativeAcceleration())
            {
                emitter.rotation.transform(particle.acceleration);
            }

            if (localSpace.bbscgHasGravity())
            {
                particle.acceleration.y -= 9.81F;
            }

            /* Linear drag, applied separately from acceleration so it can be clamped:
             * high drag values decelerate the particle to a stop instead of reversing it. */
            Vector3f drag = new Vector3f(particle.speed);
            drag.mul(-(particle.drag + particle.dragFactor));
            drag.mul(1F / 20F);

            if (particle.speed.length() - drag.length() <= 0)
            {
                particle.speed.set(0F, 0F, 0F);
            }
            else
            {
                particle.speed.add(drag);
            }

            particle.speed.add(particle.acceleration.x / 20F, particle.acceleration.y / 20F, particle.acceleration.z / 20F);

            Vector3f vec = new Vector3f(particle.speed);

            vec.x *= particle.accelerationFactor.x;
            vec.y *= particle.accelerationFactor.y;
            vec.z *= particle.accelerationFactor.z;

            if (!particle.relativeVelocity)
            {
                particle.matrix.transform(vec);
            }

            if (particle.age == 0)
            {
                vec.mul(1F + particle.offset);
            }

            particle.position.x += vec.x / 20F;
            particle.position.y += vec.y / 20F;
            particle.position.z += vec.z / 20F;
        }

        /* Regular lifetime, or the collision component's expirationDelay deadline — whichever
         * comes first. */
        int expireAge = ((IParticleCollisionAccess) particle).bbscgGetExpireAge();

        if (particle.lifetime >= 0 && particle.age >= particle.lifetime || expireAge != -1 && particle.age >= expireAge)
        {
            particle.setDead();
        }

        particle.age += 1;

        particle.acceleration.set(0F, 0F, 0F);
        particle.rotationAcceleration = 0;
        particle.dragFactor = 0;
    }

    /**
     * Register the extra components Snowstorm can emit into a parser's component registry.
     * Called from the ParticleParser mixin's constructor tail. Registration itself is harmless
     * — the components only ever act when the setting is on — but it must be unconditional so
     * {@code ParticleParser.toData} can map the component classes back to their JSON keys.
     */
    public static void registerComponents(ParticleParser parser)
    {
        parser.components.put("emitter_rate_manual", ParticleComponentRateManual.class);
        parser.components.put("emitter_lifetime_events", ParticleComponentEmitterLifetimeEvents.class);
        parser.components.put("particle_lifetime_events", ParticleComponentParticleLifetimeEvents.class);
        parser.components.put("blockbuster:particle_collision_appearance", ParticleComponentCollisionAppearance.class);
        parser.components.put("blockbuster:particle_collision_tinting", ParticleComponentCollisionTinting.class);
        parser.components.put("blockbuster:particle_morph", ParticleComponentParticleMorph.class);
    }

    /**
     * Collision-appearance lookup for the billboard mixin's "skip already collided particles"
     * guard. The per-frame cache is refreshed by the emitter mixin at the start of render();
     * {@code NONE} marks schemes without a collision appearance so a hundred-thousand-particle
     * frame doesn't rescan the component list per particle.
     */
    private static final Object NONE = new Object();

    private static final Map<ParticleEmitter, Object> COLLISION_APPEARANCES = new WeakHashMap<>();

    public static void updateCollisionRenderCache(ParticleEmitter emitter)
    {
        Object found = NONE;

        ParticleScheme scheme = emitter.scheme;

        if (scheme != null)
        {
            for (ParticleComponentBase component : scheme.components)
            {
                if (component instanceof ParticleComponentCollisionAppearance)
                {
                    found = component;

                    break;
                }
            }
        }

        COLLISION_APPEARANCES.put(emitter, found);
    }

    /**
     * Whether the given particle should render through its collision appearance rather than
     * the regular billboard (setting on, gate at 1, and the particle has collided).
     */
    public static boolean isCollisionTexture(ParticleEmitter emitter, Particle particle)
    {
        if (!isEnabled() || !emitter.running)
        {
            return false;
        }

        Object cached = COLLISION_APPEARANCES.get(emitter);

        return cached instanceof ParticleComponentCollisionAppearance
            && ((ParticleComponentCollisionAppearance) cached).isCollisionTexture(emitter, particle);
    }

    /**
     * Rename saved {@code minecraft:blockbuster:*} component keys back to {@code blockbuster:*}.
     *
     * <p>BBS's serializer writes every component as {@code "minecraft:" + key}, so a registered
     * {@code blockbuster:...} key round-trips as {@code minecraft:blockbuster:...}. Parsing
     * strips the {@code minecraft:} prefix, so both spellings load identically — but re-saving
     * without this normalization would keep growing the prefix on every editor round-trip.</p>
     */
    public static void normalizeBlockbusterKeys(MapType root)
    {
        BaseType effect = root != null ? root.get("particle_effect") : null;

        if (effect == null || !effect.isMap())
        {
            return;
        }

        BaseType components = effect.asMap().get("components");

        if (components == null || !components.isMap())
        {
            return;
        }

        MapType map = components.asMap();
        List<String> renamed = null;

        for (Map.Entry<String, BaseType> entry : map)
        {
            if (entry.getKey().startsWith("minecraft:blockbuster:"))
            {
                if (renamed == null)
                {
                    renamed = new ArrayList<>();
                }

                renamed.add(entry.getKey());
            }
        }

        if (renamed == null)
        {
            return;
        }

        for (String key : renamed)
        {
            map.put(key.substring("minecraft:".length()), map.get(key));
            map.remove(key);
        }
    }

    /**
     * Parse the top-level events table ({@code particle_effect.events}) of a particle file.
     * Called from the ParticleParser mixin after an effect finished parsing.
     */
    public static void parseEvents(ParticleScheme scheme, MapType root)
    {
        Map<String, ParticleEvent> events = null;

        BaseType effect = root != null ? root.get("particle_effect") : null;

        if (effect != null && effect.isMap())
        {
            BaseType table = effect.asMap().get("events");

            if (table != null && table.isMap())
            {
                events = new HashMap<>();

                for (Map.Entry<String, BaseType> entry : table.asMap())
                {
                    ParticleEvent event = ParticleEvent.parse(entry.getValue(), scheme.parser);

                    if (event != null)
                    {
                        events.put(entry.getKey(), event);
                    }
                }
            }
        }

        if (events != null && !events.isEmpty())
        {
            SCHEME_EVENTS.put(scheme, events);
        }
        else
        {
            SCHEME_EVENTS.remove(scheme);
        }
    }

    /**
     * Write the parsed events table back into the outgoing JSON. Called from the ParticleParser
     * mixin's toData return, so {@code ParticleScheme.dupe} and the editor's save round-trip
     * keep the events.
     */
    public static void writeEvents(MapType root, ParticleScheme scheme)
    {
        Map<String, ParticleEvent> events = SCHEME_EVENTS.get(scheme);

        if (events == null || events.isEmpty() || root == null)
        {
            return;
        }

        BaseType effect = root.get("particle_effect");

        if (effect == null || !effect.isMap())
        {
            effect = new MapType();
            root.put("particle_effect", effect);
        }

        effect.asMap().put("events", ParticleEvent.toDataMap(events));
    }

    /**
     * Fire the events the given references point at. {@code references} is a list of ids from
     * the top-level events table, collected by {@link ParticleEvent#parseReferences}.
     */
    public static void fireEvents(ParticleScheme scheme, List<String> references, ParticleEmitter emitter, Particle particle, Vector3d pos)
    {
        if (!isEnabled() || references == null || references.isEmpty() || scheme == null)
        {
            return;
        }

        Map<String, ParticleEvent> events = SCHEME_EVENTS.get(scheme);

        if (events == null)
        {
            return;
        }

        for (String reference : references)
        {
            ParticleEvent event = events.get(reference);

            if (event != null)
            {
                event.fire(emitter, particle, pos);
            }
        }
    }

    /**
     * Fire an event trigger field (single string or array of strings) straight from JSON-ish
     * data. Helper for components that store the raw references.
     */
    public static void fireReferences(ParticleScheme scheme, BaseType references, ParticleEmitter emitter, Particle particle, Vector3d pos)
    {
        fireEvents(scheme, ParticleEvent.parseReferences(references), emitter, particle, pos);
    }

    /**
     * Spawn a sub emitter for a {@code particle_effect} event. The child attaches to its parent
     * (the parent ticks and renders it), inherits the parent's world, target and user
     * variables, and is depth-limited to one level so event chains can't recurse forever.
     */
    public static void spawnChildEmitter(ParticleEmitter parent, String effect, Vector3d pos)
    {
        if (!isEnabled() || parent == null || !(parent instanceof IChildEmitterAccess))
        {
            return;
        }

        IChildEmitterAccess access = (IChildEmitterAccess) parent;
        int depth = access.bbscgGetDepth();

        /* One level of sub emitters only — an event chain that spawns emitters that spawn
         * emitters would otherwise explode combinatorially. */
        if (depth >= 1)
        {
            return;
        }

        ParticleScheme scheme = null;

        try
        {
            scheme = BBSModClient.getParticles().load(effect);
        }
        catch (Exception e)
        {}

        if (scheme == null)
        {
            return;
        }

        ParticleEmitter child = new ParticleEmitter();

        ((IChildEmitterAccess) child).bbscgSetDepth(depth + 1);
        child.setScheme(scheme);
        child.setTarget(parent.target);
        child.setWorld(parent.world);
        child.setUserVariables(parent.user1, parent.user2, parent.user3, parent.user4, parent.user5, parent.user6);

        if (pos != null)
        {
            child.lastGlobal.set(pos);
        }

        child.rotation.set(parent.rotation);

        access.bbscgAddChild(child);
    }

    /**
     * Compute the global position a particle's events should use. Mirrors
     * {@code Particle.getGlobalPosition} but is safe to call right after particle creation,
     * where the position has not yet been offset by the emitter's global position.
     */
    public static Vector3d getEventPosition(Particle particle, ParticleEmitter emitter)
    {
        if (particle == null || emitter == null)
        {
            return emitter != null ? emitter.lastGlobal : new Vector3d();
        }

        Vector3d position = new Vector3d(particle.position);

        if (particle.relativePosition && particle.relativeRotation)
        {
            position.add(emitter.lastGlobal);
        }

        return position;
    }

    /**
     * Parse a {@code timeline} field ({@code {"0.5": "event", ...}}) into a sorted list of
     * (time, references) entries.
     */
    public static List<TimelineEntry> parseTimeline(BaseType data)
    {
        List<TimelineEntry> entries = new ArrayList<>();

        if (data == null || !data.isMap())
        {
            return entries;
        }

        for (Map.Entry<String, BaseType> entry : data.asMap())
        {
            float time;

            try
            {
                time = Float.parseFloat(entry.getKey());
            }
            catch (NumberFormatException e)
            {
                continue;
            }

            List<String> references = ParticleEvent.parseReferences(entry.getValue());

            if (!references.isEmpty())
            {
                entries.add(new TimelineEntry(time, references));
            }
        }

        entries.sort((a, b) -> Float.compare(a.time, b.time));

        return entries;
    }

    public static class TimelineEntry
    {
        public final float time;
        public final List<String> references;

        public TimelineEntry(float time, List<String> references)
        {
            this.time = time;
            this.references = references;
        }
    }

    /**
     * Parse a distance-keyed events field into a sorted list of (distance, references) entries.
     * Accepts both the map form {@code {"0.5": "event"}} and the looping array form
     * {@code [{"distance": 0.5, "effects": "event"}]}.
     */
    public static List<DistanceEntry> parseDistanceEvents(BaseType data)
    {
        List<DistanceEntry> entries = new ArrayList<>();

        if (data == null)
        {
            return entries;
        }

        if (data.isMap())
        {
            for (Map.Entry<String, BaseType> entry : data.asMap())
            {
                float distance;

                try
                {
                    distance = Float.parseFloat(entry.getKey());
                }
                catch (NumberFormatException e)
                {
                    continue;
                }

                List<String> references = ParticleEvent.parseReferences(entry.getValue());

                if (!references.isEmpty())
                {
                    entries.add(new DistanceEntry(distance, references));
                }
            }
        }
        else if (data.isList())
        {
            for (BaseType entry : data.asList())
            {
                if (!entry.isMap())
                {
                    continue;
                }

                MapType map = entry.asMap();
                float distance = map.getFloat("distance");
                List<String> references = ParticleEvent.parseReferences(map.get("effects"));

                if (!references.isEmpty())
                {
                    entries.add(new DistanceEntry(distance, references));
                }
            }
        }

        entries.sort((a, b) -> Float.compare(a.distance, b.distance));

        return entries;
    }

    public static class DistanceEntry
    {
        public final float distance;
        public final List<String> references;

        public DistanceEntry(float distance, List<String> references)
        {
            this.distance = distance;
            this.references = references;
        }
    }
}
