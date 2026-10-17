package com.bbscg.mixin;

import com.bbscg.particles.IChildEmitterAccess;
import com.bbscg.particles.IParticleCollisionAccess;
import com.bbscg.particles.IParticleLocalSpaceAccess;
import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * See {@link SnowstormCompat} for the general background. This mixin adds:
 *
 * <ul>
 *     <li>the fixed physics update (see {@code SnowstormCompat.updateParticle});</li>
 *     <li>the local-space collision matrix identity (don't multiply by a rotation matrix the
 *     particle never filled in);</li>
 *     <li>per-particle child emitters for the event system ({@link IChildEmitterAccess});</li>
 *     <li>the collision runtime state ({@link IParticleCollisionAccess}): first intersection,
 *     per-axis last-collision ticks, expiration deadline, this-tick collision flag and the
 *     collision rotation drag — Blockbuster's {@code BedrockParticle} fields that BBS's
 *     particle simply doesn't have;</li>
 *     <li>the local-space physics flags ({@link IParticleLocalSpaceAccess}):
 *     {@code direction}/{@code acceleration}/{@code gravity} from Blockbuster's extended
 *     {@code emitter_local_space}.</li>
 * </ul>
 */
@Mixin(Particle.class)
public abstract class ParticleMixin implements IChildEmitterAccess, IParticleCollisionAccess, IParticleLocalSpaceAccess
{
    /* -------------------------------------------------------------------
     * Child emitters (event system)
     * ------------------------------------------------------------------- */

    @Unique
    private java.util.List<ParticleEmitter> bbscgChildren;

    public java.util.List<ParticleEmitter> bbscgChildren()
    {
        if (this.bbscgChildren == null)
        {
            this.bbscgChildren = new java.util.ArrayList<>();
        }

        return this.bbscgChildren;
    }

    /* -------------------------------------------------------------------
     * Collision runtime state
     * ------------------------------------------------------------------- */

    @Unique
    private boolean bbscgIntersected;

    @Unique
    private int bbscgFirstIntersection;

    @Unique
    private Vector3f bbscgCollisionTime;

    @Unique
    private int bbscgExpireAge;

    @Unique
    private int bbscgExpirationDelay;

    @Unique
    private boolean bbscgCollided;

    @Unique
    private float bbscgRotationCollisionDrag;

    /* -------------------------------------------------------------------
     * Local space physics flags
     * ------------------------------------------------------------------- */

    @Unique
    private boolean bbscgRelativeDirection;

    @Unique
    private boolean bbscgRelativeAcceleration;

    @Unique
    private boolean bbscgGravity;

    @Override
    public boolean bbscgIsIntersected()
    {
        return this.bbscgIntersected;
    }

    @Override
    public int bbscgGetFirstIntersection()
    {
        /* 0 is the dropped-initializer default; only meaningful once intersected. */
        return this.bbscgIntersected ? this.bbscgFirstIntersection : -1;
    }

    @Override
    public void bbscgMarkIntersected(int age)
    {
        if (!this.bbscgIntersected)
        {
            this.bbscgIntersected = true;
            this.bbscgFirstIntersection = age;
        }
    }

    @Override
    public Vector3f bbscgGetCollisionTime()
    {
        if (this.bbscgCollisionTime == null)
        {
            this.bbscgCollisionTime = new Vector3f(-2F, -2F, -2F);
        }

        return this.bbscgCollisionTime;
    }

    @Override
    public int bbscgGetExpireAge()
    {
        return this.bbscgExpireAge > 0 ? this.bbscgExpireAge : -1;
    }

    @Override
    public int bbscgGetExpirationDelay()
    {
        return this.bbscgExpirationDelay;
    }

    @Override
    public void bbscgSetExpirationDelay(double delay)
    {
        int ticks = (int) Math.max(0, delay);
        int deadline = ((Particle) (Object) this).age + ticks;
        int current = this.bbscgGetExpireAge();

        this.bbscgExpirationDelay = ticks;
        this.bbscgExpireAge = current != -1 ? Math.min(current, deadline) : deadline;
    }

    @Override
    public boolean bbscgHasCollided()
    {
        return this.bbscgCollided;
    }

    @Override
    public void bbscgSetCollided(boolean collided)
    {
        this.bbscgCollided = collided;
    }

    @Override
    public float bbscgGetRotationCollisionDrag()
    {
        return this.bbscgRotationCollisionDrag;
    }

    @Override
    public void bbscgSetRotationCollisionDrag(float drag)
    {
        this.bbscgRotationCollisionDrag = drag;
    }

    @Override
    public boolean bbscgIsRelativeDirection()
    {
        return this.bbscgRelativeDirection;
    }

    @Override
    public void bbscgSetRelativeDirection(boolean relative)
    {
        this.bbscgRelativeDirection = relative;
    }

    @Override
    public boolean bbscgIsRelativeAcceleration()
    {
        return this.bbscgRelativeAcceleration;
    }

    @Override
    public void bbscgSetRelativeAcceleration(boolean relative)
    {
        this.bbscgRelativeAcceleration = relative;
    }

    @Override
    public boolean bbscgHasGravity()
    {
        return this.bbscgGravity;
    }

    @Override
    public void bbscgSetGravity(boolean gravity)
    {
        this.bbscgGravity = gravity;
    }

    /* -------------------------------------------------------------------
     * Behaviour replacements
     * ------------------------------------------------------------------- */

    @Redirect(
        method = "setupMatrix",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix3f;identity()Lorg/joml/Matrix3f;"
        )
    )
    private Matrix3f bbscgUseRealMatrix(Matrix3f matrix)
    {
        /* When only relativeVelocity is on (and neither relativePosition nor
         * relativeRotation), stock multiplies the velocity by an identity matrix built from
         * the emitter quaternion — but never fills it in when the emitter has no rotation
         * either, leaving whatever was in the matrix before. Use a real identity. */
        Particle self = (Particle) (Object) this;

        if (self.relativePosition || self.relativeRotation)
        {
            return matrix;
        }

        return matrix.identity();
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void bbscgUpdatePhysics(ParticleEmitter emitter, CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        /* Fresh "collided this tick" flag for the collision component. */
        this.bbscgSetCollided(false);

        SnowstormCompat.updateParticle((Particle) (Object) this, emitter);
        ci.cancel();
    }
}
