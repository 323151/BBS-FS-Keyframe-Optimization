package com.bbscg.mixin;

import com.bbscg.CombinedGizmoKeys;
import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The "fractional timeline dragging" feature: while the setting is on, holding the bound key
 * (left Alt by default) bypasses snap-to-ticks.
 *
 * <p>{@code isSnappingToTicks} is the single gate every time-snapped read goes through — the
 * playhead scrub ({@code fromGraphCursor} inside {@code moveNoKeyframes}), keyframe drags in the
 * dope sheet, pastes that align to the grid. Returning {@code false} here is the whole change:
 * every consumer keeps its fractional time instead of rounding to the whole tick, exactly what
 * Shift already does as a stock temporary bypass, but on a rebindable key of its own. The stock
 * behaviour is untouched whenever the setting is off or the key is not held.</p>
 */
@Mixin(UITimelineCanvas.class)
public abstract class UITimelineCanvasMixin
{
    @Inject(method = "isSnappingToTicks()Z", at = @At("RETURN"), cancellable = true)
    private void bbscg$fractionalScrub(CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ() && CombinedGizmoMode.isFractionalScrub() && CombinedGizmoKeys.snapBypass().isHeld())
        {
            cir.setReturnValue(false);
        }
    }
}
