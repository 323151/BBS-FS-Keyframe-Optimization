package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the pose keyframe popup's reset cover the whole boxed group, not just the pose rows.
 *
 * <p>Stock reset from this panel fans out through
 * {@code UIReplaysEditorUtils.forEachSelectedKeyframe}, which only visits rows whose factory
 * matches the panel keyframe's — a whole-pose (POSE) keyframe. Per-limb rows (POSE_TRANSFORM) in
 * the same marquee group are skipped, so after a box-select some of the boxed keyframes quietly
 * stayed un-reset. This mixin appends the limb rows to the reset: after the stock reset has done
 * the pose rows, every selected keyframe on every POSE_TRANSFORM row is zeroed the same way
 * (translate to 0, scale to 1, rotation reset). It is an absolute write, so it is idempotent and
 * needs none of the pose panel's bone-picking or mirror bookkeeping — a limb keyframe's whole
 * value is one bone's transform.</p>
 *
 * <p>Only runs while the batch-edit setting is on and the rows' selections actually hold a group
 * (two or more keyframes); a plain single pick keeps stock behaviour.</p>
 */
@Mixin(targets = "mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory$UIPoseTransforms")
public abstract class UIPoseTransformsMixin
{
    @Shadow
    protected abstract UIKeyframes getKeyframes();

    @Inject(
        method = "reset()V",
        at = @At("TAIL")
    )
    private void bbscg$resetLimbKeyframesToo(CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isBatchEdit())
        {
            return;
        }

        UIKeyframes keyframes = this.getKeyframes();
        IUIKeyframeGraph graph = keyframes == null ? null : keyframes.getGraph();

        if (graph == null || this.bbscg$countSelected(graph) < 2)
        {
            return;
        }

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            if (sheet.channel.getFactory() != KeyframeFactories.POSE_TRANSFORM)
            {
                continue;
            }

            /* getSelected() hands out a shared buffer, so each row's list is consumed
             * before the next row is asked for its own. */
            for (Keyframe selected : sheet.selection.getSelected())
            {
                PoseTransform poseT = (PoseTransform) selected.getValue();

                selected.preNotify();
                poseT.translate.set(0F, 0F, 0F);
                poseT.scale.set(1F, 1F, 1F);
                poseT.resetRotation();
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
