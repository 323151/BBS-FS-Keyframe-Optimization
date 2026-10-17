package com.bbscg.mixin;

import com.bbscg.CombinedGizmoKeys;
import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Registers the combined-mode toggle keybind wherever a transform's hotkeys are enabled.
 *
 * <p>2.5.2's {@code UIPropTransform#enableHotkeys} registered the T key alongside G/S/R
 * (2.5.2: {@code this.keys().register(Keys.TRANSFORMATIONS_COMBINED, () ->
 * Gizmo.INSTANCE.toggleCombined()).strict().active(enabled).category(category)}); 2.7 dropped
 * the whole display-mode system and that registration with it. Appending the same bind at the
 * tail of the two-argument overload — the one every other overload funnels into — puts it
 * back in every editor that hosts a transform: form panels, pose editors, model editor,
 * replays, model blocks, keyframe factories.
 *
 * <p>Strict, so plain T does not shadow Shift+T (the keyframes interpolation hotkey), and
 * active under the same {@code enabled} gate the caller passed for the rest of the hotkeys.
 */
@Mixin(UIPropTransform.class)
public abstract class UIPropTransformMixin
{
    @Inject(
        method = "enableHotkeys(Ljava/util/function/Supplier;Ljava/util/function/Predicate;)Lmchorse/bbs_mod/ui/framework/elements/input/UIPropTransform;",
        at = @At("TAIL")
    )
    private void bbscg$registerCombinedKeybind(Supplier<Boolean> enabled, Predicate<TransformOp> ops, CallbackInfoReturnable<UIPropTransform> cir)
    {
        UIPropTransform self = (UIPropTransform) (Object) this;

        self.keys().register(CombinedGizmoKeys.combined(), CombinedGizmoMode::toggleCombined)
            .strict()
            .active(enabled)
            .category(UIKeys.TRANSFORMS_KEYS_CATEGORY);
    }
}
