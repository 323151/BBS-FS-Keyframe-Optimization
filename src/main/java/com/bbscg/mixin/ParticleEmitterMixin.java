package com.bbscg.mixin;

import com.bbscg.particles.IChildEmitterAccess;
import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.math.Variable;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.World;
import org.joml.Matrix3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.gl.ShaderProgram;

/**
 * Lets a {@code particle_effect} event spawn sub emitters: children are ticked right after
 * the parent's update, rendered right after the parent's render (both world and UI preview),
 * and removed once they run out of particles while stopped (or hit a safety age cap, so a
 * looping sub effect can't leak forever).
 *
 * <p>Everything is inert while the {@code snowstorm_particles} setting is off — in that case
 * no children are ever spawned, and any leftovers (from switching the setting off mid-run)
 * are dropped at the next update.</p>
 */
@Mixin(ParticleEmitter.class)
public abstract class ParticleEmitterMixin implements IChildEmitterAccess
{
    @Shadow
    public ParticleScheme scheme;

    @Shadow
    public World world;

    @Shadow
    public boolean playing;

    @Shadow
    public int age;

    @Shadow
    public float cYaw;

    @Shadow
    public float cPitch;

    @Shadow
    public double cX;

    @Shadow
    public double cY;

    @Shadow
    public double cZ;

    @Shadow
    public Matrix3f rotation;

    @Shadow
    public float random1;

    @Shadow
    public float random2;

    @Shadow
    public float random3;

    @Shadow
    public float random4;

    @Shadow
    public int lifetime;

    @Unique
    private List<ParticleEmitter> bbscgChildren;

    @Unique
    private int bbscgDepth;

    /* @Unique field initializers are dropped by mixin merging — init lazily instead */
    private List<ParticleEmitter> bbscgChildren()
    {
        if (this.bbscgChildren == null)
        {
            this.bbscgChildren = new ArrayList<>();
        }

        return this.bbscgChildren;
    }

    @Override
    public List<ParticleEmitter> bbscgGetChildren()
    {
        return this.bbscgChildren();
    }

    @Override
    public void bbscgAddChild(ParticleEmitter child)
    {
        this.bbscgChildren().add(child);
    }

    @Override
    public int bbscgGetDepth()
    {
        return this.bbscgDepth;
    }

    @Override
    public void bbscgSetDepth(int depth)
    {
        this.bbscgDepth = depth;
    }

    /* Switching the emitter's scheme replaces the effect — drop the old children */
    @Inject(method = "setScheme", at = @At("HEAD"))
    private void bbscgClearChildrenOnRescheme(ParticleScheme scheme, CallbackInfo ci)
    {
        if (this.bbscgChildren != null)
        {
            this.bbscgChildren.clear();
        }
    }

    /* -------------------------------------------------------------------
     * variable.particle_speed.{x,y,z,length}
     * ------------------------------------------------------------------- */

    @Unique
    private Variable bbscgVarSpeedX;

    @Unique
    private Variable bbscgVarSpeedY;

    @Unique
    private Variable bbscgVarSpeedZ;

    @Unique
    private Variable bbscgVarSpeedLength;

    /**
     * Cache the speed variables after the scheme's variable table is (re)created. Registration
     * is unconditional (harmless — Molang auto-registers unknown variables as 0 anyway), so
     * expressions referencing them compile identically regardless of the setting.
     */
    @Inject(method = "setupVariables", at = @At("TAIL"))
    private void bbscgCacheSpeedVariables(CallbackInfo ci)
    {
        if (this.scheme != null)
        {
            this.bbscgVarSpeedX = this.scheme.parser.getOrCreateVariable("variable.particle_speed.x");
            this.bbscgVarSpeedY = this.scheme.parser.getOrCreateVariable("variable.particle_speed.y");
            this.bbscgVarSpeedZ = this.scheme.parser.getOrCreateVariable("variable.particle_speed.z");
            this.bbscgVarSpeedLength = this.scheme.parser.getOrCreateVariable("variable.particle_speed.length");

            /* Bare `pt` / `et` shorthands many lz effects rely on without defining them */
            this.bbscgVarPt = this.scheme.parser.getOrCreateVariable("pt");
            this.bbscgVarEt = this.scheme.parser.getOrCreateVariable("et");
        }
    }

    /* -------------------------------------------------------------------
     * Bare pt/et progress variables (laser/thrust/tide effects)
     * ------------------------------------------------------------------- */

    @Unique
    private Variable bbscgVarPt;

    @Unique
    private Variable bbscgVarEt;

    /**
     * Default {@code et} (emitter progress) before anything else evaluates it — mirrors
     * {@code variable.emitter_age / variable.emitter_lifetime}. Effects that define their
     * own {@code et} via emitter_initialization simply overwrite this with the same value.
     */
    @Inject(method = "setEmitterVariables", at = @At("TAIL"))
    private void bbscgSetEmitterProgress(float transition, CallbackInfo ci)
    {
        if (SnowstormCompat.isEnabled() && this.bbscgVarEt != null && this.lifetime > 0)
        {
            this.bbscgVarEt.set((this.age + transition) / (float) this.lifetime);
        }
    }

