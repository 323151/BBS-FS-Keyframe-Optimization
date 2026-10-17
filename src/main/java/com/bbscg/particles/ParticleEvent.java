package com.bbscg.particles;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One entry of the top-level {@code particle_effect.events} table of a Bedrock particle file, as
 * exported by Snowstorm.
 *
 * <p>An event is either a leaf that does something — run an {@code expression}, play a
 * {@code sound_effect}, or spawn a sub emitter via {@code particle_effect} — or a container
 * ({@code sequence} runs its children in order, {@code randomize} picks one by weight) that
 * nests further events. The original JSON is kept around ({@link #rawData}) so a parsed scheme
 * can still be re-serialized ({@link ParticleScheme#dupe} and the particle editor's save path
 * both round-trip through {@code ParticleParser.toData}).</p>
 */
public class ParticleEvent
{
    /* Leaf payloads */
    public String particleEffect;
    public MolangExpression preEffect;
    public String soundEvent;
    public MolangExpression expression;

    /* Container payloads */
    public List<ParticleEvent> sequence;
    public List<RandomEntry> randomize;

    /* The original JSON node, kept for round-tripping back to data */
    public BaseType rawData;

    private static final Random RNG = new Random();

    public static class RandomEntry
    {
        public double weight = 1;
        public ParticleEvent event;
    }

    /**
     * Parse one event node. {@code data} may be a map (the normal case) or, recursively, any
     * shape that map contains inside {@code sequence}/{@code randomize}.
     */
    public static ParticleEvent parse(BaseType data, MolangParser parser)
    {
        if (!data.isMap())
        {
            return null;
        }

        ParticleEvent event = new ParticleEvent();
        MapType map = data.asMap();

        event.rawData = data;

        if (map.has("sequence"))
        {
            event.sequence = new ArrayList<>();

            for (BaseType entry : map.getList("sequence"))
            {
                ParticleEvent child = parse(entry, parser);

                if (child != null)
                {
                    event.sequence.add(child);
                }
            }
        }

        if (map.has("randomize"))
        {
            event.randomize = new ArrayList<>();

            for (BaseType entry : map.getList("randomize"))
            {
                if (!entry.isMap())
                {
                    continue;
                }

                MapType option = entry.asMap();
                RandomEntry random = new RandomEntry();

                if (option.has("weight")) random.weight = option.getFloat("weight");

                /* The option map doubles as the child event node; parse() only looks at the
                 * keys it knows, so the "weight" field it also carries is simply ignored. */
                random.event = parse(option, parser);

                if (random.event != null)
                {
                    event.randomize.add(random);
                }
            }
        }

        if (map.has("particle_effect"))
        {
            BaseType effect = map.get("particle_effect");

            if (effect.isMap())
            {
                MapType particleEffect = effect.asMap();

                if (particleEffect.has("effect")) event.particleEffect = particleEffect.getString("effect");
                if (particleEffect.has("pre_effect_expression")) event.preEffect = parser.parseDataSilently(particleEffect.get("pre_effect_expression"));
            }
        }

        if (map.has("sound_effect"))
        {
            BaseType sound = map.get("sound_effect");

            if (sound.isMap() && sound.asMap().has("event_name"))
            {
                event.soundEvent = sound.asMap().getString("event_name");
            }
        }

        if (map.has("expression"))
        {
            event.expression = parser.parseDataSilently(map.get("expression"));
        }

        return event;
    }

    public BaseType toData()
    {
        return this.rawData != null ? this.rawData : new MapType();
    }

    /**
     * Execute this event against the given emitter context. {@code particle} is the particle
     * that caused the event (null for emitter-level events); {@code pos} is the global spawn
     * position sub emitters should appear at.
     */
    public void fire(ParticleEmitter parent, Particle particle, Vector3d pos)
    {
        if (this.expression != null)
        {
            this.expression.get();
        }

        if (this.soundEvent != null && parent.world != null)
        {
            try
            {
                SoundEvent sound = Registries.SOUND_EVENT.get(new Identifier(this.soundEvent));

                if (sound != null)
                {
                    parent.world.playSound(pos.x, pos.y, pos.z, sound, SoundCategory.PLAYERS, 1F, 1F, false);
                }
            }
            catch (Exception e)
            {}
        }

        if (this.particleEffect != null)
        {
            if (this.preEffect != null)
            {
                this.preEffect.get();
            }

            SnowstormCompat.spawnChildEmitter(parent, this.particleEffect, pos);
        }

        if (this.sequence != null)
        {
            for (ParticleEvent event : this.sequence)
            {
                event.fire(parent, particle, pos);
            }
        }

        if (this.randomize != null && !this.randomize.isEmpty())
        {
            double total = 0;

            for (RandomEntry entry : this.randomize)
            {
                total += entry.weight;
            }

            double pick = RNG.nextDouble() * Math.max(total, 1.0E-4);
            double cumulative = 0;

            for (RandomEntry entry : this.randomize)
            {
                cumulative += entry.weight;

                if (pick <= cumulative)
                {
                    entry.event.fire(parent, particle, pos);

                    return;
                }
            }

            this.randomize.get(this.randomize.size() - 1).event.fire(parent, particle, pos);
        }
    }

    /**
     * Serialize an events table (id to event) back into the shape Snowstorm writes.
     */
    public static MapType toDataMap(Map<String, ParticleEvent> events)
    {
        MapType data = new MapType();

        for (Map.Entry<String, ParticleEvent> entry : events.entrySet())
        {
            BaseType payload = entry.getValue().toData();

            if (payload != null && !ParticleScheme.PARSER.isEmpty(payload))
            {
                data.put(entry.getKey(), payload);
            }
        }

        return data;
    }

    /**
     * Collect the event ids an event-trigger field refers to. The Bedrock format allows either
     * a single string or an array of strings.
     */
    public static List<String> parseReferences(BaseType data)
    {
        List<String> ids = new ArrayList<>();

        /* Almost every component carrying an event trigger omits the field entirely
         * (map.get returns null) — without this guard the NPE bubbles out of the
         * component's fromData, BBS's ParticleManager.createData swallows it with
         * printStackTrace, and the scheme silently loses this component plus every
         * component ordered after it in the JSON. */
        if (data == null)
        {
            return ids;
        }

        if (data.isString())
        {
            ids.add(data.asString());
        }
        else if (data.isList())
        {
            ListType list = data.asList();

            for (int i = 0, c = list.size(); i < c; i++)
            {
                BaseType entry = list.get(i);

                if (entry.isString())
                {
                    ids.add(entry.asString());
                }
            }
        }

        return ids;
    }

    /**
     * Turn parsed event ids back into their JSON form (single string for one id, array for
     * several, null for none).
     */
    public static BaseType referencesToData(List<String> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return null;
        }

        if (ids.size() == 1)
        {
            return new StringType(ids.get(0));
        }

        ListType list = new ListType();

        for (String id : ids)
        {
            list.add(new StringType(id));
        }

        return list;
    }
}
