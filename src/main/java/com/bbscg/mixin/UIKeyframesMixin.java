package com.bbscg.mixin;

import com.bbscg.CombinedGizmoKeys;
import com.bbscg.CombinedGizmoMode;
import com.bbscg.SummarySheet;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.KeyframeType;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Three features live here, on the shared keyframe editor {@link UIKeyframes}:
 *
 * <ul>
 *   <li><b>Fractional timeline dragging.</b> The scrub (playhead drag in the ruler) refuses to
 *   start while Alt or Ctrl is held — {@code subMouseClicked} checks both in its scrub condition
 *   before anything else. Two ordinal-0 redirects let the bound bypass key through that
 *   condition, and the ordinal-1 Alt redirect does the same for the tracks' own Alt branch
 *   (column duplicate) — holding the key, a click in any row falls through to the regular
 *   pick-and-drag, and the drag keeps fractional ticks because {@code UITimelineCanvasMixin}
 *   lifts the snap gate itself. Every other Alt/Ctrl use in the method (create, Alt-copy
 *   modes) keeps its stock meaning, and with the setting off the handlers return the real key
 *   state, leaving 2.7 untouched.</li>
 *   <li><b>Summary rows.</b> A summary row has no keyframes of its own, so the stock dot picker
 *   cannot grab its dots. The injection at the plain-click keyframe pick ({@code
 *   pickOrStartSelectingKeyframes}'s call site) replaces that pick when the click lands on a
 *   summary dot: it selects the whole column of keyframes (each into its own track's selection)
 *   and leaves the stock drag state primed — dragging then moves the column together through the
 *   dope sheet's own multi-track drag, and releasing submits one undo per track, exactly like a
 *   hand-marqueed column. Injecting at that call site means stock has already established the
 *   plain-click-in-graph context (zoom stopped, drag anchors set, modifiers filtered), so the
 *   handler only decides summary-or-not.</li>
 *   <li><b>Value flip (Blockbench-style).</b> A second context handler, registered at the end of
 *   the constructor ({@code context()} <i>appends</i> to the element's handler list), adds three
 *   "Flip X/Y/Z axis" actions to the keyframes' right-click menu whenever the selection holds at
 *   least one {@link Transform}-valued keyframe — limb tracks' pose transforms and form transform
 *   tracks alike. Flipping mirrors the keyframe's value across the axis's plane: the translation
 *   flips its axis component, the rotation keeps its axis euler angle and negates the other two
 *   (quaternions get the matching component-wise treatment), and the scale is untouched — the
 *   exact convention BBS's own {@code Transform#mirrorX} documents, extended to Y and Z. Each
 *   flipped channel is wrapped in {@code pre/postNotify(FLAG_UNMERGEABLE)}, the same discrete-edit
 *   undo wrapping an interpolation pick gets, so one undo step reverts the whole flip.</li>
 * </ul>
 *
 * <p>Pasting onto a summary row is redirected to the pose row it summarizes, the same
 * destination its label-click and Ctrl-click creations take (see
 * {@code UIKeyframeDopeSheetMixin}).</p>
 */
@Mixin(UIKeyframes.class)
public abstract class UIKeyframesMixin extends UITimelineCanvas
{
    @Shadow
    private int dragging;

    @Shadow
    private Pair<Keyframe, KeyframeType> draggingData;

    @Shadow
    private float originalT;

    @Shadow
    private Object originalV;

    @Shadow
    private IUIKeyframeGraph currentGraph;

    @Shadow
    public abstract void cacheKeyframes();

    /* Fractional scrub: let the bound bypass key start the playhead drag. */

    private boolean bbscg$bypassHeld()
    {
        return CombinedGizmoMode.isFractionalScrub() && CombinedGizmoKeys.snapBypass().isHeld();
    }

    /**
     * The scrub condition's Alt check — the first of the two isAltPressed reads in
     * subMouseClicked; the later one on the graph-area branch keeps its stock column-duplicate
     * meaning.
     */
    @Redirect(
        method = "subMouseClicked(Lmchorse/bbs_mod/ui/framework/UIContext;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/graphics/window/Window;isAltPressed()Z",
            ordinal = 0
        )
    )
    private boolean bbscg$altDuringScrub()
    {
        return Window.isAltPressed() && !this.bbscg$bypassHeld();
    }

    /** The scrub condition's Ctrl check, symmetric with the Alt one above. */
    @Redirect(
        method = "subMouseClicked(Lmchorse/bbs_mod/ui/framework/UIContext;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/graphics/window/Window;isCtrlPressed()Z",
            ordinal = 0
        )
    )
    private boolean bbscg$ctrlDuringScrub()
    {
        return Window.isCtrlPressed() && !this.bbscg$bypassHeld();
    }

    /**
     * The graph-area branch's Alt check — the second isAltPressed read in subMouseClicked.
     * Stock gives Alt+click in the tracks the column-duplicate meaning, which swallows the
     * click before the regular pick-and-drag path can take it; while the bypass key is
     * held it falls through to that pick instead, so keyframes can be dragged off the tick
     * grid from any row — dragKeyframes consults the very snap gate the scrub already
     * bypasses. With the setting off (or a bypass key that isn't Alt), the stock
     * column-duplicate stays exactly as it was.
     */
    @Redirect(
        method = "subMouseClicked(Lmchorse/bbs_mod/ui/framework/UIContext;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/graphics/window/Window;isAltPressed()Z",
            ordinal = 1
        )
    )
    private boolean bbscg$altDuringColumn()
    {
        return Window.isAltPressed() && !this.bbscg$bypassHeld();
    }

    /* Summary rows: pick the whole column under a summary dot. */

    /**
     * The nearest summary dot within the stock 5 px pick radius, or NaN when the click is not on
     * any dot. Only keyframes of rows that are on screen take part, mirroring which dots the
     * renderer draws.
     */
    private float bbscg$findSummaryDotTick(SummarySheet summary, int mouseX, Map<UIKeyframeSheet, Integer> yCache)
    {
        /* Boxes, because the lambda below writes them; the finder only reads them. */
        final double[] best = {25D};
        final float[] bestTick = {Float.NaN};

        summary.forEachBelow((row, keyframe) ->
        {
            if (!yCache.containsKey(row))
            {
                return;
            }

            int x = this.toGraphX(keyframe.getTick());
            double distance = Math.pow(x - mouseX, 2D);

            if (distance < best[0])
            {
                best[0] = distance;
                bestTick[0] = keyframe.getTick();
            }
        });

        return bestTick[0];
    }

    @Inject(
        method = "subMouseClicked(Lmchorse/bbs_mod/ui/framework/UIContext;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframes;pickOrStartSelectingKeyframes(Lmchorse/bbs_mod/ui/framework/UIContext;)V"
        ),
        cancellable = true
    )
    private void bbscg$pickSummaryColumn(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        /* Summary rows only exist in the dope sheet, never in the single-track graph. */
        if (!CombinedGizmoMode.isSummaryTrack() || !(this.currentGraph instanceof UIKeyframeDopeSheet))
        {
            return;
        }

        if (!(this.currentGraph.getSheet(context.mouseY) instanceof SummarySheet summary))
        {
            return;
        }

        IUIKeyframeGraph graph = this.currentGraph;
        Map<UIKeyframeSheet, Integer> yCache = ((UIKeyframeDopeSheetAccessor) graph).bbscg$getSheetYCache();
        float tick = this.bbscg$findSummaryDotTick(summary, context.mouseX, yCache);

        if (Float.isNaN(tick))
        {
            return;
        }

        /* Stock keeps the whole selection when a plain click lands on an already selected
         * keyframe — a marqueeed group survives into the drag. Match that here: clear only
         * when the clicked column isn't part of the selection yet, so a box-selected group
         * stays put and drags as one when one of its dots is clicked. */
        final boolean[] columnSelected = {false};

        summary.forEachBelow((row, keyframe) ->
        {
            if (keyframe.getTick() == tick && yCache.containsKey(row) && row.selection.has(keyframe))
            {
                columnSelected[0] = true;
            }
        });

        if (!Window.isShiftPressed() && !columnSelected[0])
        {
            graph.clearSelection();
        }

        summary.forEachBelow((row, keyframe) ->
        {
            if (keyframe.getTick() == tick && yCache.containsKey(row))
            {
                row.selection.add(keyframe);
            }
        });

        /* The anchor is whatever the graph considers its primary selected keyframe — the same
         * one setTick's default diff math reads, so the drag below the pointer and the moved
         * column cannot disagree. */
        Keyframe anchor = graph.getSelected();

        if (anchor == null)
        {
            return;
        }

        graph.pickKeyframe(anchor);
        this.dragging = 0;
        this.draggingData = new Pair<>(anchor, KeyframeType.REGULAR);
        this.cacheKeyframes();

        this.originalT = anchor.getTick();
        this.originalV = anchor.getFactory().copy(anchor.getValue());

        cir.setReturnValue(true);
    }

    /* Summary rows: paste lands on the pose row, never on the summary's empty mirror channel. */

    /**
     * Pasting with a single track copied (the plain Ctrl-V case) targets the row under the
     * cursor; on a summary row that would paste into its empty channel. Redirect the row lookup
     * to the pose row so the paste lands where the dots' keyframes actually live. The
     * many-tracks branch matches copied track ids and can never match a summary id, so this one
     * redirect covers every paste that could touch a summary.
     */
    @Redirect(
        method = "pasteKeyframes(Ljava/util/Map;FIZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/IUIKeyframeGraph;getSheet(I)Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;"
        )
    )
    private UIKeyframeSheet bbscg$pasteOntoPoseRow(IUIKeyframeGraph graph, int mouseY)
    {
        UIKeyframeSheet sheet = graph.getSheet(mouseY);

        return sheet instanceof SummarySheet ? ((SummarySheet) sheet).poseSheet : sheet;
    }

    /* Value flip: mirror boxed limb keyframes across the X/Y/Z axis plane. */

    /**
     * Register the flip's own context handler. {@code context()} appends to the element's handler
     * list, so the stock menu is filled in first and these three actions land right after it —
     * shown only while the setting is on and the current selection actually holds a
     * {@link Transform}-valued keyframe, the values a flip can act on.
     */
    @Inject(
        method = "<init>(Ljava/util/function/Consumer;)V",
        at = @At("RETURN")
    )
    private void bbscg$registerFlipMenu(Consumer<Keyframe> callback, CallbackInfo ci)
    {
        this.context((menu) ->
        {
            if (!CombinedGizmoMode.isKeyframeFlip() || !this.bbscg$hasFlippableSelection())
            {
                return;
            }

            menu.action(Icons.FLIP_HORIZONTAL, L10n.lang("bbscg.ui.keyframes.flip_x", "Flip X axis", null), () -> this.bbscg$flipSelectedTransforms(0));
            menu.action(Icons.FLIP_VERTICAL, L10n.lang("bbscg.ui.keyframes.flip_y", "Flip Y axis", null), () -> this.bbscg$flipSelectedTransforms(1));
            menu.action(Icons.GLOBE, L10n.lang("bbscg.ui.keyframes.flip_z", "Flip Z axis", null), () -> this.bbscg$flipSelectedTransforms(2));
        });
    }

    /** Whether any selected keyframe, on any sheet, carries a {@link Transform} value. */
    @Unique
    private boolean bbscg$hasFlippableSelection()
    {
        for (UIKeyframeSheet sheet : this.currentGraph.getSheets())
        {
            for (Keyframe keyframe : sheet.selection.getSelected())
            {
                if (keyframe.getValue() instanceof Transform)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Mirror every selected {@link Transform}-valued keyframe across the given axis's plane
     * (0 = X, 1 = Y, 2 = Z). Each touched channel's edits are wrapped in a
     * {@code pre/postNotify(FLAG_UNMERGEABLE)} pair — the wrapping a discrete pick like an
     * interpolation change gets — so a whole flip across every track is one undo step.
     */
    @Unique
    private void bbscg$flipSelectedTransforms(int axis)
    {
        for (UIKeyframeSheet sheet : this.currentGraph.getSheets())
        {
            if (!sheet.selection.hasAny())
            {
                continue;
            }

            sheet.channel.preNotify(IValueListener.FLAG_UNMERGEABLE);

            for (Keyframe keyframe : sheet.selection.getSelected())
            {
                if (!(keyframe.getValue() instanceof Transform transform))
                {
                    continue;
                }

                /* Copy first, then mirror the copy: copy() keeps the PoseTransform subclass limb
                 * tracks use, and the keyframe's previous value instance is never touched. */
                Transform copy = transform.copy();

                this.bbscg$mirror(copy, axis);
                keyframe.setValue(copy, false);
            }

            sheet.channel.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }

        this.currentGraph.pickSelected();
    }

    /**
     * The mirror itself, in the convention {@code Transform#mirrorX} documents for X — the
     * translation flips its axis component, the rotation keeps its axis euler angle and negates
     * the other two, the scale is untouched — carried over to Y and Z, quaternion mode included.
     */
    @Unique
    private void bbscg$mirror(Transform transform, int axis)
    {
        if (axis == 0)
        {
            transform.mirrorX();

            return;
        }

        if (axis == 1)
        {
            transform.translate.y = -transform.translate.y;

            if (transform.rotationMode == Transform.RotationMode.QUATERNION)
            {
                transform.quat.set(-transform.quat.x, transform.quat.y, -transform.quat.z, transform.quat.w);
            }
            else
            {
                transform.rotate.mul(-1F, 1F, -1F);
            }
        }
        else
        {
            transform.translate.z = -transform.translate.z;

            if (transform.rotationMode == Transform.RotationMode.QUATERNION)
            {
                transform.quat.set(-transform.quat.x, -transform.quat.y, transform.quat.z, transform.quat.w);
            }
            else
            {
                transform.rotate.mul(-1F, -1F, 1F);
            }
        }
    }
}
