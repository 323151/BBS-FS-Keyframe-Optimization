package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformGesture;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores 2.5.2's first-press behaviour of the G/S/R hotkeys.
 *
 * <p>In 2.5.2 a G/S/R press whose operation didn't match the gizmo's displayed handles only
 * switched the display ({@code UIPropTransform#switchGizmoDisplayMode}); the operation started
 * on the second press. In 2.7 the gizmo shows everything at once, so
 * {@link TransformGesture#enableMode(TransformOp)} — the single-parameter, hotkey-driven
 * overload, which only the G/S/R keybinds call — starts the operation immediately (its own
 * comment: "there is no display mode for a press to switch first").
 *
 * <p>This addon re-introduces the display mode, so the press-to-switch comes back too: when
 * the display doesn't show the pressed operation yet, the press is consumed by the switch
 * and no edit starts. When the display already matches — or the mode is combined, where 2.5.2
 * also started the operation right away — the press falls through untouched.
 */
@Mixin(TransformGesture.class)
public abstract class TransformGestureMixin
{
    @Inject(
        method = "enableMode(Lmchorse/bbs_mod/ui/framework/elements/input/drag/TransformOp;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void bbscg$switchDisplayFirst(TransformOp op, CallbackInfo ci)
    {
        if (CombinedGizmoMode.switchDisplayMode(op))
        {
            ci.cancel();
        }
    }
}
