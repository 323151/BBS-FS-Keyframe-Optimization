package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.IComponentEmitterInitialize;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;

/**
 * The {@code minecraft:emitter_rate_manual} component — a particle rate mode where particles
 * are only spawned by external triggers, with a cap of {@code max_particles} active at once.
 *
 * <p>Stock BBS 2.7 doesn't register this component at all, so Snowstorm files using the
 * "Manual" rate mode lose their rate settings on import. Registering this component keeps the
 * file parsing (and round-tripping through the editor) intact. There is no gameplay-side
 * manual spawn trigger in BBS, so the emitter simply spawns nothing on its own — which is
 * exactly what manual mode means.</p>
 */
public class ParticleComponentRateManual extends ParticleComponentBase implements IComponentEmitterInitialize
{
    public MolangExpression maxParticles;

    @Override
    public int getSortingIndex()
    {
        return -900;
    }

    @Override
    protected void toData(MapType data)
    {
        if (this.maxParticles != null)
        {
            data.put("max_particles", this.maxParticles.toData());
        }
    }

    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (!data.isMap())
        {
            return super.fromData(data, parser);
        }

        MapType map = data.asMap();

        if (map.has("max_particles"))
        {
            this.maxParticles = parser.parseDataSilently(map.get("max_particles"));
        }

        return super.fromData(map, parser);
    }

    /* Manual rate emitters don't self-spawn; nothing to initialize. */
    @Override
    public void apply(ParticleEmitter emitter)
    {}
}
