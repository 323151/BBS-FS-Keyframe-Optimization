package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.utils.Gizmo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Filters the gizmo's per-frame handle mask by the active display mode.
 *
 * <p>2.7's gizmo takes the frame's {@link Gizmo.HandleMask} in three capture calls — the world
 * render, the interface (visual) capture and the pick stencil — and everything downstream reads
 * it back from the {@code Gizmo.INSTANCE} singleton: the drawn handles, the stencil pick, the
 * hover highlight, the gesture-start gate ({@code start()} refuses a handle the mask rejects)
 * and the move/scale-vs-rings inner scale. So intersecting the mask argument here, at the top
 * of all three entry points, is the one change that makes a translate-only gizmo actually
 * translate-only — for every editor that renders a gizmo, at once.
 *
 * <p>The mask argument is rewritten before the method body assigns it to the field, and the
 * op/axis intersection itself happens in {@link CombinedGizmoMode#restrict}, which mirrors
 * 2.5.2's {@code Mode.shows(Op)} filter.
 */
@Mixin(Gizmo.class)
public abstract class GizmoMixin
{
    @ModifyVariable(
        method = {
            "render(Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleMask;)V",
            "captureVisual(Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleMask;)V",
            "renderStencil(Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleMask;)V"
        },
        at = @At("HEAD"),
        argsOnly = true
    )
    private Gizmo.HandleMask bbscg$restrictMask(Gizmo.HandleMask mask)
    {
        return CombinedGizmoMode.restrict(mask);
    }

    /**
     * Collapses the three two-axis plane handles to nothing when the user switched them off
     * for the single display modes.
     *
     * <p>The planes are the quads next to the axis bars that drag in two axes at once — but
     * they share the MOVE (or SCALE) op with the bars, so no mask the frame carries can drop
     * just them. They are, however, the 6th-to-8th {@code sink.box} calls in
     * {@code collectHandles} (after the three bars, the screen cube and the uniform-scale cube,
     * before the three scale cubes) — and that one walk feeds both the drawn gizmo and the pick
     * stencil. Shrinking their boxes to zero size (x2/y2/z2 onto x1/y1/z1) makes them rasterize
     * to no pixels and no stencil, so they vanish from the screen <em>and</em> from under the
     * cursor: hidden means ungrabbable, the way 2.7's own element settings hide things.</p>
     *
     * <p>{@code sink.box} stays private inside {@code Gizmo}, so the injection is addressed by
     * signatures only — no Java-level reference to the private types is needed, and the plane
     * boxes are told apart from the other eight calls purely by their ordinals in the compiled
     * method.</p>
     */
    @ModifyArgs(
        method = "collectHandles(Lmchorse/bbs_mod/ui/utils/Gizmo$Layout;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;box(Lmchorse/bbs_mod/ui/utils/Gizmo$Handle;FFFFFFI)V",
            ordinal = 5
        )
    )
    private void bbscg$hidePlaneXZ(Args args)
    {
        this.bbscg$collapsePlane(args);
    }

    @ModifyArgs(
        method = "collectHandles(Lmchorse/bbs_mod/ui/utils/Gizmo$Layout;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;box(Lmchorse/bbs_mod/ui/utils/Gizmo$Handle;FFFFFFI)V",
            ordinal = 6
        )
    )
    private void bbscg$hidePlaneXY(Args args)
    {
        this.bbscg$collapsePlane(args);
    }

    @ModifyArgs(
        method = "collectHandles(Lmchorse/bbs_mod/ui/utils/Gizmo$Layout;Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/utils/Gizmo$HandleSink;box(Lmchorse/bbs_mod/ui/utils/Gizmo$Handle;FFFFFFI)V",
            ordinal = 7
        )
    )
    private void bbscg$hidePlaneZY(Args args)
    {
        this.bbscg$collapsePlane(args);
    }

    /**
     * Puts the far corner of the box onto its near corner: a zero-size box covers no pixels
     * and writes no stencil, whichever of the two sinks is asking. The handle argument is
     * left as-is so a running drag (which filters down to the active handle anyway) is not
     * disturbed beyond losing the plane's visuals.
     */
    private void bbscg$collapsePlane(Args args)
    {
        if (!CombinedGizmoMode.hidesPlanes())
        {
            return;
        }

        args.set(4, args.get(1));
        args.set(5, args.get(2));
        args.set(6, args.get(3));
    }
}