    /**
     * Blockbuster parity — its {@code start()} re-runs every emitter initialize component
     * (the {@code creation_expression}) whenever a stopped emitter starts again, so looping
     * effects re-roll their per-cycle randoms (blade arc heights, splash seeds...).
     */
    @Inject(method = "start", at = @At("TAIL"))
    private void bbscgRunCreationOnStart(CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled() || this.scheme == null)
        {
            return;
        }

        for (IComponentEmitterInitialize component : this.scheme.emitterInitializes)
        {
            component.apply((ParticleEmitter) (Object) this);
        }
    }

    /**
     * Blockbuster parity — its {@code stop()} re-rolls emitter_random_1..4 so the next
     * cycle's creation expressions see fresh random values.
     */
    @Inject(method = "stop", at = @At("TAIL"))
    private void bbscgRerollRandomsOnStop(CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        this.random1 = (float) Math.random();
        this.random2 = (float) Math.random();
        this.random3 = (float) Math.random();
        this.random4 = (float) Math.random();
    }

    /**
     * Run the initialization component's {@code particle_update_expression} after the particle's
     * standard variables (age, lifetime, randoms, position...) are loaded — the same spot
     * Blockbuster runs it from. This covers all three moments the variables are refreshed:
     * particle creation (before the shape component consumes them), the per-tick update
     * (before the update components), and each render frame (before render components
     * evaluate sizes/colors). Also refreshes {@code variable.particle_speed.*} so expressions
     * like the shield effects' {@code custom_direction} see the live velocity.
     */
    @Inject(method = "setParticleVariables", at = @At("TAIL"))
    private void bbscgRunParticleUpdate(Particle particle, float transition, CallbackInfo ci)
    {
        /* Default `pt` (particle progress) first — the scheme's particle_update_expression
         * (run right below) may then redefine it, which is fine since both use the same
         * age/lifetime ratio. Effects without an initializer (laser, thrust, tide...) keep
         * this default and their size/tinting formulas come alive. */
        if (SnowstormCompat.isEnabled() && this.bbscgVarPt != null && particle.lifetime > 0)
        {
            this.bbscgVarPt.set((particle.age + transition) / (float) particle.lifetime);
        }

        SnowstormCompat.runParticleUpdate(this.scheme);

        if (SnowstormCompat.isEnabled() && this.bbscgVarSpeedX != null)
        {
            this.bbscgVarSpeedX.set(particle.speed.x);
            this.bbscgVarSpeedY.set(particle.speed.y);
            this.bbscgVarSpeedZ.set(particle.speed.z);
            this.bbscgVarSpeedLength.set(particle.speed.length());
        }
    }

    /* -------------------------------------------------------------------
     * Collision appearance render cache
     * ------------------------------------------------------------------- */

    /**
     * Refresh the per-frame "does this scheme have an active collision appearance" lookup
     * before the render loop runs, so the billboard skip guard is O(1) per particle.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void bbscgRefreshCollisionRenderCache(VertexFormat format, Supplier<ShaderProgram> program, MatrixStack stack, int overlay, float transition, boolean blend, CallbackInfo ci)
    {
        SnowstormCompat.updateCollisionRenderCache((ParticleEmitter) (Object) this);
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void bbscgUpdateChildren(CallbackInfo ci)
    {
        if (this.bbscgChildren == null || this.bbscgChildren.isEmpty())
        {
            return;
        }

        if (!SnowstormCompat.isEnabled())
        {
            /* Setting got switched off mid-run — drop event-spawned children */
            this.bbscgChildren.clear();

            return;
        }

        Iterator<ParticleEmitter> it = this.bbscgChildren.iterator();

        while (it.hasNext())
        {
            ParticleEmitter child = it.next();

            child.update();

            /* A child is done when it stopped and its last particles died; looping sub
             * effects keep going, but a safety age cap stops eventual leaks. */
            boolean done = (child.particles.isEmpty() && !child.playing)
                || (child.particles.isEmpty() && child.age > 6000);

            if (done)
            {
                it.remove();
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void bbscgRenderChildren(VertexFormat format, Supplier<ShaderProgram> program, MatrixStack stack, int overlay, float transition, boolean blend, CallbackInfo ci)
    {
        if (this.bbscgChildren == null || this.bbscgChildren.isEmpty())
        {
            return;
        }

        for (ParticleEmitter child : this.bbscgChildren)
        {
            /* Refresh the per-frame render context so camera-facing modes work in children */
            child.cYaw = this.cYaw;
            child.cPitch = this.cPitch;
            child.cX = this.cX;
            child.cY = this.cY;
            child.cZ = this.cZ;
            child.rotation.set(this.rotation);

            child.render(format, program, stack, overlay, transition, blend);
        }
    }

    @Inject(method = "renderUI", at = @At("TAIL"))
    private void bbscgRenderUIChildren(MatrixStack stack, float transition, CallbackInfo ci)
    {
        if (this.bbscgChildren == null)
        {
            return;
        }

        for (ParticleEmitter child : this.bbscgChildren)
        {
            child.renderUI(stack, transition);
        }
    }
}
