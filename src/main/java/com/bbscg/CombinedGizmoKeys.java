package com.bbscg;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import org.lwjgl.glfw.GLFW;

/**
 * The addon's own key combos.
 *
 * <p>Created when BBS posts {@code RegisterKeybindsEvent} — before the keybind settings file
 * is built — so each combo is registered into the keybind settings screen (the user can
 * rebind it like any BBS key) and the shared instance is what the client mixins read live.
 *
 * <p>Lives behind accessors rather than static fields so the {@code RegisterKeybindsEvent}
 * timing (L10n loaded, before settings) is the only moment a combo is built, and a missing
 * registration degrades to a locally-built combo instead of a crash.
 */
public class CombinedGizmoKeys
{
    public static final String MOD_ID = "bbscombinedgizmo";

    private static KeyCombo combined;
    private static KeyCombo snapBypass;

    private CombinedGizmoKeys()
    {}

    /** The combined-mode toggle combo, building a fallback on first use if the event never ran. */
    public static KeyCombo combined()
    {
        if (CombinedGizmoKeys.combined == null)
        {
            CombinedGizmoKeys.combined = new KeyCombo(MOD_ID + ":combined", L10n.lang("bbscg.ui.keys.combined", "Toggle combined gizmo mode", null), GLFW.GLFW_KEY_T).categoryKey("transformations");
        }

        return CombinedGizmoKeys.combined;
    }

    /**
     * The hold-to-bypass-snap combo: while the "fractional timeline dragging" setting is on,
     * holding this key makes timeline drags (the playhead and keyframes alike) keep their
     * fractional time instead of snapping to whole ticks.
     *
     * <p>Left Alt by default, and either side of the keyboard counts — {@link KeyCombo} maps
     * the Alt keys onto the left/right-agnostic check. A rebind in the keybind settings moves
     * the bypass onto whatever key the user picks.
     */
    public static KeyCombo snapBypass()
    {
        if (CombinedGizmoKeys.snapBypass == null)
        {
            CombinedGizmoKeys.snapBypass = new KeyCombo(MOD_ID + ":snap_bypass", L10n.lang("bbscg.ui.keys.snap_bypass", "Hold to drag the timeline off the tick grid", null), GLFW.GLFW_KEY_LEFT_ALT).categoryKey("keyframes");
        }

        return CombinedGizmoKeys.snapBypass;
    }
}
