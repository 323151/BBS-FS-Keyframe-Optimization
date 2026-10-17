package com.bbscg;

import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;

import java.util.EnumSet;

/**
 * The gizmo display mode state this addon restores from BBS 2.5.2.
 *
 * <p>BBS 2.7 made the gizmo a single always-combined widget: every handle (move, scale, rotate)
 * is shown at once, and the old {@code Gizmo.Mode} — translate-only, scale-only, rotate-only,
 * combined — is gone, together with the T hotkey that toggled the combined one. This class holds
 * that mode again, on the addon's side:
 *
 * <ul>
 *   <li>{@link #restrict(Gizmo.HandleMask)} intersects the frame's handle mask with the active
 *       mode, so the gizmo only draws, only stencils and only accepts the handles of the mode —
 *       exactly the {@code Mode.shows(Op)} filter 2.5.2's renderer applied.</li>
 *   <li>{@link #toggleCombined()} is the old T key: combined off remembers the mode left behind,
 *       combined on returns to it.</li>
 *   <li>{@link #switchDisplayMode(TransformOp)} is the old first-press behaviour of G/S/R:
 *       when the displayed handles don't match the pressed operation's mode yet, the press only
 *       switches what the gizmo shows; the second press starts the operation.</li>
 * </ul>
 *
 * <p>The default mode is COMBINED, which leaves 2.7's stock behaviour untouched until the user
 * actually switches, and the state is deliberately not persisted (2.5.2 kept it on the Gizmo
 * singleton for the session too).
 */
public class CombinedGizmoMode
{
    /** The restored display modes, mirroring 2.5.2's {@code Gizmo.Mode}. */
    public static enum Mode
    {
        TRANSLATE, SCALE, ROTATE, COMBINED;

        /** The 2.5.2 {@code Mode.shows(Op)} filter. */
        public boolean shows(Gizmo.Op op)
        {
            switch (this)
            {
                case TRANSLATE:
                    return op == Gizmo.Op.MOVE || op == Gizmo.Op.SCREEN;
                case SCALE:
                    return op == Gizmo.Op.SCALE || op == Gizmo.Op.SCALE_ALL;
                case ROTATE:
                    return op == Gizmo.Op.ROTATE || op == Gizmo.Op.VIEW || op == Gizmo.Op.TRACKBALL;
                case COMBINED:
                default:
                    return true;
            }
        }
    }

    /** Active display mode. COMBINED keeps stock 2.7 behaviour. */
    private static Mode mode = Mode.COMBINED;
    /** The mode to return to when combined mode is toggled off (2.5.2's previousMode). */
    private static Mode previousMode = Mode.TRANSLATE;

    /**
     * The "show two-axis planes in single modes" setting, filled in when the addon registers its
     * settings page with BBS. The plane quads sit next to the axis bars and drive the free
     * two-axis move (and, with move hidden, two-axis scale); a user who finds them in the way
     * in a single display mode can switch them off there. {@code null} until registered — and
     * on any side where BBS never fired the settings event — so readers fall back to showing.
     */
    public static ValueBoolean showPlanes;

    /**
     * The "auto limb keyframes in the replay editor" setting, filled in when the addon registers
     * its settings page with BBS. While it is on, clicking a model limb in the replay editor no
     * longer jumps the timeline to that limb's nearest keyframe, and dragging a limb's gizmo
     * keys the limb at the playhead instead of rewriting the selected keyframe. {@code null}
     * until registered, so readers fall back to stock behaviour.
     */
    public static ValueBoolean autoKeyframe;

    /**
     * The "hold a key to drag the timeline without tick snapping" setting, filled in when the
     * addon registers its settings page with BBS. While it is on, holding the bound key (left
     * Alt by default, rebindable in BBS's keybind settings) bypasses snap-to-ticks — the same
     * temporary bypass Shift already gives, but on its own key. {@code null} until registered,
     * so readers fall back to stock behaviour.
     */
    public static ValueBoolean fractionalScrub;

    /**
     * The "summary track above pose tracks" setting, filled in when the addon registers its
     * settings page with BBS. While it is on, the replay editor puts a Blender-style summary
     * row above every whole-pose track: the row aggregates the keyframes of the pose track and
     * of everything folded under it, and dragging one of its dots moves that whole column of
     * keyframes in one go. {@code null} until registered, so readers fall back to stock
     * behaviour.
     */
    public static ValueBoolean summaryTrack;

