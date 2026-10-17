package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.IComponentParticleRender;
import mchorse.bbs_mod.particles.components.appearance.colors.Tint;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import org.joml.Matrix4f;

/**
 * {@code blockbuster:particle_collision_tinting}: a second tint that takes over the particle's
 * color after it has collided with something.
 *
 * <p>Ported from Blockbuster's {@code BedrockComponentCollisionTinting}: it reuses the regular
 * tinting's color model (solid / gradient) and simply swaps which one gets computed. BBS's own
 * {@code ParticleComponentAppearanceTinting} keeps its {@code color} field private, so instead
 * of subclassing it this component embeds its own {@link Tint} and mirrors its parsing.</p>
 *
 * <p>Sorting: regular tinting runs at {@code -10}, this at {@code -5}, billboards at
 * {@code 0} — so on a collided particle the base color is computed first and then
 * overwritten here, before the (collision) billboard renders.</p>
 */
public class ParticleComponentCollisionTinting extends ParticleComponentBase implements IComponentParticleRender
{
    /** Molang gate, defaults to ZERO (i.e. off) — Blockbuster's default for this component. */
    public MolangExpression enabled = MolangParser.ZERO;

    /** The tint to compute on collided particles; {@code null} means "leave the base color". */
    private Tint color;

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        /* Reset before parsing so a re-parsed instance doesn't keep the previous scheme's tint. */
        this.enabled = MolangParser.ZERO;
        this.color = null;

        if (data.isMap())
        {
            MapType map = data.asMap();

            this.enabled = parser.parseDataSilently(map.get("enabled"), MolangParser.ZERO);

            if (map.has("color"))
            {
                BaseType color = map.get("color");

                if (color.isMap())
                {
                    this.color = Tint.parseGradient(color.asMap(), parser);
                }
                else
                {
                    this.color = Tint.parseColor(color, parser);
                }
            }
        }

        return super.fromData(data, parser);
    }

    @Override
    protected void toData(MapType data)
    {
        data.put("enabled", this.enabled.toData());

        if (this.color != null)
        {
            data.put("color", this.color.toData());
        }

        super.toData(data);
    }

    @Override
    public int getSortingIndex()
    {
        return -5;
    }

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        if (this.color == null
            || !SnowstormCompat.isEnabled()
            || !((IParticleCollisionAccess) particle).bbscgIsIntersected()
            || !Operation.equals(this.enabled.get(), 1))
        {
            return;
        }

        /* Overwrite whatever the regular tinting computed a moment ago. */
        this.color.compute(particle);
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        /* UI previews show the pre-collision look; the regular tinting already ran. */
    }

    @Override
    public void preRender(ParticleEmitter emitter, float partialTicks)
    {
    }

    @Override
    public void postRender(ParticleEmitter emitter, float partialTicks)
    {
    }

    @Override
    public boolean canBeEmpty()
    {
        return true;
    }
}
