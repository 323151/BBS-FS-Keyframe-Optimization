package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.IComponentEmitterUpdate;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@code minecraft:emitter_lifetime_events} component: creation/expiration events, a
 * second-precision timeline, and (looping) travel-distance events, all referring to entries of
 * the top-level {@code particle_effect.events} table.
 *
 * <p>Stock BBS 2.7 doesn't know this component, so Snowstorm's "Emitter Event Triggers" simply
 * vanish on import. This component re-adds them on top of the stock engine: emitter creation
 * fires when the emitter starts (including every wake-up of a looping emitter), expiration
 * when it stops (including every sleep of a looping emitter), timeline entries fire during the
 * tick they're crossed, and travel-distance entries fire as the emitter accumulates movement.
 * State (previous playing flag, distance counters) is kept per emitter in a weak map so the
 * shared scheme instance stays stateless.</p>
 */
public class ParticleComponentEmitterLifetimeEvents extends ParticleComponentBase implements IComponentEmitterInitialize, IComponentEmitterUpdate
{
    public List<String> creationEvent = new ArrayList<>();
    public List<String> expirationEvent = new ArrayList<>();
    public List<SnowstormCompat.TimelineEntry> timeline = new ArrayList<>();
    public List<SnowstormCompat.DistanceEntry> travelDistance = new ArrayList<>();
    public List<SnowstormCompat.DistanceEntry> loopingTravelDistance = new ArrayList<>();

    /* Per-emitter trigger state (emitters share scheme instances in BBS) */
    private final Map<ParticleEmitter, EmitterState> states = new WeakHashMap<>();

    private static class EmitterState
    {
        public boolean prevPlaying;
        public boolean initialized;
        public Vector3d lastPosition;
        public double distance;
        public int travelIndex;
        public double[] loopAccumulated;
    }

    @Override
    public int getSortingIndex()
    {
        /* Run after the lifetime components (so stop()/start() already happened this tick) */
        return 900;
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

        BaseType travel = this.distanceEventsToData(this.travelDistance, false);

        if (travel != null) data.put("travel_distance_events", travel);

        BaseType looping = this.distanceEventsToData(this.loopingTravelDistance, true);

        if (looping != null) data.put("looping_travel_distance_events", looping);
    }

    private BaseType distanceEventsToData(List<SnowstormCompat.DistanceEntry> entries, boolean looping)
    {
        if (entries.isEmpty())
        {
            return null;
        }

        if (!looping)
        {
            MapType map = new MapType();

            for (SnowstormCompat.DistanceEntry entry : entries)
            {
                BaseType references = ParticleEvent.referencesToData(entry.references);

                if (references != null)
                {
                    map.put(String.valueOf(entry.distance), references);
                }
            }

            return map.isEmpty() ? null : map;
        }

        ListType list = new ListType();

        for (SnowstormCompat.DistanceEntry entry : entries)
        {
            BaseType references = ParticleEvent.referencesToData(entry.references);

            if (references == null)
            {
                continue;
            }

            MapType option = new MapType();

            option.putFloat("distance", entry.distance);
            option.put("effects", references);
            list.add(option);
        }

        return list.isEmpty() ? null : list;
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
        this.travelDistance = SnowstormCompat.parseDistanceEvents(map.get("travel_distance_events"));
        this.loopingTravelDistance = SnowstormCompat.parseDistanceEvents(map.get("looping_travel_distance_events"));

        return super.fromData(map, parser);
    }

    @Override
    public void apply(ParticleEmitter emitter)
    {
        /* Fresh emitter — make sure it starts without stale state from a previous scheme */
        this.states.remove(emitter);
    }

    @Override
    public void update(ParticleEmitter emitter)
    {
        if (!SnowstormCompat.isEnabled() || emitter.scheme == null)
        {
            return;
        }

        EmitterState state = this.states.get(emitter);

        if (state == null)
        {
            state = new EmitterState();
            state.lastPosition = new Vector3d(emitter.lastGlobal);
            state.loopAccumulated = new double[this.loopingTravelDistance.size()];

            this.states.put(emitter, state);
        }

        boolean playing = emitter.playing;

        /* Creation fires on the first observed tick of a playing emitter (and again after
         * every wake-up of a looping emitter, since stop()/start() flip `playing`). */
        if (playing && !state.prevPlaying)
        {
            SnowstormCompat.fireEvents(emitter.scheme, this.creationEvent, emitter, null, emitter.lastGlobal);
        }

        if (!playing && state.prevPlaying && state.initialized)
        {
            SnowstormCompat.fireEvents(emitter.scheme, this.expirationEvent, emitter, null, emitter.lastGlobal);
        }

        state.prevPlaying = playing;
        state.initialized = true;

        if (!playing)
        {
            /* Freeze distance tracking while the emitter sleeps */
            state.lastPosition.set(emitter.lastGlobal);

            return;
        }

        /* Timeline: the component updates one tick before the emitter's age increments, so a
         * time t fires during the tick where t*20 first lands in (age, age + 1]. */
        double from = emitter.getAge();
        double to = from + 1 / 20.0;

        for (SnowstormCompat.TimelineEntry entry : this.timeline)
        {
            if (entry.time > from && entry.time <= to)
            {
                SnowstormCompat.fireEvents(emitter.scheme, entry.references, emitter, null, emitter.lastGlobal);
            }
        }

        /* Travel distance events */
        if (!this.travelDistance.isEmpty() || !this.loopingTravelDistance.isEmpty())
        {
            double dx = emitter.lastGlobal.x - state.lastPosition.x;
            double dy = emitter.lastGlobal.y - state.lastPosition.y;
            double dz = emitter.lastGlobal.z - state.lastPosition.z;
            double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);

            state.lastPosition.set(emitter.lastGlobal);
            state.distance += delta;

            while (state.travelIndex < this.travelDistance.size()
                && state.distance >= this.travelDistance.get(state.travelIndex).distance)
            {
                SnowstormCompat.fireEvents(emitter.scheme, this.travelDistance.get(state.travelIndex).references, emitter, null, emitter.lastGlobal);
                state.travelIndex++;
            }

            for (int i = 0; i < this.loopingTravelDistance.size(); i++)
            {
                SnowstormCompat.DistanceEntry entry = this.loopingTravelDistance.get(i);

                state.loopAccumulated[i] += delta;

                while (state.loopAccumulated[i] >= entry.distance)
                {
                    state.loopAccumulated[i] -= entry.distance;

                    SnowstormCompat.fireEvents(emitter.scheme, entry.references, emitter, null, emitter.lastGlobal);
                }
            }
        }
    }
}
