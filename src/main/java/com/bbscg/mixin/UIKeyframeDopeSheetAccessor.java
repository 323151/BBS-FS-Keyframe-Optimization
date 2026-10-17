package com.bbscg.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read access to the dope sheet's row-Y cache, for the summary-track feature.
 *
 * <p>{@code updateScrollSize} only caches rows that are on screen, so {@code containsKey} is the
 * exact "is this row visible (not folded away)" predicate the summary dot picker and the column
 * selection need — {@code getDopeSheetY} cannot serve (it returns a baseline value for missing
 * rows). Used from {@code UIKeyframesMixin} on the {@code UIKeyframes} instance's own dope sheet
 * via a cast, which is safe because the accessor is merged into every
 * {@link UIKeyframeDopeSheet}.</p>
 */
@Mixin(UIKeyframeDopeSheet.class)
public interface UIKeyframeDopeSheetAccessor
{
    @Accessor("sheetYCache")
    Map<UIKeyframeSheet, Integer> bbscg$getSheetYCache();
}
