package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentParticleInitialize;
import mchorse.bbs_mod.particles.components.IComponentParticleUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code minecraft:particle_lifetime_events} component: creation/expiration events and a
 * per-particle timeline, referring to entries of the top-level {@code particle_effect.events}
 * table.
 *
 * <p>Stock BBS 2.7 doesn't know this component, so Snowstorm's "Particle Event Triggers" are
 * lost on import. Creation fires right when the particle spawns, expiration during the tick the
 * particle dies (this component updates after the expiration components thanks to its high
 * sorting index), and timeline entries fire during the tick their time is crossed.</p>
 */
public class ParticleComponentParticleLifetimeEvents extends ParticleComponentBase implements IComponentParticleInitialize, IComponentParticleUpdate
{
    public List<String> creationEvent = new ArrayList<>();
    public List<String> expirationEvent = new ArrayList<>();
    public List<SnowstormCompat.TimelineEntry> timeline = new ArrayList<>();

    @Override
    public int getSortingIndex()
    {
        /* Update after the expiration components (kill plane runs at 100) so particle death is
         * visible to this component during the same tick. Local space runs at 1000 and only
         * affects position, which doesn't matter for event triggers. */
        return 500;
    }

    @Override
    protected void toData(MapType data)
    {
        BaseType creation = ParticleEvent.referencesToData(this.creationEvent);

        if (creation != null) data.put("creation_event", creation);

        BaseType expiration = ParticleEvent.referencesToData(this.expirationEvent);

        if (expiration != null) data.put("expiration_event", expiration);

        if (!this.timeline.isEmpty())
        {
            MapType timeline = new MapType();

            for (SnowstormCompat.TimelineEntry entry : this.timeline)
            {
                BaseType references = ParticleEvent.referencesToData(entry.references);

                if (references != null)
                {
                    timeline.put(String.valueOf(entry.time), references);
                }
            }

            if (!timeline.isEmpty())
            {
                data.put("timeline", timeline);
            }
        }
    }

    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        this.creationEvent = ParticleEvent.parseReferences(map.get("creation_event"));
        this.expirationEvent = ParticleEvent.parseReferences(map.get("expiration_event"));
        this.timeline = SnowstormCompat.parseTimeline(map.get("timeline"));

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter, Particle particle)
    {
        if (!SnowstormCompat.isEnabled() || this.creationEvent.isEmpty())
        {
            return;
        }

        /* At creation time the particle position is still emitter-local, so the global event
         * position is simply the spawn position offset by the emitter's global position. */
        Vector3d pos = new Vector3d(particle.position).add(emitter.lastGlobal);

        SnowstormCompat.fireEvents(emitter.scheme, this.creationEvent, emitter, particle, pos);
    }

    @Override
    public void update(ParticleEmitter emitter, Particle particle)
    {
        if (!SnowstormCompat.isEnabled() || emitter.scheme == null)
        {
            return;
        }

        /* The particle's age has already been incremented for this tick by particle.update(),
         * so a timeline time t fires during the tick where t*20 first lands in (age-1, age]. */
        double age = particle.getAge(0);

        for (SnowstormCompat.TimelineEntry entry : this.timeline)
        {
            if (entry.time > age - 1 / 20.0 && entry.time <= age)
            {
                SnowstormCompat.fireEvents(emitter.scheme, entry.references, emitter, particle, particle.getGlobalPosition(emitter));
            }
        }

        if (particle.isDead() && !this.expirationEvent.isEmpty())
        {
            /* The particle dies this tick (only visible because this component updates after
             * the expiration components) — dead particles are removed right after the update
             * pass, so this fires exactly once. */
            SnowstormCompat.fireEvents(emitter.scheme, this.expirationEvent, emitter, particle, particle.getGlobalPosition(emitter));
        }
    }
}
