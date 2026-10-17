package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewrites the film timeline's answer to "which tick should an edit land on".
 *
 * <p>BBS 2.7 routes every editor-panel and gizmo value edit through
 * {@code UIKeyframes#getAutoKeyframeTick}: while it answers with the playhead, the edit is
 * redirected onto a keyframe created there ({@code IUIKeyframeGraph#applyValue} for the numeric
 * panels, {@code UIReplaysEditorUtils#forEachSelectedKeyframe} for the pose and transform panels
 * — their reset buttons included); while it answers {@code null}, the very same code fans the
 * edit out over <b>every selected keyframe</b> of every track holding that kind of value. The
 * fan-out machinery is stock, so both directions below only have to steer this one answer.</p>
 *
 * <p>Two settings, one handler, a fixed order:</p>
 *
 * <ul>
 *   <li><b>Batch edits last</b> — the "edit boxed keyframes together" setting with at least two
 *   keyframes sitting in the rows' selections forces the answer to {@code null} no matter what
 *   came before: box-selecting a group and then editing in the panel, dragging the gizmo, or
 *   hitting reset applies to every boxed keyframe of the matching tracks, instead of quietly
 *   landing on a fresh keyframe at the playhead. One selected keyframe keeps the stock answer,
 *   so the playhead redirect and the auto-limb workflow below are untouched.</li>
 *   <li><b>Auto limb keyframes first</b> — with that setting on and the timeline's selected
 *   keyframe sitting on a model track (a whole-pose or a per-limb one), the playhead is reported
 *   as the auto-key tick even with the global {@code BBSSettings.autoKeyframe} toggle off, which
 *   is what makes limb drags key at the playhead in the replay editor. Since it only ever flips
 *   a {@code null} answer, the batch rule above can still override it for a group — the order
 *   inside the single handler makes that certain.</li>
 * </ul>
 */
@Mixin(UIFilmKeyframes.class)
public abstract class UIFilmKeyframesMixin
{
    @Shadow
    public IUIClipsDelegate editor;

    @Shadow
    public abstract float getOffset();

    @Inject(
        method = "getAutoKeyframeTick()Ljava/lang/Float;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void bbscg$autoKeyTick(CallbackInfoReturnable<Float> cir)
    {
        /* The auto-limb rule first: it only ever flips a null answer (stock's own tick stands),
         * so nothing it does can be "more stock" than the batch rule below. */
        this.bbscg$forceLimbAutoKey(cir);

        /* Batch group edits last, so they always win: with the whole selection holding two or
         * more keyframes, the answer is forced back to null even while auto-keyframing wants
         * the playhead — every consumer then fans the edit out over the selection instead of
         * landing it on a fresh keyframe there. A single keyframe keeps whatever answer the
         * rules above produced, so the playhead redirect and the auto-limb workflow are
         * untouched when nothing is box-selected. */
        if (cir.getReturnValue() != null
            && CombinedGizmoMode.isBatchEdit()
            && this.bbscg$countSelected() >= 2)
        {
            cir.setReturnValue(null);
        }
    }

    private void bbscg$forceLimbAutoKey(CallbackInfoReturnable<Float> cir)
    {
        /* Stock already answers with a tick (the built-in toggle is on) or has no timeline to
         * key on (no editor): nothing to add in either case. */
        if (cir.getReturnValue() != null || !CombinedGizmoMode.isAutoKeyframe() || this.editor == null)
        {
            return;
        }

        IUIKeyframeGraph graph = ((UIFilmKeyframes) (Object) this).getGraph();
        Keyframe selected = graph == null ? null : graph.getSelected();
        UIKeyframeSheet sheet = selected == null ? null : graph.getSheet(selected);

        /* Only model work is keyed at the playhead: a whole-pose or per-limb keyframe being
         * the selection is what "editing model keyframes" looks like from the timeline's point
         * of view. Every other factory leaves the stock answer (null) in place. */
        if (sheet != null
            && (sheet.channel.getFactory() == KeyframeFactories.POSE
            || sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM))
        {
            cir.setReturnValue(this.getOffset());
        }
    }

    /**
     * How many keyframes sit in the rows' selections right now — the marquee group after a
     * box-select, a plain single pick after a click. {@code getSelected()} hands out a shared
     * buffer, so each row is only asked for its size before moving on.
     */
    private int bbscg$countSelected()
    {
        IUIKeyframeGraph graph = ((UIFilmKeyframes) (Object) this).getGraph();
        int count = 0;

        if (graph != null)
        {
            for (UIKeyframeSheet sheet : graph.getSheets())
            {
                count += sheet.selection.getSelected().size();
            }
        }

        return count;
    }
}
