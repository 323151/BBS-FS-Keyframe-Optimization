package com.bbscg.mixin;

import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentLocalSpace;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Maps Blockbuster's {@code emitter_local_space} extensions {@code scale} and
 * {@code scale_billboard} onto BBS's stock machinery, but only while the
 * {@code snowstorm_particles} setting is on (stock 2.7 ignores both keys).
 *
 * <p>BBS's {@code texture_scale} flag is the exact equivalent of Blockbuster's
 * {@code scale_billboard}: {@code Particle.setupMatrix} captures the emitter's scale
 * factor into {@code particle.matrix}, and the billboard renderer multiplies the
 * computed size by it. Position scaling ({@code scale: true}) needs no extra work
 * when {@code position: true} — the captured matrix already carries the scale.</p>
 *
 * <p>Crucial detail: a bare {@code new Matrix3f()} is all-zero in JOML, and emitters
 * that were never attached to a locator keep a zero rotation matrix. Feeding a zero
 * row length into {@code matrix.scale(0)} collapses the whole matrix — and with it
 * every parametric position transform — to zero, which squashed effects like the
 * black hole into a single invisible point. The mapping therefore only engages when
 * the emitter's scale factor is real (non-zero, finite); a fallback keeps stock
 * behaviour everywhere else.</p>
 *
 * <p>Both keys are parsed and written back unconditionally so schemes round-trip
 * through BBS's save code without losing them; the behavioural switch is checked at
 * apply time, so toggling the setting takes effect immediately.</p>
 */
@Mixin(ParticleComponentLocalSpace.class)
public abstract class ParticleComponentLocalSpaceMixin
{
    @Unique
    private boolean bbscgScaleBillboard;

    @Unique
    private boolean bbscgScale;

    @Unique
    private boolean bbscgRelativeDirection;

    @Unique
    private boolean bbscgRelativeAcceleration;

    @Unique
    private boolean bbscgGravity;

    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbscgParseScaleKeys(BaseType data, MolangParser parser, CallbackInfoReturnable<ParticleComponentBase> ci)
    {
        this.bbscgScaleBillboard = false;
        this.bbscgScale = false;
        this.bbscgRelativeDirection = false;
        this.bbscgRelativeAcceleration = false;
        this.bbscgGravity = false;

        if (data.isMap())
        {
            MapType map = data.asMap();

            if (map.has("scale_billboard")) this.bbscgScaleBillboard = map.getBool("scale_billboard");
            if (map.has("scale")) this.bbscgScale = map.getBool("scale");
            if (map.has("direction")) this.bbscgRelativeDirection = map.getBool("direction");
            if (map.has("acceleration")) this.bbscgRelativeAcceleration = map.getBool("acceleration");
            if (map.has("gravity")) this.bbscgGravity = map.getBool("gravity");
        }
    }

    @Inject(method = "toData(Lmchorse/bbs_mod/data/types/MapType;)V", at = @At("TAIL"))
    private void bbscgWriteScaleKeys(MapType data, CallbackInfo ci)
    {
        if (this.bbscgScaleBillboard) data.putBool("scale_billboard", true);
        if (this.bbscgScale) data.putBool("scale", true);
        if (this.bbscgRelativeDirection) data.putBool("direction", true);
        if (this.bbscgRelativeAcceleration) data.putBool("acceleration", true);
        if (this.bbscgGravity) data.putBool("gravity", true);
    }

    /**
     * Push the three physics flags onto the particle after the stock flags are applied —
     * {@code SnowstormCompat.updateParticle} consumes them during integration (initial speed
     * rotated into the emitter frame, acceleration re-rotated every tick, gravity added).
     */
    @Inject(method = "apply", at = @At("TAIL"))
    private void bbscgApplyPhysicsFlags(ParticleEmitter emitter, Particle particle, CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        com.bbscg.particles.IParticleLocalSpaceAccess access = (com.bbscg.particles.IParticleLocalSpaceAccess) particle;

        access.bbscgSetRelativeDirection(this.bbscgRelativeDirection);
        access.bbscgSetRelativeAcceleration(this.bbscgRelativeAcceleration);
        access.bbscgSetGravity(this.bbscgGravity);
    }

    /**
     * Intercepts the setupMatrix call inside apply() so the mapping decision can look at
     * the emitter's rotation matrix before the particle's matrix gets built. The field
     * redirect approach (as used before) couldn't see the emitter and unconditionally
     * enabled textureScale, which collapsed unattached emitters' particles to origin.
     */
    @Redirect(
        method = "apply",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/particles/emitter/Particle;setupMatrix(Lmchorse/bbs_mod/particles/emitter/ParticleEmitter;)V"
        )
    )
    private void bbscgSetupMatrixWithScaleBillboard(Particle particle, ParticleEmitter emitter)
    {
        if (SnowstormCompat.isEnabled() && this.bbscgScaleBillboard && !particle.textureScale)
        {
            Vector3f row = new Vector3f();
            float scale = emitter.rotation.getRow(0, row).length();

            if (scale > 1.0E-4F && Float.isFinite(scale))
            {
                particle.textureScale = true;
            }
        }

        particle.setupMatrix(emitter);
    }
}