    /**
     * The "edit boxed keyframes together" setting, filled in when the addon registers its
     * settings page with BBS. While it is on, a marquee-selected group of keyframes (two or
     * more) makes every editor-panel and gizmo value edit — the pose panels' reset buttons
     * included — apply to the whole group instead of being redirected onto the playhead by
     * auto-keyframing; a single selected keyframe keeps stock behaviour. {@code null} until
     * registered, so readers fall back to stock behaviour.
     */
    public static ValueBoolean batchEdit;

    /**
     * The "reveal limb tracks on demand" setting, filled in when the addon registers its
     * settings page with BBS. While it is on, a pose track's limb rows start out hidden in
     * the dope sheet and the bone hierarchy is dropped from the display — limb rows flatten
     * out directly under the pose row, lining up in the order their limbs were picked; picking
     * a limb in the viewport reveals its row (reveals accumulate as more limbs are picked),
     * folding the pose row closed resets its limb rows to hidden, and shift-unfolding reveals
     * the whole branch at once. {@code null} until registered, so readers fall back to stock
     * behaviour.
     */
    public static ValueBoolean revealLimbs;

    /**
     * The "flip keyframe values" setting, filled in when the addon registers its settings page
     * with BBS. While it is on, the keyframe editor's right-click menu gains "Flip X/Y/Z axis"
     * actions: they mirror every selected limb/transform keyframe's value across the axis's
     * plane, Blockbench-style. {@code null} until registered, so readers fall back to stock
     * behaviour.
     */
    public static ValueBoolean keyframeFlip;

    /**
     * The "full Snowstorm particle support" setting, filled in when the addon registers its
     * settings page with BBS. While it is on, the particle system parses and simulates every
     * parameter Snowstorm can export: the {@code bezier}/{@code bezier_chain} curve types, the
     * {@code lookat_direction} and {@code emitter_transform_*} billboard facing modes, the
     * "custom" billboard direction mode, gradient interpolants spanning more than 0..1, the
     * emitter/particle event system (creation, expiration, timeline, travel distance and
     * collision events), and {@code emitter_rate_manual}. {@code null} until registered, so
     * readers fall back to stock behaviour.
     */
    public static ValueBoolean snowstormParticles;

    public static Mode getMode()
    {
        return mode;
    }

    /**
     * Set the display mode. Returns {@code true} iff the mode actually changed — callers use
     * this to tell a real switch from a no-op press on the already-active mode (2.5.2 setMode).
     */
    public static boolean setMode(Mode mode)
    {
        boolean same = CombinedGizmoMode.mode == mode;

        CombinedGizmoMode.mode = mode;

        return !same;
    }

    /**
     * The T key: toggle the combined mode. Entering it remembers the mode left behind so a
     * second press returns there (2.5.2 Gizmo#toggleCombined).
     */
    public static void toggleCombined()
    {
        if (CombinedGizmoMode.mode == Mode.COMBINED)
        {
            CombinedGizmoMode.setMode(CombinedGizmoMode.previousMode);

            return;
        }

        Mode previous = CombinedGizmoMode.mode;

        if (CombinedGizmoMode.setMode(Mode.COMBINED))
        {
            CombinedGizmoMode.previousMode = previous;
        }
    }

    /**
     * The G/S/R first-press behaviour (2.5.2 UIPropTransform#switchGizmoDisplayMode): when the
     * gizmo doesn't display the pressed operation's handles yet, the press only switches the
     * display. In combined mode there is nothing to switch, so the operation always starts.
     *
     * @return {@code true} when the press was consumed by the display switch
     */
    public static boolean switchDisplayMode(TransformOp op)
    {
        if (CombinedGizmoMode.mode == Mode.COMBINED)
        {
            return false;
        }

        Mode target = CombinedGizmoMode.modeOf(op);

        return CombinedGizmoMode.mode != target && CombinedGizmoMode.setMode(target);
    }

    private static Mode modeOf(TransformOp op)
    {
        if (op == TransformOp.TRANSLATE)
        {
            return Mode.TRANSLATE;
        }

        return op == TransformOp.SCALE ? Mode.SCALE : Mode.ROTATE;
    }

