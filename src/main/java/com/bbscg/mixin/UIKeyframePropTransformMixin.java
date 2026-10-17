package com.bbscg.mixin;

import com.bbscg.IKeyframeTimeline;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframePropTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Adds {@link IKeyframeTimeline} to every keyframe transform panel, so code living further up the
 * class tree (the {@code UITransform} mixin) can ask any of them for its timeline despite
 * {@code getKeyframes} being protected.
 */
@Mixin(UIKeyframePropTransform.class)
public abstract class UIKeyframePropTransformMixin implements IKeyframeTimeline
{
    @Shadow
    protected abstract UIKeyframes getKeyframes();

    @Override
    public UIKeyframes bbscg$getTimeline()
    {
        return this.getKeyframes();
    }
}
