package com.bbscg;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;

/**
 * Public bridge onto {@code UIKeyframePropTransform#getKeyframes()}.
 *
 * <p>{@code getKeyframes} is protected and declared on a subclass of {@code UITransform}, so a mixin
 * hooked into {@code UITransform} itself (where the stock {@code reset()} lives) cannot reach it
 * through a cast — an ancestor cannot touch a descendant's protected members. Adding this interface
 * to {@code UIKeyframePropTransform} via its own mixin gives every keyframe transform panel a
 * public way to hand out its timeline.</p>
 */
public interface IKeyframeTimeline
{
    UIKeyframes bbscg$getTimeline();
}
