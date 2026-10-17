package com.bbscg.particles;

import mchorse.bbs_mod.particles.emitter.Particle;
import org.joml.Vector3f;

/**
 * Collision runtime state grafted onto {@link Particle} by {@code ParticleMixin}.
 *
 * <p>Blockbuster's {@code BedrockParticle} carries all of this as regular fields — first
 * intersection age, per-axis last-collision ticks, the expiration deadline set by
 * {@code expirationDelay}, and the "did this tick already handle a collision" flag. BBS 2.7's
 * {@code Particle} has none of it, so the collision-after appearance components
 * ({@code blockbuster:particle_collision_appearance} / {@code particle_collision_tinting}) and
 * the extended {@code particle_motion_collision} fields have nowhere to live. The mixin adds
 * them as {@code @Unique} fields behind this interface.</p>
 *
 * <p>Conventions: {@code -1} (or {@code -2} per axis for {@code collisionTime}) means "unset",
 * mirroring Blockbuster's sentinel values. All fields default to unset and are only ever
 * written while the {@code snowstorm_particles} setting is on.</p>
 */
public interface IParticleCollisionAccess
{
    /** Whether this particle has hit a block or entity at least once in its life. */
    boolean bbscgIsIntersected();

    /** The particle's age (in ticks) at its first intersection; {@code -1} while never hit. */
    int bbscgGetFirstIntersection();

    /** Mark the particle as intersected, recording {@code age} as the first intersection. */
    void bbscgMarkIntersected(int age);

    /** Per-axis tick of the last collision ({@code -2} per axis while unset). */
    Vector3f bbscgGetCollisionTime();

    /** Absolute age (in ticks) at which the particle dies; {@code -1} while no deadline set. */
    int bbscgGetExpireAge();

    /** The configured {@code expirationDelay} in ticks ({@code 0} while unset). */
    int bbscgGetExpirationDelay();

    /**
     * Set the expiration delay (Blockbuster semantics: the earliest deadline wins, so a
     * particle resting against a surface doesn't keep pushing its death further away).
     */
    void bbscgSetExpirationDelay(double delay);

    /** Whether a collision has already been processed this tick (for {@code expirationDelay}). */
    boolean bbscgHasCollided();

    void bbscgSetCollided(boolean collided);

    /** The {@code collision_rotation_drag} the collision component wants applied while touching. */
    float bbscgGetRotationCollisionDrag();

    void bbscgSetRotationCollisionDrag(float drag);
}
