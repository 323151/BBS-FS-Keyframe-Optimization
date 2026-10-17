package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import com.bbscg.IRevealableTracks;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.MobFormRenderer;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.mob.MobRig;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseTransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.pose.UIBoneList;
import mchorse.bbs_mod.ui.utils.pose.PoseBones;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The reveal-limb-tracks setting's picker entry point, for limbs the viewport can't reach.
 *
 * <p>Revealing a limb's row normally means clicking the limb itself in the viewport — but a
 * limb whose parent is folded away, scaled flat or simply awkward to hit has no clickable
 * pixel to offer. The pose keyframes' panel already solves this with its full bone list; the
 * limb keyframes' panel (the fields that come up when a limb keyframe is selected) gets the
 * same reach through this mixin: a button at the top of the panel toggles the very same bone
 * list the pose panel shows ({@link UIBoneList} — search box, mirror/invert toggles,
 * hierarchy tree, multi-select), built from the same sources the pose panel reads: the model
 * instance's hierarchy for model forms, the rig (or the renderer's flat bone list) for mob
 * forms. Selecting bones there reveals their rows exactly like viewport clicks would (the
 * same id resolution, the same {@link IRevealableTracks#bbscg$revealTrack} funnel), so the
 * rows appear in their places among the flattened limb rows, in pick order.</p>
 *
 * <p>The form and the editor are captured when the panel is built (a panel lives and dies with
 * its keyframe, and its editor instance never changes under it), while the dope sheet is
 * looked up fresh at every selection — undo and rebuilds swap the whole graph, and the picks
 * should always talk to the one on screen.</p>
 *
 * <p>Only the panel's own {@code boneVisible} toggle is shadowed — this Mixin version looks
 * {@code @Shadow} fields up in the target class alone, not its superclasses, so the inherited
 * {@code editor} and {@code scroll} are reached through the constructor argument (captured
 * in the button lambda) and the toggle's container instead.</p>
 */
@Mixin(UIPoseTransformKeyframeFactory.class)
public abstract class UIPoseTransformKeyframeFactoryMixin
{
    @Shadow
    public UIToggle boneVisible;

    /**
     * The bone list currently unfolded under the button — null while folded. No initializer:
     * Mixin drops those, and null is the folded default anyway.
     */
    @Unique
    private UIElement bbscg$limbArea;

    @Inject(
        method = "<init>(Lmchorse/bbs_mod/utils/keyframes/Keyframe;Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframes;)V",
        at = @At("RETURN")
    )
    private void bbscg$addLimbPickerButton(Keyframe keyframe, UIKeyframes editor, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isRevealLimbs() || !(editor.getGraph() instanceof UIKeyframeDopeSheet))
        {
            return;
        }

        UIKeyframeSheet sheet = editor.getGraph().getSheet(keyframe);
        Form form = sheet == null ? null : UIReplaysEditor.getSheetForm(sheet);

        if (!(form instanceof ModelForm) && !(form instanceof MobForm))
        {
            return;
        }

        /* Right above the panel's own fields — under the tick/duration header row, before the
         * bone-visible toggle, which is the first field the stock constructor adds (and whose
         * container is the panel's scroll). */
        UIButton button = new UIButton(L10n.lang("bbscg.ui.reveal_limb_picker", "Reveal a limb track…", null), (b) ->
        {
            this.bbscg$toggleLimbArea(b, editor, form);
        });

        button.tooltip(L10n.lang("bbscg.ui.reveal_limb_picker_tooltip", "Pick a bone to bring its track in — for limbs the viewport can't click", null));

        UIElement scroll = this.boneVisible.getParent();

        if (scroll != null)
        {
            scroll.addBefore(this.boneVisible, button);
        }
    }

    /**
     * Unfold or fold the bone list under the button. Unfolding builds the same bone list the pose
     * keyframes' panel shows ({@link UIBoneList}) over the panel's form; folding just removes it.
     */
    private void bbscg$toggleLimbArea(UIButton button, UIKeyframes editor, Form form)
    {
        UIElement scroll = this.boneVisible.getParent();

        if (scroll == null)
        {
            return;
        }

        if (this.bbscg$limbArea != null)
        {
            scroll.remove(this.bbscg$limbArea);
            this.bbscg$limbArea = null;
            button.label = L10n.lang("bbscg.ui.reveal_limb_picker", "Reveal a limb track…", null);

            return;
        }

        /* The same height the pose keyframes' panel gives its list — the panel is a popup, and a
         * taller list would push its fields off screen. */
        UIBoneList bones = new UIBoneList((selected) ->
        {
            if (editor.getGraph() instanceof UIKeyframeDopeSheet dope)
            {
                for (String bone : selected)
                {
                    this.bbscg$revealLimb(dope, form, bone);
                }
            }
        });

        if (!this.bbscg$fillBoneList(bones, form))
        {
            return;
        }

        bones.filter(true);
        /* The same deal the pose panels give their list: the height is only the minimum, the
         * expand chain lets the list eat whatever room the panel has to spare — without it the
         * list sits frozen at four rows no matter how tall the popup is. */
        bones.list.h(UIStringList.DEFAULT_HEIGHT * 8 - 8).expand();
        bones.expand();

        this.bbscg$limbArea = bones;
        scroll.addBefore(this.boneVisible, bones);
        button.label = L10n.lang("bbscg.ui.reveal_limb_picker_close", "Close the bone list", null);
    }

    /**
     * Fill the bone list from the panel's form — the same sources and the same filtering the pose
     * keyframes' panel's {@code fillGroups} uses. Returns whether anything was filled (a form
     * with no model behind it yields an empty list and no area at all).
     */
    private boolean bbscg$fillBoneList(UIBoneList bones, Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            ModelInstance model = ((ModelFormRenderer) FormUtilsClient.getRenderer(modelForm)).getModel();

            if (model == null)
            {
                return false;
            }

            /* Hierarchy metadata so the list renders as a tree, then the source list itself —
             * both skipping the model's disabled bones exactly like the pose panel does. */
            bones.list.setHierarchy(model.model, (bone) -> PoseBones.isHidden(model.getDisabledBones(), bone));

            List<String> list = new ArrayList<>(model.model.getGroupKeysInHierarchyOrder());

            list.removeIf((bone) -> PoseBones.isHidden(model.getDisabledBones(), bone));
            bones.setSource(list, false);
        }
        else if (form instanceof MobForm mobForm)
        {
            MobRig rig = MobFormRenderer.getRig(mobForm);

            if (rig != null)
            {
                bones.list.setHierarchy(rig, null);
                bones.setSource(rig.getGroupKeysInHierarchyOrder(), false);
            }
            else
            {
                bones.list.setHierarchy(null, null);
                bones.setSource(FormUtilsClient.getRenderer(mobForm).getBones(), true);
            }
        }
        else
        {
            return false;
        }

        return bones.hasBones();
    }

    /**
     * Resolve a picked bone name to its limb row and reveal it — the same address the viewport
     * pick resolves by (the form's path plus the bone name), matched exactly first, then
     * ignoring case (the stencil may say "head" while the row id reads "pose.bones.Head").
     * {@code getSheet(String)} only walks the rows on screen, so the case-insensitive sweep
     * over the full row list is what finds the not-yet-revealed ones.
     */
    private void bbscg$revealLimb(UIKeyframeDopeSheet dope, Form form, String bone)
    {
        if (bone == null || bone.isEmpty() || !(dope instanceof IRevealableTracks tracks))
        {
            return;
        }

        String boneKey = TrackId.bone(FormUtils.getPath(form), bone).toKey();
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
