package com.bbscg.particles;

import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

import java.util.List;

/**
 * Duck interface implemented (via mixin) on {@link ParticleEmitter} so {@link SnowstormCompat}
 * and the event components can manage sub emitters spawned by {@code particle_effect} events
 * without touching BBS source.
 */
public interface IChildEmitterAccess
{
    /**
     * Sub emitters spawned by particle events. Ticked and rendered by the parent's emitter
     * mixin right after the parent itself.
     */
    List<ParticleEmitter> bbscgGetChildren();

    void bbscgAddChild(ParticleEmitter child);

    /**
     * Event-spawn depth: 0 for a scene emitter, 1 for a child spawned by an event. Children
     * don't spawn further children — see {@link SnowstormCompat#spawnChildEmitter}.
     */
    int bbscgGetDepth();

    void bbscgSetDepth(int depth);
}
