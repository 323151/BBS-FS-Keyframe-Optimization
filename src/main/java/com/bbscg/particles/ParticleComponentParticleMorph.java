package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;

import java.util.Map;

/**
 * {@code blockbuster:particle_morph}: turn the particle into a full morph once it collides.
 *
 * <p>Every one of the 120 Snowstorm reference effects ships this component with
 * {@code "enabled": false}, so no rendering is implemented — that would require wiring into
 * BBS's own morph rendering, which no shipped effect asks for. What matters is that the
 * component <b>round-trips</b>: stock BBS 2.7's component registry doesn't know the
 * {@code blockbuster:} prefix, so the raw JSON is silently dropped on import and an
 * editor save would lose it. This class stores the whole component map verbatim and writes
 * it back untouched, and deliberately implements no behaviour interface so it never enters
 * any of the scheme's runtime component lists.</p>
 */
public class ParticleComponentParticleMorph extends ParticleComponentBase
{
    /** The component's JSON exactly as imported (deep copied); {@code null} when absent. */
    private MapType raw;

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        if (data.isMap())
        {
            MapType map = data.asMap();

            this.raw = new MapType();

            for (Map.Entry<String, BaseType> entry : map)
            {
                this.raw.put(entry.getKey(), entry.getValue().copy());
            }
        }

        return super.fromData(data, parser);
    }

    @Override
    protected void toData(MapType data)
    {
        if (this.raw != null)
        {
            for (Map.Entry<String, BaseType> entry : this.raw)
            {
                data.put(entry.getKey(), entry.getValue().copy());
            }
        }
        else
        {
            data.putBool("enabled", false);
        }
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
