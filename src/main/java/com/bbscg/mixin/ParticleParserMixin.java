package com.bbscg.mixin;

import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.particles.ParticleParser;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Extends BBS's Bedrock particle parser with the components and top-level tables that
 * Snowstorm can export but stock BBS 2.7 ignores: {@code emitter_rate_manual},
 * {@code emitter_lifetime_events}, {@code particle_lifetime_events}, and the top-level
 * {@code particle_effect.events} table.
 *
 * <p>The component registration is unconditional (a registered component that's absent from
 * a file simply never parses, and registration is what lets the parser map those classes back
 * to their JSON keys when saving). Events parsing only happens while the
 * {@code snowstorm_particles} setting is on; parsed events are always written back so an
 * imported file never silently loses its event definitions.</p>
 */
@Mixin(ParticleParser.class)
public abstract class ParticleParserMixin
{
    @Shadow
    public Map<String, Class<? extends ParticleComponentBase>> components;

    /* Register the extra components into the parser's registry */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbscgRegisterExtraComponents(CallbackInfo ci)
    {
        SnowstormCompat.registerComponents((ParticleParser) (Object) this);
    }

    /* Parse the top-level particle_effect.events table once an effect finished parsing */
    @Inject(
        method = "fromData(Lmchorse/bbs_mod/particles/ParticleScheme;Lmchorse/bbs_mod/data/types/MapType;)Lmchorse/bbs_mod/particles/ParticleScheme;",
        at = @At("RETURN")
    )
    private void bbscgParseEvents(ParticleScheme scheme, MapType data, CallbackInfoReturnable<ParticleScheme> cir)
    {
        if (SnowstormCompat.isEnabled())
        {
            SnowstormCompat.parseEvents(scheme, data);
        }
    }

    /* Write the parsed events table back when the scheme is serialized (save, dupe, editor) */
    @Inject(method = "toData(Lmchorse/bbs_mod/particles/ParticleScheme;)Lmchorse/bbs_mod/data/types/MapType;", at = @At("RETURN"))
    private void bbscgWriteEvents(ParticleScheme scheme, CallbackInfoReturnable<MapType> cir)
    {
        SnowstormCompat.writeEvents(cir.getReturnValue(), scheme);
        /* BBS writes every component as "minecraft:" + key; keep blockbuster:* keys clean. */
        SnowstormCompat.normalizeBlockbusterKeys(cir.getReturnValue());
    }
}
