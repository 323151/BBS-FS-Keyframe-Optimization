package com.bbscg.mixin;

import com.bbscg.particles.ICollisionExtensionAccess;
import com.bbscg.particles.ParticleEvent;
import com.bbscg.particles.SnowstormCollision;
import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Extends {@code minecraft:particle_motion_collision} with everything Snowstorm reference
 * effects use beyond BBS 2.7's five base fields: {@code events} (collision event triggers)
 * plus the Blockbuster extended physics set — {@code realisticCollision},
 * {@code realistic_collision_drag}, {@code entityCollision}, {@code momentum}, {@code damp},
 * {@code random_damp}, {@code bounciness_randomness}, {@code collision_rotation_drag},
 * {@code split_particle_count}, {@code split_particle_speedThreshold} and
 * {@code expirationDelay}.
 *
 * <p>While the setting is on, the whole update is replaced by {@link SnowstormCollision}
 * (which fires the events itself); while it's off, stock behaviour runs untouched but the
 * fields still parse and round-trip, so editor saves never lose them.</p>
 */
@Mixin(ParticleComponentMotionCollision.class)
public abstract class ParticleMotionCollisionMixin implements ICollisionExtensionAccess
{
    @Unique
    private List<String> bbscgEvents;

    @Unique
    private boolean bbscgRealisticCollision;

    @Unique
    private boolean bbscgRealisticCollisionDrag;

    @Unique
    private boolean bbscgEntityCollision;

    @Unique
    private boolean bbscgMomentum;

    @Unique
    private float bbscgDamp;

    @Unique
    private float bbscgRandomDamp;

    @Unique
    private float bbscgRandomBounciness;

    @Unique
    private float bbscgRotationCollisionDrag;

    @Unique
    private MolangExpression bbscgSplitParticleCount;

    @Unique
    private MolangExpression bbscgSplitParticleSpeedThreshold;

    @Unique
    private MolangExpression bbscgExpirationDelay;

    /* @Unique field initializers are dropped by mixin merging — init lazily instead */
    private List<String> bbscgEvents()
    {
        if (this.bbscgEvents == null)
        {
            this.bbscgEvents = new ArrayList<>();
        }

        return this.bbscgEvents;
    }

    private MolangExpression bbscgSplitParticleCount()
    {
        if (this.bbscgSplitParticleCount == null)
        {
            this.bbscgSplitParticleCount = MolangParser.ZERO;
        }

        return this.bbscgSplitParticleCount;
    }

    private MolangExpression bbscgSplitParticleSpeedThreshold()
    {
        if (this.bbscgSplitParticleSpeedThreshold == null)
        {
            this.bbscgSplitParticleSpeedThreshold = MolangParser.ZERO;
        }

        return this.bbscgSplitParticleSpeedThreshold;
    }

    private MolangExpression bbscgExpirationDelay()
    {
        if (this.bbscgExpirationDelay == null)
        {
            this.bbscgExpirationDelay = MolangParser.ZERO;
        }

        return this.bbscgExpirationDelay;
    }

    /* Parse the extension fields (a single string or an array of event ids for events) */
    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbscgParseCollisionExtensions(BaseType data, MolangParser parser, CallbackInfoReturnable<ParticleComponentBase> cir)
    {
        this.bbscgEvents().clear();
        this.bbscgRealisticCollision = false;
        this.bbscgRealisticCollisionDrag = false;
        this.bbscgEntityCollision = false;
        this.bbscgMomentum = false;
        this.bbscgDamp = 0;
        this.bbscgRandomDamp = 0;
        this.bbscgRandomBounciness = 0;
        this.bbscgRotationCollisionDrag = 0;
        this.bbscgSplitParticleCount = null;
        this.bbscgSplitParticleSpeedThreshold = null;
        this.bbscgExpirationDelay = null;

        if (!data.isMap())
        {
            return;
        }

        MapType map = data.asMap();

        this.bbscgEvents().addAll(ParticleEvent.parseReferences(map.get("events")));
        this.bbscgRealisticCollision = map.getBool("realisticCollision");
        this.bbscgRealisticCollisionDrag = map.getBool("realistic_collision_drag");
        this.bbscgEntityCollision = map.getBool("entityCollision");
        this.bbscgMomentum = map.getBool("momentum");

        if (map.has("damp")) this.bbscgDamp = map.getFloat("damp");
        if (map.has("random_damp")) this.bbscgRandomDamp = map.getFloat("random_damp");
        if (map.has("bounciness_randomness")) this.bbscgRandomBounciness = map.getFloat("bounciness_randomness");
        if (map.has("collision_rotation_drag")) this.bbscgRotationCollisionDrag = map.getFloat("collision_rotation_drag");

        if (map.has("split_particle_count")) this.bbscgSplitParticleCount = parser.parseDataSilently(map.get("split_particle_count"), MolangParser.ZERO);
        if (map.has("split_particle_speedThreshold")) this.bbscgSplitParticleSpeedThreshold = parser.parseDataSilently(map.get("split_particle_speedThreshold"), MolangParser.ZERO);
        if (map.has("expirationDelay")) this.bbscgExpirationDelay = parser.parseDataSilently(map.get("expirationDelay"), MolangParser.ZERO);
    }

