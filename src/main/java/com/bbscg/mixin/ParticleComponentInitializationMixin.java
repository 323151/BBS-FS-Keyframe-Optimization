package com.bbscg.mixin;

import com.bbscg.particles.IParticleInitializationAccess;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentInitialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches {@code minecraft:emitter_initialization} the {@code particle_update_expression}
 * field. Stock 2.7 only parses {@code creation_expression} and {@code per_update_expression};
 * Blockbuster (which these effects are authored against) additionally runs
 * {@code particle_update_expression} once per particle — at creation (before the shape
 * component reads the variables), on every tick, and on every render frame. Effects like
 * lightning bolts compute their whole geometry (shape offsets, sizes) inside that block,
 * so without it every variable stays zero and the effect collapses into a dot.
 *
 * <p>Parsing happens unconditionally (so a parsed scheme round-trips through
 * {@code toData}), but only ever executes while the {@code snowstorm_particles} setting
 * is on — see {@code ParticleEmitterMixin}'s injection into {@code setParticleVariables}.</p>
 */
@Mixin(ParticleComponentInitialization.class)
public abstract class ParticleComponentInitializationMixin implements IParticleInitializationAccess
{
    @Unique
    private MolangExpression bbscgParticleUpdate;

    @Override
    public MolangExpression bbscgGetParticleUpdate()
    {
        return this.bbscgParticleUpdate;
    }

    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbscgParseParticleUpdate(BaseType data, MolangParser parser, CallbackInfoReturnable<ParticleComponentBase> ci) throws MolangException
    {
        if (data.isMap())
        {
            MapType map = data.asMap();

            if (map.has("particle_update_expression"))
            {
                this.bbscgParticleUpdate = parser.parseGlobalData(map.get("particle_update_expression"));
            }
        }
    }

    @Inject(method = "toData(Lmchorse/bbs_mod/data/types/MapType;)V", at = @At("TAIL"))
    private void bbscgWriteParticleUpdate(MapType data, CallbackInfo ci)
    {
        if (this.bbscgParticleUpdate != null && !MolangExpression.isZero(this.bbscgParticleUpdate))
        {
            data.put("particle_update_expression", this.bbscgParticleUpdate.toData());
        }
    }
}
