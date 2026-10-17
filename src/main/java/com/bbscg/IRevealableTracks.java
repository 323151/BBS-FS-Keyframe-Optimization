package com.bbscg;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;

/**
 * Public bridge for the reveal-limb-tracks feature.
 *
 * <p>The revealed-row bookkeeping lives on the dope sheet itself — only its rows get rebuilt, so
 * there it survives track rebuilds, the same deal as the fold state. But the trigger comes from
 * outside: a limb clicked in the viewport lands in {@code UIReplaysEditorUtils.pickForm}, which
 * has no handle on the dope sheet's internals. Adding this interface to
 * {@code UIKeyframeDopeSheet} via its mixin lets that hook reach the bookkeeping across the class
 * boundary.</p>
 */
public interface IRevealableTracks
{
    /** Mark this row (and every row above it in the fold tree) revealed, refreshing the layout. */
    void bbscg$revealTrack(UIKeyframeSheet sheet);
}
