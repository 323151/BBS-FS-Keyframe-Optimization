package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import com.bbscg.IRevealableTracks;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.film.ICursor;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the replay editor's timeline from jumping to the nearest keyframe when the user clicks
 * a model limb, while the addon's "auto limb keyframes" setting is on.
 *
 * <p>Clicking a limb in the viewport runs through {@code UIReplaysEditorUtils.pickForm}, which
 * has exactly two places that move the film cursor onto the keyframe it just found — the
 * whole-pose-sheet branch inside {@code pickForm} itself and the shared {@code pickProperty}
 * walk that resolves the limb's own track. Both calls go through {@link ICursor#setCursor(float)}.
 * Redirecting those two invocations is the whole change: the nearest keyframe is still selected
 * (so the limb's properties panel comes up and the gizmo attaches to the bone), the bone is
 * still highlighted in the pose editor, and only the timeline itself stays where the user
 * left it — the writing then happens at the playhead through the auto-keyframing layer forced
 * on by {@link UIFilmKeyframesMixin}.
 *
 * <p>The redirects are scoped to the replay editor by the cursor's own type — the film panel is
 * the only {@link ICursor} passed by {@code UIReplaysEditor}; the animation state editor hands
 * its form editor in instead, so its clicks keep stock behaviour. The stock jump survives
 * untouched whenever the addon setting is off.</p>
 */
@Mixin(UIReplaysEditorUtils.class)
public abstract class UIReplaysEditorUtilsMixin
{
    /**
     * pickForm's pose-sheet branch: the clicked limb's form has its whole-pose track selected,
     * and the closest keyframe on it was about to take over the timeline.
     */
    @Redirect(
        method = "pickForm(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor;Lmchorse/bbs_mod/ui/film/ICursor;Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/film/ICursor;setCursor(F)V"
        )
    )
    private static void bbscg$noSnapPoseSheet(ICursor cursor, float tick)
    {
        UIReplaysEditorUtilsMixin.bbscg$setCursor(cursor, tick);
    }

    /**
     * pickProperty's branch: the clicked limb resolved to its own per-limb track (or the pose
     * track fallback), and the closest keyframe there was about to take over the timeline.
     */
    @Redirect(
        method = "pickProperty(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor;Lmchorse/bbs_mod/ui/film/ICursor;Ljava/lang/String;Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/film/ICursor;setCursor(F)V"
        )
    )
    private static void bbscg$noSnapBoneTrack(ICursor cursor, float tick)
    {
        UIReplaysEditorUtilsMixin.bbscg$setCursor(cursor, tick);
    }

    /**
     * The cursor only moves when the feature is off, or when the caller is not the replay
     * editor's film panel (the animation state editor shares these utils).
     */
    private static void bbscg$setCursor(ICursor cursor, float tick)
    {
        if (CombinedGizmoMode.isAutoKeyframe() && cursor instanceof UIFilmPanel)
        {
            return;
        }

        cursor.setCursor(tick);
    }

    /**
     * World-pick group preservation, for the "edit boxed keyframes together" setting. Stock's
     * {@code forceSelectInSheet} wipes every row's selection before selecting the clicked
     * limb's nearest keyframe — the documented "world-pick must deterministically activate
     * exactly the clicked keyframe" behaviour, and the end of any marquee group that spans
     * rows (a summary-row column pick, a box over several tracks). While the setting is on
     * and the group is real (two or more keyframes selected overall), the pick joins the
     * group instead: the clicked keyframe is selected and the properties panel follows it,
     * but the rest of the group stays selected — so the edit that follows fans out over the
     * whole group rather than keying the playhead alone. With the setting off, or nothing
     * box-selected, the stock wipe stands.
     */
    @Inject(
        method = "forceSelectInSheet(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/IUIKeyframeGraph;Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;Lmchorse/bbs_mod/utils/keyframes/Keyframe;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void bbscg$keepGroupOnWorldPick(IUIKeyframeGraph graph, UIKeyframeSheet sheet, Keyframe keyframe, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isBatchEdit() || !bbscg$countSelected(graph))
        {
            return;
        }

        sheet.selection.add(keyframe);
        graph.pickKeyframe(keyframe);

        ci.cancel();
    }

    /** Whether the rows' selections hold a real group — two or more keyframes overall. */
    private static boolean bbscg$countSelected(IUIKeyframeGraph graph)
    {
        int count = 0;

        for (UIKeyframeSheet row : graph.getSheets())
        {
            count += row.selection.getSelected().size();
        }

        return count >= 2;
    }

    /**
     * Reveal-limb-tracks: the world pick is the one funnel every limb click passes through —
     * either editor's viewport pick, insert mode, the pose-sheet-active branch, the ctrl
     * multi-select branch, all of them — so it is the one place the reveal has to hook. That
     * is why the injection sits at every return of {@code pickForm} rather than its tail:
     * most branches (the pose-sheet one included) leave through an early return the tail
     * never sees. A click that names a bone marks that bone's limb row — and every row above
     * it in the fold tree — revealed in the dope sheet; the row layout refresh makes it show
     * up on the spot, and the reveals accumulate click by click. Guarded down to limb rows
     * only: the bone must resolve to a real POSE_TRANSFORM row, so picks of whole forms or
     * bone-less things reveal nothing.
     */
    @Inject(
        method = "pickForm(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor;Lmchorse/bbs_mod/ui/film/ICursor;Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;Z)V",
        at = @At("RETURN")
    )
    private static void bbscg$revealPickedLimb(UIKeyframeEditor keyframeEditor, ICursor cursor, Form form, String bone, boolean insert, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isRevealLimbs()
            || keyframeEditor == null || form == null
            || bone == null || bone.isEmpty()
            || !(keyframeEditor.view.getDopeSheet() instanceof IRevealableTracks tracks))
        {
            return;
        }

        /* The same address stock resolves the limb's own row by — the form's path plus the bone
         * name — matched exactly first, then ignoring case (the stencil may say "head" while
         * the row id reads "pose.bones.Head"). */
        String boneKey = TrackId.bone(FormUtils.getPath(form), bone).toKey();
        UIKeyframeDopeSheet dope = keyframeEditor.view.getDopeSheet();
        UIKeyframeSheet sheet = dope.getSheet(boneKey);

        if (sheet == null)
        {
            for (UIKeyframeSheet row : dope.getSheets())
            {
                if (row.id != null && row.id.equalsIgnoreCase(boneKey))
                {
                    sheet = row;
                    break;
                }
            }
        }

        if (sheet != null && sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM)
        {
            tracks.bbscg$revealTrack(sheet);
        }
    }
}
