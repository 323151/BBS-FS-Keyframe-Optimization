package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import com.bbscg.IKeyframeTimeline;
import mchorse.bbs_mod.ui.framework.elements.input.UITransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Group reset for the transform panels that inherit the stock {@code UITransform#reset()}.
 *
 * <p>The whole-pose keyframe popup overrides {@code reset()} with an absolute write, but the panels
 * for a single limb keyframe (and the plain transform property keyframe) do not — they inherit
 * {@code UITransform#reset()}, which routes the zeroing through the delta machinery: the delta is
 * measured against the primary (the panel's own keyframe) and <b>added</b> to every selected
 * keyframe. With a box-selected group that means only the primary lands on zero; the rest are
 * nudged by the same amount and stay un-reset.</p>
 *
 * <p>This mixin runs after that stock delta pass and overwrites it with the absolute reset the
 * group actually asked for: every selected keyframe on the timeline whose value is a
 * {@link Transform} (per-limb rows, transform property rows) gets translate zeroed, scale set to
 * one and rotation reset. Only keyframe panels are touched — the {@link IKeyframeTimeline} check
 * (added to {@code UIKeyframePropTransform} by its own mixin) keeps every other transform editor in
 * BBS (model editor, form panels) on stock behaviour — and only while the batch-edit setting is on
 * with a real group (two or more keyframes) sitting in the rows' selections.</p>
 */
@Mixin(UITransform.class)
public abstract class UITransformMixin
{
    @Inject(
        method = "reset()V",
        at = @At("TAIL")
    )
    private void bbscg$resetBoxedKeyframes(CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isBatchEdit() || !(this instanceof IKeyframeTimeline timeline))
        {
            return;
        }

        UIKeyframes keyframes = timeline.bbscg$getTimeline();
        IUIKeyframeGraph graph = keyframes == null ? null : keyframes.getGraph();

        if (graph == null || this.bbscg$countSelected(graph) < 2)
        {
            return;
        }

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            /* getSelected() hands out a shared buffer, so each row's list is consumed
             * before the next row is asked for its own. */
            for (Keyframe selected : sheet.selection.getSelected())
            {
                if (!(selected.getValue() instanceof Transform transform))
                {
                    continue;
                }

                selected.preNotify();
                transform.translate.set(0F, 0F, 0F);
                transform.scale.set(1F, 1F, 1F);
                transform.resetRotation();
                selected.postNotify();
            }
        }
    }

    /** How many keyframes sit in the rows' selections right now — the marquee group after a box-select. */
    private int bbscg$countSelected(IUIKeyframeGraph graph)
    {
        int count = 0;

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            count += sheet.selection.getSelected().size();
        }

        return count;
    }
}
