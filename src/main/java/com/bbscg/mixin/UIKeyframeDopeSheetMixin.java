package com.bbscg.mixin;

import com.bbscg.CombinedGizmoMode;
import com.bbscg.IRevealableTracks;
import com.bbscg.RevealState;
import com.bbscg.SummarySheet;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The summary rows in the dope sheet itself:
 *
 * <ul>
 *   <li><b>Creation.</b> Both ways a user hand-creates a keyframe on a clicked row — Ctrl-click
 *   in the graph area ({@code addKeyframeAt}) and a click on the row's label
 *   ({@code clickSheets}) — end in {@code addKeyframeManually}. On a summary row that would
 *   insert into its empty mirror channel; the two redirects drop the keyframe onto the pose row
 *   it summarizes instead, so a click on the summary behaves like a click on the pose row
 *   below.</li>
 *   <li><b>Dots.</b> {@code renderTopmostKeyframes} batches every "drawn on top" keyframe into
 *   one QUADS buffer. The injection rides that same batch — it re-derives the very same builder
 *   (a Tessellator singleton), matrix and clip the stock section dots use, then draws one
 *   6x6 square per distinct tick of every visible row at or below the summary's pose row, in
 *   the section rows' own plain-square style. A dot renders white while any keyframe it stands
 *   for is selected, its track colour otherwise — same reading as the section aggregation
 *   dots it sits next to.</li>
 *   <li><b>Visibility.</b> The setting is checked right inside the stock visibility test: while
 *   it is off, a summary row is treated like a folded-away one — kept out of the row layout,
 *   unrendered, unclickable. The frame the setting flips, the layout is rebuilt, so rows appear
 *   or vanish on the spot.</li>
 *   <li><b>Marquee.</b> Stock marquee selection only sees keyframes at their own row's height;
 *   the injection after {@code selectInArea} runs the box over the summary rows too, selecting
 *   every aggregated keyframe at the summary row's height — so a box drawn over summary dots
 *   selects their whole columns, ready to drag.</li>
 * </ul>
 */
@Mixin(UIKeyframeDopeSheet.class)
public abstract class UIKeyframeDopeSheetMixin implements IRevealableTracks
{
    /** Matches {@code UIKeyframeDopeSheet#CULL_MARGIN}: wider than any dot's radius. */
    private static final int BBCG_CULL_MARGIN = 20;

    @Shadow
    private UIKeyframes keyframes;

    @Shadow
    private List<UIKeyframeSheet> sheets;

    @Shadow
    private Map<UIKeyframeSheet, Integer> sheetYCache;

    @Shadow
    public abstract int getDopeSheetY(UIKeyframeSheet sheet);

    @Shadow
    public abstract int getTrackHeight(UIKeyframeSheet sheet);

    @Shadow
    private void updateScrollSize()
    {}

    @Shadow
    private boolean isUnfolded(UIKeyframeSheet sheet)
    {
        throw new AssertionError();
    }

    /** The setting's state as of the last frame — a flip means the row layout is stale. */
    @Unique
    private boolean bbscg$summaryShown;

    /** The reveal setting's state as of the last frame — a flip means the row layout is stale. */
    @Unique
    private boolean bbscg$revealShown;

    /**
     * The stock shape of the limb tree, remembered while the flatten pass runs: each limb row's
     * stock parent id (what re-hanging undoes) and every row id in first-seen order (what the
     * list reorder undoes). Keyed by id — the rows themselves are rebuilt as fresh objects — and
     * forgotten wholesale on {@code removeAllSheets}, because a rebuilt list is a fresh stock
     * tree the next flatten re-records.
     *
     * <p>No initializers, same Mixin rule as above; the lazy accessors do the
     * initializing.</p>
     */
    @Unique
    private Map<String, String> bbscg$stockParents;

    @Unique
    private Set<String> bbscg$stockOrder;

    /**
     * The current replay's revealed limb rows, in click order — session state that lives outside
     * the sheet ({@link RevealState}) so undo/redo rebuilding the whole editor keeps it, exactly
     * like the fold state the editor hands to each rebuild.
     */
    private Set<String> bbscg$revealed()
    {
        return RevealState.revealed();
    }

