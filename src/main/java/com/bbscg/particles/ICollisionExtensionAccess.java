package com.bbscg.particles;

import mchorse.bbs_mod.math.molang.expressions.MolangExpression;

import java.util.List;

/**
 * The {@code particle_motion_collision} extension fields Snowstorm/Blockbuster effects use,
 * grafted onto BBS's {@code ParticleComponentMotionCollision} by its mixin.
 *
 * <p>Stock BBS 2.7 parses only {@code enabled}, {@code collision_drag},
 * {@code coefficient_of_restitution}, {@code collision_radius} and {@code expire_on_contact};
 * everything here is silently dropped on import. The mixin parses and writes them back
 * unconditionally (so schemes round-trip), while {@code SnowstormCollision} consumes them at
 * runtime — but only while the {@code snowstorm_particles} setting is on.</p>
 */
public interface ICollisionExtensionAccess
{
    /** Event ids (into the top-level events table) fired on every collision. */
    List<String> bbscgGetEvents();

    /** {@code realisticCollision}: reflect the velocity instead of flipping the acceleration factor. */
    boolean bbscgIsRealisticCollision();

    /** {@code realistic_collision_drag}: collision drag only applies while actually touching. */
    boolean bbscgIsRealisticCollisionDrag();

    /** {@code entityCollision}: collide against entity bounding boxes too. */
    boolean bbscgIsEntityCollision();

    /** {@code momentum}: entities push particles around by their own movement. */
    boolean bbscgIsMomentum();

    /** {@code damp}: multiplicative velocity damping on impact (Blender-style). */
    float bbscgGetDamp();

    /** {@code random_damp}: random jitter added to the damping factor. */
    float bbscgGetRandomDamp();

    /** {@code bounciness_randomness}: random spread of the bounce direction. */
    float bbscgGetRandomBounciness();

    /** {@code collision_rotation_drag}: extra spin drag while touching a surface. */
    float bbscgGetRotationCollisionDrag();

    /** {@code split_particle_count}: how many children a hard-enough impact splits into. */
    int bbscgGetSplitParticleCount();

    /** {@code split_particle_speedThreshold}: speed above which the split happens. */
    float bbscgGetSplitParticleSpeedThreshold();

    /** {@code expirationDelay}: ticks to live after the (expiring) collision. */
    MolangExpression bbscgGetExpirationDelay();
}
