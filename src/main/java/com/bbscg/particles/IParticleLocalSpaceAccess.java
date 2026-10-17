package com.bbscg.particles;

import mchorse.bbs_mod.particles.emitter.Particle;

/**
 * Local-space physics flags grafted onto {@link Particle} by {@code ParticleMixin}.
 *
 * <p>Blockbuster's {@code emitter_local_space} component accepts three extra booleans beyond
 * BBS's {@code position}/{@code rotation}/{@code velocity}/{@code texture_scale}:
 * {@code direction} (the initial speed is expressed in the emitter's local space),
 * {@code acceleration} (the acceleration vector is re-rotated by the emitter every tick) and
 * {@code gravity} (a 9.81 blocks/s² downward acceleration is applied). The local space mixin
 * parses them and writes them here at apply time; {@code SnowstormCompat.updateParticle}
 * consumes them during physics integration.</p>
 */
public interface IParticleLocalSpaceAccess
{
    boolean bbscgIsRelativeDirection();

    void bbscgSetRelativeDirection(boolean relative);

    boolean bbscgIsRelativeAcceleration();

    void bbscgSetRelativeAcceleration(boolean relative);

    boolean bbscgHasGravity();

    void bbscgSetGravity(boolean gravity);
}