    private Map<String, String> bbscg$stockParents()
    {
        if (this.bbscg$stockParents == null)
        {
            this.bbscg$stockParents = new HashMap<>();
        }

        return this.bbscg$stockParents;
    }

    private Set<String> bbscg$stockOrder()
    {
        if (this.bbscg$stockOrder == null)
        {
            this.bbscg$stockOrder = new LinkedHashSet<>();
        }

        return this.bbscg$stockOrder;
    }

    /**
     * The initializer the mixin source cannot have. Runs at the end of the dope sheet's real
     * constructor — by then the addon's settings are loaded — so both booleans start at the
     * setting's current state instead of a default false that the first render would read as a
     * flip.
     */
    @Inject(
        method = "<init>(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframes;)V",
        at = @At("RETURN")
    )
    private void bbscg$initSettingsState(UIKeyframes keyframes, CallbackInfo ci)
    {
        this.bbscg$summaryShown = CombinedGizmoMode.isSummaryTrack();
        this.bbscg$revealShown = CombinedGizmoMode.isRevealLimbs();
    }

    private UIKeyframeSheet bbscg$poseTarget(UIKeyframeSheet sheet)
    {
        return sheet instanceof SummarySheet ? ((SummarySheet) sheet).poseSheet : sheet;
    }

    /** Ctrl-click in the graph area on a summary row keys the pose row beneath it. */
    @Redirect(
        method = "addKeyframeAt(FI)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeDopeSheet;addKeyframeManually(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"
        )
    )
    private Keyframe bbscg$createOnPoseFromGraph(UIKeyframeDopeSheet graph, UIKeyframeSheet sheet, float tick, Object value)
    {
        return graph.addKeyframeManually(this.bbscg$poseTarget(sheet), tick, value);
    }

    /** A label-column click on a summary row keys the pose row beneath it. */
    @Redirect(
        method = "clickSheets(Lmchorse/bbs_mod/ui/framework/UIContext;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeDopeSheet;addKeyframeManually(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"
        )
    )
    private Keyframe bbscg$createOnPoseFromLabel(UIKeyframeDopeSheet graph, UIKeyframeSheet sheet, float tick, Object value)
    {
        return graph.addKeyframeManually(this.bbscg$poseTarget(sheet), tick, value);
    }

    /**
     * Draw the summary rows' aggregated dots inside the stock "topmost keyframes" batch, right
     * before the section rows' own aggregation dots are drawn into the same buffer.
     */
    @Inject(
        method = "renderTopmostKeyframes(Lmchorse/bbs_mod/ui/framework/UIContext;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeDopeSheet;renderSectionKeyframes(Lmchorse/bbs_mod/ui/framework/UIContext;Lnet/minecraft/client/render/BufferBuilder;Lorg/joml/Matrix4f;Lmchorse/bbs_mod/ui/utils/Area;)V"
        )
    )
    private void bbscg$renderSummaryDots(UIContext context, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isSummaryTrack() || this.sheets.isEmpty())
        {
            return;
        }

        Area area = this.keyframes.graphArea;
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Matrix4f matrix = context.batcher.getContext().getMatrices().peek().getPositionMatrix();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (!(sheet instanceof SummarySheet summary) || !this.sheetYCache.containsKey(sheet))
            {
                continue;
            }

            int y = this.getDopeSheetY(sheet) + this.getTrackHeight(sheet) / 2;

            if (y + 3 < area.y || y - 3 > area.ey())
            {
                continue;
            }

            /* One dot per distinct tick; it counts as selected once any keyframe it stands for
             * is, so a dragged column reads as white from the summary row too. */
            TreeMap<Float, Boolean> dots = new TreeMap<>();

            summary.forEachBelow((row, keyframe) ->
            {
                if (UIKeyframeDopeSheetMixin.this.sheetYCache.containsKey(row))
                {
                    dots.put(keyframe.getTick(), dots.getOrDefault(keyframe.getTick(), false) || row.selection.has(keyframe));
                }
            });

            for (Map.Entry<Float, Boolean> dot : dots.entrySet())
            {
                int x = this.keyframes.toGraphX(dot.getKey());

                if (x < area.x - BBCG_CULL_MARGIN)
                {
                    continue;
                }

                if (x > area.ex() + BBCG_CULL_MARGIN)
                {
                    break;
                }

                int color = (dot.getValue() ? Colors.WHITE : summary.color) | Colors.A100;

                context.batcher.fillRect(builder, matrix, x - 3, y - 3, 6, 6, color, color, color, color);
            }
        }
    }

    /** Rebuild the row layout the frame the setting flips, so toggling shows/hides on the spot. */
    @Inject(
        method = "renderTopmostKeyframes(Lmchorse/bbs_mod/ui/framework/UIContext;)V",
        at = @At("HEAD")
    )
    private void bbscg$refreshSummaryVisibility(UIContext context, CallbackInfo ci)
    {
        boolean shown = CombinedGizmoMode.isSummaryTrack();

        if (shown != this.bbscg$summaryShown)
        {
            this.bbscg$summaryShown = shown;
            this.updateScrollSize();
        }

        boolean reveal = CombinedGizmoMode.isRevealLimbs();

        if (reveal != this.bbscg$revealShown)
        {
            this.bbscg$revealShown = reveal;
            this.bbscg$revealed().clear();

            /* Switching off un-flattens: the remembered stock shape goes back in before the
             * layout is recomputed. */
            if (!reveal)
            {
                this.bbscg$restoreStockTree();
            }

            this.updateScrollSize();
        }
    }

    /**
     * The setting's live on/off switch: while it is off a summary row fails the visibility
     * check, which keeps it out of the row layout everywhere at once — no space taken, not
     * rendered, not clickable — the same deal a folded-away row gets.
     */
    @Inject(
        method = "isVisible(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;)Z",
        at = @At("RETURN"), cancellable = true
    )
    private void bbscg$hideSummaryWhenOff(UIKeyframeSheet sheet, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ() && sheet instanceof SummarySheet && !CombinedGizmoMode.isSummaryTrack())
        {
            cir.setReturnValue(false);
        }
    }

    /**
     * Marquee selection reaches the summary dots: after the stock pass has selected what the
     * box covers row by row, run the box over every visible summary row too — matching each
     * aggregated keyframe at the summary row's own height, so a box drawn over summary dots
     * selects their whole columns, exactly like clicking a single dot does.
     */
    @Inject(
        method = "selectInArea(Lmchorse/bbs_mod/ui/utils/Area;)V",
        at = @At("RETURN")
    )
    private void bbscg$selectSummaryColumns(Area area, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isSummaryTrack())
        {
            return;
        }

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (!(sheet instanceof SummarySheet summary) || !this.sheetYCache.containsKey(sheet))
            {
                continue;
            }

            int y = this.getDopeSheetY(summary) + this.getTrackHeight(summary) / 2;

            summary.forEachBelow((row, keyframe) ->
            {
                if (UIKeyframeDopeSheetMixin.this.sheetYCache.containsKey(row)
                    && area.isInside(UIKeyframeDopeSheetMixin.this.keyframes.toGraphX(keyframe.getTick()), y))
                {
                    row.selection.add(keyframe);
                }
            });
        }
    }

    /**
     * The reveal-limb-tracks filter: while the setting is on, a limb row (a POSE_TRANSFORM row)
     * only passes the visibility test once it has been revealed — by clicking its limb in the
     * viewport, or by the shift-unfold that shows everything. Until then it gets the folded-away
     * row's deal: no space taken, not rendered, not clickable.
     */
    @Inject(
        method = "isVisible(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;)Z",
        at = @At("RETURN"), cancellable = true
    )
    private void bbscg$hideUnrevealedLimbs(UIKeyframeSheet sheet, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ()
            && CombinedGizmoMode.isRevealLimbs()
            && sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM
            && !this.bbscg$revealed().contains(sheet.id))
        {
            cir.setReturnValue(false);
        }
    }

    /**
     * Fold-toggle bookkeeping for reveal-limb-tracks. Shift-unfolding is the stock "show the
     * whole branch" gesture, so it reveals every row below as well — that is how the setting
     * keeps the stock shortcut of shift-clicking a pose track's toggle to show all of its limb
     * tracks. Folding a pose row closed is the reset: the limb rows it folds away go back to
     * hidden, so the next unfold starts from a clean slate. Folding a limb row of its own keeps
     * what was revealed below it (only the stock fold applies there).
     */
    @Inject(
        method = "toggleFold(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;Z)V",
        at = @At("HEAD")
    )
    private void bbscg$trackRevealedLimbsOnFold(UIKeyframeSheet sheet, boolean branch, CallbackInfo ci)
    {
        if (!CombinedGizmoMode.isRevealLimbs())
        {
            return;
        }

        boolean unfold = !this.isUnfolded(sheet);

        if (unfold && branch)
        {
            this.bbscg$setRevealedBelow(sheet, true);
        }
        else if (!unfold && sheet.channel.getFactory() == KeyframeFactories.POSE)
        {
            this.bbscg$setRevealedBelow(sheet, false);
        }
    }

    /** Reveal (or conceal) every row below this one — the reveal-set half of the fold bookkeeping. */
    private void bbscg$setRevealedBelow(UIKeyframeSheet sheet, boolean revealed)
    {
        for (UIKeyframeSheet child : sheet.children)
        {
            if (revealed)
            {
                this.bbscg$revealed().add(child.id);
            }
            else
            {
                this.bbscg$revealed().remove(child.id);
            }

            this.bbscg$setRevealedBelow(child, revealed);
        }
    }

    @Override
    public void bbscg$revealTrack(UIKeyframeSheet sheet)
    {
        /* The picked limb row plus every row above it in the fold tree, so a nested bone can
         * appear even while its parent bone row is still hidden. */
        for (UIKeyframeSheet row = sheet; row != null; row = row.parent)
        {
            this.bbscg$revealed().add(row.id);
        }

        this.updateScrollSize();
    }

    /**
     * A rebuilt sheet list is a fresh stock tree, so the remembered stock shape is stale — it is
     * forgotten here, at the one choke point every rebuild passes through, and the flatten pass
     * re-records it from the fresh rows as they arrive.
     */
    @Inject(
        method = "removeAllSheets()V",
        at = @At("HEAD")
    )
    private void bbscg$forgetStockShape(CallbackInfo ci)
    {
        this.bbscg$stockParents().clear();
        this.bbscg$stockOrder().clear();
    }

    /**
     * The flatten pass of the reveal-limb-tracks setting: while the setting is on, the limb rows
     * drop the model's bone hierarchy from the display. Every limb row is re-hung directly under
     * its form's pose row (no more limbs nested under limbs — depth one all around, no fold
     * arrows on limb rows, and folding the pose row still hides the whole form at once), and the
     * row list — which is the display order, the layout walks it linearly — is reordered so a
     * pose row is immediately followed by its limb rows: the revealed ones in the order their
     * limbs were clicked, then the still-hidden ones.
     *
     * <p>It rides the layout's own head, so every path that recomputes the layout — rows added
     * or removed, a fold toggled, a limb revealed — re-flattens first. And it is idempotent:
     * re-hanging a limb that already hangs off the pose row is skipped, and the reorder always
     * rebuilds the list from scratch.</p>
     */
    @Inject(
        method = "updateScrollSize()V",
        at = @At("HEAD")
    )
    private void bbscg$flattenLimbsBeforeLayout(CallbackInfo ci)
    {
        if (CombinedGizmoMode.isRevealLimbs())
        {
            this.bbscg$flattenLimbs();
        }
    }

    private void bbscg$flattenLimbs()
    {
        /* Phase 0 — remember the stock shape before touching it. A limb's stock parent is written
         * the first time its id is seen: rows fresh from a rebuild still hang where the catalog
         * put them (flattening moves a row's own parent link, never its children's), while a
         * re-run over an already flat tree must not overwrite the remembered shape. A null
         * recorded only mid-rebuild heals itself once the real parent arrives. */
        Map<String, String> parents = this.bbscg$stockParents();
        Set<String> order = this.bbscg$stockOrder();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.id != null)
            {
                order.add(sheet.id);
            }

            if (sheet.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM && sheet.id != null)
            {
                if (!parents.containsKey(sheet.id) || (parents.get(sheet.id) == null && sheet.parent != null))
                {
                    parents.put(sheet.id, sheet.parent == null ? null : sheet.parent.id);
                }
            }
        }

        /* Phase 1 — re-hang every limb row directly under its pose row. */
        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.channel.getFactory() != KeyframeFactories.POSE_TRANSFORM)
            {
                continue;
            }

            UIKeyframeSheet pose = this.bbscg$poseAncestor(sheet);

            if (pose == null || pose == sheet.parent)
            {
                continue;
            }

            sheet.parent.children.remove(sheet);
            sheet.parent = pose;
            pose.children.add(sheet);
        }

        /* Phase 2 — lay the list out flat: each pose row immediately followed by its limb rows,
         * revealed ones first, in click order (the revealed set iterates in click order);
         * everything else keeps its place, materials and their property rows included. */
        List<UIKeyframeSheet> ordered = new ArrayList<>();
        Set<UIKeyframeSheet> placed = new HashSet<>();
        Map<String, UIKeyframeSheet> limbById = new HashMap<>();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (!placed.add(sheet))
            {
                continue;
            }

            ordered.add(sheet);

            if (sheet.channel.getFactory() != KeyframeFactories.POSE)
            {
                continue;
            }

            limbById.clear();

            for (UIKeyframeSheet child : sheet.children)
            {
                if (child.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM && child.id != null)
                {
                    limbById.putIfAbsent(child.id, child);
                }
            }

            for (String id : this.bbscg$revealed())
            {
                UIKeyframeSheet limb = limbById.get(id);

                if (limb != null && placed.add(limb))
                {
                    ordered.add(limb);
                }
            }

            for (UIKeyframeSheet child : sheet.children)
            {
                if (child.channel.getFactory() == KeyframeFactories.POSE_TRANSFORM && placed.add(child))
                {
                    ordered.add(child);
                }
            }
        }

        if (ordered.size() == this.sheets.size())
        {
            this.sheets.clear();
            this.sheets.addAll(ordered);
        }
    }

    /** The nearest pose row above this one — the row every limb of that form flattens under. */
    private UIKeyframeSheet bbscg$poseAncestor(UIKeyframeSheet sheet)
    {
        for (UIKeyframeSheet row = sheet.parent; row != null; row = row.parent)
        {
            if (row.channel.getFactory() == KeyframeFactories.POSE)
            {
                return row;
            }
        }

        return null;
    }

    /**
     * The way back, for the moment the setting is switched off: re-hang every limb row under its
     * remembered stock parent and sort the list back into stock (catalog) order. Rows the
     * snapshot never saw (it is only ever empty when nothing was flattened) fall to the end in
     * their current order rather than corrupting the restore.
     */
    private void bbscg$restoreStockTree()
    {
        Map<String, String> parents = this.bbscg$stockParents();

        if (parents.isEmpty())
        {
            return;
        }

        Map<String, UIKeyframeSheet> byId = new HashMap<>();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.id != null)
            {
                byId.put(sheet.id, sheet);
            }
        }

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.channel.getFactory() != KeyframeFactories.POSE_TRANSFORM || sheet.id == null || !parents.containsKey(sheet.id))
            {
                continue;
            }

            String stockParentId = parents.get(sheet.id);
            UIKeyframeSheet stockParent = stockParentId == null ? null : byId.get(stockParentId);

            if (sheet.parent == stockParent)
            {
                continue;
            }

            if (sheet.parent != null)
            {
                sheet.parent.children.remove(sheet);
            }

            sheet.parent = stockParent;

            if (stockParent != null)
            {
                stockParent.children.add(sheet);
            }
        }

        Map<String, Integer> order = new HashMap<>();
        int index = 0;

        for (String id : this.bbscg$stockOrder())
        {
            order.put(id, index++);
        }

        if (!order.isEmpty())
        {
            this.sheets.sort(Comparator.comparingInt(s -> s.id == null ? Integer.MAX_VALUE : order.getOrDefault(s.id, Integer.MAX_VALUE)));
        }
    }
}
