package com.bbscg;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.BBSApi;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterSettingsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * The common half of the addon — registered under the {@code bbs-addon} entry point.
 *
 * <p>{@link RegisterSourcePacksEvent} is posted by BBS while it loads the {@code bbs-addon}
 * entry points, before any of the client ones — so making this addon's assets addressable as
 * {@code bbscombinedgizmo:...} links has to happen here and not in {@link CombinedGizmoClientAddon}.</p>
 */
public class CombinedGizmoAddon implements BBSAddonMod
{
    @Subscribe
    public void onSourcePacks(RegisterSourcePacksEvent event)
    {
        /* Before anything else: a mismatch here reads as "this addon does not fit this BBS
         * build" rather than as a crash on the first thing the user does. */
        BBSApi.requireVersion(CombinedGizmoKeys.MOD_ID, 1);

        /* Makes this addon's own assets addressable as bbscombinedgizmo:... links. */
        event.registerAddon(CombinedGizmoKeys.MOD_ID, CombinedGizmoAddon.class);
    }

    /**
     * Puts this addon's settings on BBS's own settings screen, persisted with BBS's other
     * settings in {@code config/bbs/settings/bbscombinedgizmo.json}.
     *
     * <p>One category means one page in BBS's settings screen: every setting of this addon
     * sits on it, in registration order. The boolean values themselves are kept in
     * {@link CombinedGizmoMode} for the client mixins to read live — a change takes effect on
     * the next frame.</p>
     */
    @Subscribe
    public void onSettings(RegisterSettingsEvent event)
    {
        event.register(Icons.KEY, CombinedGizmoKeys.MOD_ID, (builder) ->
        {
            builder.category("general", Icons.KEY);

            CombinedGizmoMode.showPlanes = builder.getBoolean("show_planes", true);
            CombinedGizmoMode.fractionalScrub = builder.getBoolean("fractional_scrub", false);
            CombinedGizmoMode.autoKeyframe = builder.getBoolean("auto_keyframe", false);
            CombinedGizmoMode.summaryTrack = builder.getBoolean("summary_track", false);
            CombinedGizmoMode.keyframeFlip = builder.getBoolean("keyframe_flip", true);
            CombinedGizmoMode.batchEdit = builder.getBoolean("batch_edit", true);
            CombinedGizmoMode.revealLimbs = builder.getBoolean("reveal_limb_tracks", false);
            /* "_v2" on purpose: builds before the full-Snowstorm work shipped this key with a
             * false default, and BBS persists the whole settings file on first launch — so an
             * existing config would keep overriding the new true default forever. The fresh
             * key sidesteps that stale false and starts enabled. */
            CombinedGizmoMode.snowstormParticles = builder.getBoolean("snowstorm_particles_v2", true);

            System.out.println("[BBS-CG] Settings registered: snowstorm_particles_v2 = " + CombinedGizmoMode.isSnowstormParticles());
        });
    }
}
