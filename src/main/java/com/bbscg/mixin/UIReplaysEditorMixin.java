package com.bbscg.mixin;

import com.bbscg.RevealState;
import com.bbscg.SummarySheet;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The summary rows' birthplace.
 *
 * <p>{@code updateChannelsList} builds the track list, filters it to the active tab and the
 * user's disabled tracks, prunes the parent/child tree, and only then hands the sheets to the
 * dope sheet row by row. Redirecting the prune is the ideal insertion point: every filter has
 * already run (a summary appears exactly when its pose row does — never on a tab without pose
 * tracks, never for a disabled model), and the sheet list is still editable before the
 * add-sheet loop walks it. One {@link SummarySheet} goes immediately above every whole-pose
 * row, so the timeline reads:</p>
 *
 * <pre>[model A summary] [model A pose] [model A bones...] [model B summary] [model B pose] ...</pre>
 *
 * <p>The rows the catalog built stay untouched; the inserted row keeps the pose row's place in
 * the fold tree (same parent, so whatever folds the pose row's group folds the summary too),
 * and carries an empty mirror channel that never receives keyframes — every create and paste
 * that could land on it is redirected to the pose row (see the other mixins).</p>
 *
 * <p>Rows are inserted whether the setting is on or not; {@code UIKeyframeDopeSheetMixin}
 * filters them out of the row layout while it is off, so flipping the setting shows or hides
 * them on the spot, with no rebuild needed.</p>
 */
@Mixin(UIReplaysEditor.class)
public abstract class UIReplaysEditorMixin
{
    @Redirect(
        method = "updateChannelsList()V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/film/replays/UIReplaysEditorUtils;pruneTree(Ljava/util/List;)V"
        )
    )
    private void bbscg$addSummaryRows(List<UIKeyframeSheet> sheets)
    {
        UIReplaysEditorUtils.pruneTree(sheets);

        for (int i = 0; i < sheets.size(); i++)
        {
            UIKeyframeSheet sheet = sheets.get(i);

            if (sheet.getPosedForm() != null && !(sheet instanceof SummarySheet))
            {
                sheets.add(i, new SummarySheet(sheet));
                i++;
            }
        }
    }

    /**
     * Reveal-limb-tracks: keep the session's current replay id in step, so the revealed-row
     * state ({@link RevealState}) is bucketed by replay. Undo and redo re-set the same replay —
     * same id, so its revealed limb rows ride out the editor rebuild those trigger — while
     * picking another replay starts from a clean slate instead of inheriting limbs revealed for
     * a different one. Every path that swaps the replay funnels through this exact overload
     * (the single-argument one just forwards), and nested calls re-enter with the same replay,
     * so the assignment is harmless there too.
     */
    @Inject(
        method = "setReplay(Lmchorse/bbs_mod/film/replays/Replay;ZLmchorse/bbs_mod/ui/film/replays/UIReplaysEditor$OrbitReaction;)V",
        at = @At("HEAD")
    )
    private void bbscg$trackCurrentReplay(Replay replay, boolean select, UIReplaysEditor.OrbitReaction orbit, CallbackInfo ci)
    {
        RevealState.currentReplayId = replay == null ? "" : replay.getId();
    }

    /**
     * Leaving a film forgets every replay's fold state (stock clears its map two lines into
     * setFilm) — the revealed-row state gets the same reset. Whatever replay setFilm itself
     * goes on to select re-registers its id with the injection above.
     */
    @Inject(
        method = "setFilm(Lmchorse/bbs_mod/film/Film;)V",
        at = @At("HEAD")
    )
    private void bbscg$forgetRevealState(Film film, CallbackInfo ci)
    {
        RevealState.clear();
    }
}
