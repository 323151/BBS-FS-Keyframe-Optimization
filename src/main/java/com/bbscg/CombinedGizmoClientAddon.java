package com.bbscg;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.RegisterKeybindsEvent;
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
import mchorse.bbs_mod.resources.Link;

import java.util.Collections;

/**
 * The client half of the addon — registered under the {@code bbs-client-addon} entry point.
 *
 * <p>Separate from {@link CombinedGizmoAddon} for the same reason the official template splits
 * its halves: these events live in BBS's client source set, and BBS only hands them to the
 * entry points it reads after the client one.</p>
 */
public class CombinedGizmoClientAddon implements BBSAddonMod
{
    @Subscribe
    public void onL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> Collections.singletonList(Link.create(CombinedGizmoKeys.MOD_ID + ":strings/" + lang + ".json")));
    }

    @Subscribe
    public void onKeybinds(RegisterKeybindsEvent event)
    {
        event.register(CombinedGizmoKeys.combined());
        event.register(CombinedGizmoKeys.snapBypass());
    }
}
