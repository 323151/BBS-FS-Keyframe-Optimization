package com.bbscg.mixin;

import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.particles.components.appearance.colors.Gradient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fixes gradient color interpolation for Snowstorm files whose gradient "Range" exceeds 1.
 *
 * <p>Snowstorm encodes gradient stop positions in the emitter's Range space — with Range 2,
 * stops get keys "0" and "2" and the interpolant (typically particle age) runs up to 2.
 * Stock BBS 2.7 clamps the interpolant to 0..1 before searching the stops, so the second half
 * of the gradient never plays and colors freeze at the midpoint. While the
 * {@code snowstorm_particles} setting is on, the clamp upper bound becomes the last stop
 * (already stored in the gradient's {@code range} field), which is a no-op for 0..1 gradients
 * and correct for wider ones.</p>
 */
@Mixin(Gradient.class)
public abstract class ParticleGradientMixin
{
    @Shadow
    public float range;

    @ModifyArg(
        method = "compute",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/utils/MathUtils;clamp(DDD)D"
        ),
        index = 2
    )
    private double bbscgClampToGradientRange(double max)
    {
        return SnowstormCompat.isEnabled() ? this.range : max;
    }
}
