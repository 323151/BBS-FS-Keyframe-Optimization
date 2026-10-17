package com.bbscg.particles;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.MolangException;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import org.joml.Matrix4f;

/**
 * {@code blockbuster:particle_collision_appearance}: a second billboard look that replaces the
 * particle's quad after it has collided with something.
 *
 * <p>Ported from Blockbuster's {@code BedrockComponentCollisionAppearance}, which extends the
 * billboard component and reuses all of its geometry code. Typical Snowstorm reference effect:
 * a water droplet flies as a 1px dot, then on impact swaps to a splash flipbook animation that
 * starts playing <i>from the collision moment</i> and expires {@code expirationDelay} ticks
 * later. {@code calculateUVs} is overridden to fake that shifted timeline by temporarily
 * rewriting {@code particle.age} / {@code particle.lifetime} around the inherited call.</p>
 *
 * <p>Sorting index {@code 200} keeps this after the regular billboard ({@code 0}); the
 * billboard mixin skips already-collided particles when this component is active, so exactly
 * one of the two quads renders. {@code renderUI} is a no-op so editor previews only show the
 * pre-collision look. The component's own {@code texture}/{@code material} fields are parsed
 * and round-tripped but intentionally not applied — all reference effects use the scheme's
 * own texture, and swapping textures mid-pass would require a second draw pass.</p>
 */
public class ParticleComponentCollisionAppearance extends ParticleComponentAppearanceBillboard
{
    /** Molang gate, defaults to ZERO (i.e. off) — Blockbuster's default for this component. */
    public MolangExpression enabled = MolangParser.ZERO;

    /** Whether the collision quad should receive lighting. */
    public boolean lit;

    /** Parsed and round-tripped, but not applied (see class comment). */
    public String material;

    /** Parsed and round-tripped, but not applied (see class comment). */
    public String texture;

    @Override
    public ParticleComponentBase fromData(BaseType data, MolangParser parser) throws MolangException
    {
        /* Billboard's fromData resets its own fields; ours default to "off" before it runs. */
        this.enabled = MolangParser.ZERO;
        this.lit = false;
        this.material = null;
        this.texture = null;

        ParticleComponentBase result = super.fromData(data, parser);

        if (result == null || !data.isMap())
        {
            return result;
        }

        MapType map = data.asMap();

        this.enabled = map.has("enabled") ? parser.parseDataSilently(map.get("enabled"), MolangParser.ZERO) : MolangParser.ZERO;
        this.lit = map.getBool("lit");
        this.material = map.has("material") ? map.getString("material") : null;
        this.texture = map.has("texture") ? map.getString("texture") : null;

        return result;
    }

    @Override
    protected void toData(MapType data)
    {
        super.toData(data);

        data.put("enabled", this.enabled.toData());

        if (this.lit)
        {
            data.putBool("lit", true);
        }

        if (this.material != null)
        {
            data.putString("material", this.material);
        }

        if (this.texture != null)
        {
            data.putString("texture", this.texture);
        }
    }

    @Override
    public int getSortingIndex()
    {
        return 200;
    }

    /**
     * Whether this particle should render through this component (as opposed to the regular
     * billboard): the setting is on, the gate evaluates to 1 and the particle has collided.
     */
    public boolean isCollisionTexture(ParticleEmitter emitter, Particle particle)
    {
        return SnowstormCompat.isEnabled()
            && ((IParticleCollisionAccess) particle).bbscgIsIntersected()
            && Operation.equals(this.enabled.get(), 1);
    }

    @Override
    public void render(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition)
    {
        if (!this.isCollisionTexture(emitter, particle))
        {
            return;
        }

        boolean wasLit = emitter.lit;

        emitter.lit = this.lit;

        try
        {
            super.render(emitter, format, particle, builder, matrix, overlay, transition);
        }
        finally
        {
            emitter.lit = wasLit;
        }
    }

    @Override
    public void renderUI(Particle particle, BufferBuilder builder, Matrix4f matrix, float transition)
    {
        /* Editor previews show the pre-collision look only. */
    }

    /**
     * Shift the flipbook clock so animations start at the first collision: the effective age
     * becomes {@code age - firstIntersection} and the effective span becomes the expiration
     * delay (when set) or the remaining lifetime.
     */
    @Override
    public void calculateUVs(Particle particle, ParticleEmitter emitter, float transition)
    {
        IParticleCollisionAccess access = (IParticleCollisionAccess) particle;
        int firstIntersection = access.bbscgGetFirstIntersection();

        if (firstIntersection < 0)
        {
            super.calculateUVs(particle, emitter, transition);

            return;
        }

        int realAge = particle.age;
        int realLifetime = particle.lifetime;
        int expireAge = access.bbscgGetExpireAge();
        int span = expireAge != -1 ? access.bbscgGetExpirationDelay() : realLifetime - firstIntersection;

        if (span < 1)
        {
            span = 1;
        }

        particle.age = Math.max(0, realAge - firstIntersection);
        particle.lifetime = span;

        try
        {
            super.calculateUVs(particle, emitter, transition);
        }
        finally
        {
            particle.age = realAge;
            particle.lifetime = realLifetime;
        }
    }
}
