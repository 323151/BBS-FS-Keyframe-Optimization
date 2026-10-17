package com.bbscg;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * A Blender-style summary row for one form's pose subtree, added by the replay editor while
 * the "summary track" setting is on.
 *
 * <p>The row sits directly above the whole-pose row ({@code pose}) it summarizes, and shows
 * one dot for every tick where any visible track at or below that pose row has a keyframe.
 * Clicking a dot selects that whole column of keyframes (each into its own track's selection),
 * so the stock drag machinery moves them together and submits one undo across all of them —
 * exactly what dragging a summary keyframe does in Blender's action editor.</p>
 *
 * <p>The row's own channel is deliberately empty and never receives keyframes: every create
 * or paste that would land on it is redirected to the pose row below (see
 * {@code UIKeyframeDopeSheetMixin} and {@code UIKeyframesMixin}). An empty channel keeps it
 * invisible to the stock identity-based {@code getSheet(Keyframe)} lookups, so the pose row's
 * own selection and editing keep working untouched.</p>
 */
public class SummarySheet extends UIKeyframeSheet
{
    /** The whole-pose row this one summarizes — the keyframes at or below it are this row's dots. */
    public final UIKeyframeSheet poseSheet;

    public SummarySheet(UIKeyframeSheet poseSheet)
    {
        super(poseSheet.id + "_summary",
            L10n.lang("bbscg.ui.keys.summary_track", "Summary", null),
            poseSheet.color,
            new KeyframeChannel(poseSheet.id + "_summary", KeyframeFactories.POSE),
            poseSheet.property);

        this.poseSheet = poseSheet;
        this.form = poseSheet.form;

        /* Same slot in the tree as the pose row (the one right below this): folding whatever
         * group the pose row sits in hides this row too, while folding the pose row itself —
         * which hides the limb rows, not the pose row — keeps it visible, the way a summary
         * stays put when the channels under it fold away. */
        if (poseSheet.parent != null)
        {
            this.setParent(poseSheet.parent);
        }
    }

    /**
     * Walk every keyframe this row aggregates: the pose track's own first, then everything
     * folded under it, depth first. Callers decide which of those rows are on screen right
     * now — a keyframe on a folded-away row is not part of the summary while it is hidden.
     */
    public void forEachBelow(BiConsumer<UIKeyframeSheet, Keyframe> consumer)
    {
        SummarySheet.walk(this.poseSheet, consumer);
    }

    private static void walk(UIKeyframeSheet sheet, BiConsumer<UIKeyframeSheet, Keyframe> consumer)
    {
        for (Keyframe keyframe : (List<Keyframe>) sheet.channel.getKeyframes())
        {
            consumer.accept(sheet, keyframe);
        }

        for (UIKeyframeSheet child : sheet.children)
        {
            SummarySheet.walk(child, consumer);
        }
    }
}
