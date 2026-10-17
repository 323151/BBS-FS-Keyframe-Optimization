package com.bbscg;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Session state for the reveal-limb-tracks setting: which limb rows the user has brought in,
 * per replay.
 *
 * <p>The revealed-row bookkeeping used to live on the dope sheet instance, but every replay
 * re-set — undo and redo included — rebuilds the whole keyframe editor, and the rebuilt sheet
 * would start with an empty set, which read as "undo hides every limb again". The state has to
 * outlive those rebuilds, so it lives here, bucketed by replay id the same way the stock fold
 * state is ({@code UIReplaysEditor.expandedTracksByReplay}): undo returns to the same replay
 * id and keeps its reveals, while switching to another replay starts from a clean slate
 * instead of inheriting limbs revealed for a different one. A plain class rather than mixin
 * state because two mixins on different target classes need to reach it — the replay editor
 * sets the current replay ({@code UIReplaysEditorMixin}), the dope sheet reads and writes the
 * rows ({@code UIKeyframeDopeSheetMixin}).</p>
 *
 * <p>A linked hash set per replay because it iterates in click order, which is the order the
 * flattened limb rows line up in.</p>
 */
public class RevealState
{
    /** The replay whose timeline is on screen — the bucket key for {@link #revealed()}. */
    public static String currentReplayId = "";

    private static final Map<String, Set<String>> REVEALED = new HashMap<>();

    /** The current replay's revealed limb row ids, in click order. */
    public static Set<String> revealed()
    {
        return REVEALED.computeIfAbsent(RevealState.currentReplayId, (id) -> new LinkedHashSet<>());
    }

    /** Forget every replay's reveals — what leaving a film is (the fold state resets there too). */
    public static void clear()
    {
        REVEALED.clear();
    }
}