    /**
     * Intersect a frame's handle mask with the active display mode, so the gizmo draws,
     * stencils and accepts only the handles the mode shows. A {@code null} mask means "no
     * restriction" and is answered with the mode's own mask (or kept null in combined, where
     * the stock {@code mask == null ? ALL : mask} logic already does the right thing).
     */
    public static Gizmo.HandleMask restrict(Gizmo.HandleMask mask)
    {
        if (CombinedGizmoMode.mode == Mode.COMBINED)
        {
            return mask;
        }

        if (mask == null)
        {
            mask = Gizmo.HandleMask.ALL;
        }

        /* HandleMask's op/axis sets are private, so rebuild the intersection from the public
         * allows() queries: keep an op only when both the frame's target and the display mode
         * accept it; rotate axes are the target's own business (the mode can only drop rotate
         * wholesale, which the ops set already did). */
        EnumSet<Gizmo.Op> ops = EnumSet.noneOf(Gizmo.Op.class);

        for (Gizmo.Op op : Gizmo.Op.values())
        {
            if (mask.allows(op) && CombinedGizmoMode.mode.shows(op))
            {
                ops.add(op);
            }
        }

        EnumSet<Axis> rotateAxes = EnumSet.noneOf(Axis.class);

        for (Axis axis : Axis.values())
        {
            if (mask.allowsRotateAxis(axis))
            {
                rotateAxes.add(axis);
            }
        }

        return Gizmo.HandleMask.of(ops, rotateAxes);
    }

    /**
     * Whether the two-axis plane handles (the three quads next to the axis bars that drag in a
     * plane) must not reach the screen or the cursor right now. Only ever in a single display
     * mode — combined stays stock 2.7, where BBS's own "show movement/scale" settings own what
     * is on screen.
     */
    public static boolean hidesPlanes()
    {
        return CombinedGizmoMode.mode != Mode.COMBINED
            && CombinedGizmoMode.showPlanes != null
            && !CombinedGizmoMode.showPlanes.get();
    }

    /**
     * Whether the replay editor must run in "auto limb keyframe" mode right now: limb clicks
     * leave the timeline alone and limb edits key at the playhead. Falls back to stock
     * behaviour whenever the setting was never registered (or is off).
     */
    public static boolean isAutoKeyframe()
    {
        return CombinedGizmoMode.autoKeyframe != null && CombinedGizmoMode.autoKeyframe.get();
    }

    /**
     * Whether holding the bound bypass key must turn snap-to-ticks off right now. Falls back
     * to stock behaviour whenever the setting was never registered (or is off).
     */
    public static boolean isFractionalScrub()
    {
        return CombinedGizmoMode.fractionalScrub != null && CombinedGizmoMode.fractionalScrub.get();
    }

    /**
     * Whether value edits must apply to the whole marquee selection rather than to the
     * playhead right now. Falls back to stock behaviour whenever the setting was never
     * registered (or is off).
     */
    public static boolean isBatchEdit()
    {
        return CombinedGizmoMode.batchEdit != null && CombinedGizmoMode.batchEdit.get();
    }

    /**
     * Whether limb rows must start hidden until their limb is picked right now. Falls back
     * to stock behaviour whenever the setting was never registered (or is off).
     */
    public static boolean isRevealLimbs()
    {
        return CombinedGizmoMode.revealLimbs != null && CombinedGizmoMode.revealLimbs.get();
    }

    /**
     * Whether the replay editor must add summary rows above its whole-pose tracks right now.
     * Falls back to stock behaviour whenever the setting was never registered (or is off).
     */
    public static boolean isSummaryTrack()
    {
        return CombinedGizmoMode.summaryTrack != null && CombinedGizmoMode.summaryTrack.get();
    }

    /**
     * Whether the keyframe editor's right-click menu must offer the X/Y/Z value flip right now.
     * Falls back to stock behaviour whenever the setting was never registered (or is off).
     */
    public static boolean isKeyframeFlip()
    {
        return CombinedGizmoMode.keyframeFlip != null && CombinedGizmoMode.keyframeFlip.get();
    }

    /**
     * Whether the particle system must run in "full Snowstorm support" mode right now: the
     * extra curve types, billboard facing modes, gradient ranges, the event system and the
     * manual rate component all become active. Falls back to stock behaviour whenever the
     * setting was never registered (or is off).
     */
    public static boolean isSnowstormParticles()
    {
        return CombinedGizmoMode.snowstormParticles != null && CombinedGizmoMode.snowstormParticles.get();
    }
}