    /* Write the extension fields back on save (only when they differ from the defaults) */
    @Inject(method = "toData()Lmchorse/bbs_mod/data/types/BaseType;", at = @At("TAIL"))
    private void bbscgWriteCollisionExtensions(CallbackInfoReturnable<BaseType> cir)
    {
        BaseType result = cir.getReturnValue();

        if (result == null || !result.isMap())
        {
            return;
        }

        MapType map = result.asMap();

        if (this.bbscgEvents != null && !this.bbscgEvents.isEmpty())
        {
            map.put("events", ParticleEvent.referencesToData(this.bbscgEvents));
        }

        if (this.bbscgRealisticCollision) map.putBool("realisticCollision", true);
        if (this.bbscgRealisticCollisionDrag) map.putBool("realistic_collision_drag", true);
        if (this.bbscgEntityCollision) map.putBool("entityCollision", true);
        if (this.bbscgMomentum) map.putBool("momentum", true);

        if (this.bbscgDamp != 0) map.putFloat("damp", this.bbscgDamp);
        if (this.bbscgRandomDamp != 0) map.putFloat("random_damp", this.bbscgRandomDamp);
        if (this.bbscgRandomBounciness != 0) map.putFloat("bounciness_randomness", this.bbscgRandomBounciness);
        if (this.bbscgRotationCollisionDrag != 0) map.putFloat("collision_rotation_drag", this.bbscgRotationCollisionDrag);

        if (this.bbscgSplitParticleCount != null && !MolangExpression.isZero(this.bbscgSplitParticleCount))
        {
            map.put("split_particle_count", this.bbscgSplitParticleCount.toData());
        }

        if (this.bbscgSplitParticleSpeedThreshold != null && !MolangExpression.isZero(this.bbscgSplitParticleSpeedThreshold))
        {
            map.put("split_particle_speedThreshold", this.bbscgSplitParticleSpeedThreshold.toData());
        }

        if (this.bbscgExpirationDelay != null && !MolangExpression.isZero(this.bbscgExpirationDelay))
        {
            map.put("expirationDelay", this.bbscgExpirationDelay.toData());
        }
    }

    /* Replace the whole update with the extended engine while the setting is on */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void bbscgRunSnowstormCollision(ParticleEmitter emitter, Particle particle, CallbackInfo ci)
    {
        if (SnowstormCompat.isEnabled())
        {
            SnowstormCollision.update((ParticleComponentMotionCollision) (Object) this, emitter, particle);
            ci.cancel();
        }
    }

    @Override
    public List<String> bbscgGetEvents()
    {
        return this.bbscgEvents();
    }

    @Override
    public boolean bbscgIsRealisticCollision()
    {
        return this.bbscgRealisticCollision;
    }

    @Override
    public boolean bbscgIsRealisticCollisionDrag()
    {
        return this.bbscgRealisticCollisionDrag;
    }

    @Override
    public boolean bbscgIsEntityCollision()
    {
        return this.bbscgEntityCollision;
    }

    @Override
    public boolean bbscgIsMomentum()
    {
        return this.bbscgMomentum;
    }

    @Override
    public float bbscgGetDamp()
    {
        return this.bbscgDamp;
    }

    @Override
    public float bbscgGetRandomDamp()
    {
        return this.bbscgRandomDamp;
    }

    @Override
    public float bbscgGetRandomBounciness()
    {
        return this.bbscgRandomBounciness;
    }

    @Override
    public float bbscgGetRotationCollisionDrag()
    {
        return this.bbscgRotationCollisionDrag;
    }

    @Override
    public int bbscgGetSplitParticleCount()
    {
        return (int) this.bbscgSplitParticleCount().get();
    }

    @Override
    public float bbscgGetSplitParticleSpeedThreshold()
    {
        return (float) this.bbscgSplitParticleSpeedThreshold().get();
    }

    @Override
    public MolangExpression bbscgGetExpirationDelay()
    {
        return this.bbscgExpirationDelay();
    }
}
