package com.bbscg.particles;

import mchorse.bbs_mod.math.Operation;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The extended Snowstorm/Blockbuster collision engine, wired in by
 * {@code ParticleMotionCollisionMixin} as a full replacement of stock BBS's
 * {@code ParticleComponentMotionCollision.update} while {@code snowstorm_particles} is on.
 *
 * <p>Stock BBS only understands the five base fields (enabled, collision_drag, coefficient of
 * restitution, collision_radius, expire_on_contact) and flips the acceleration factor on
 * impact. This port adds Blockbuster's extras on top of the same skeleton: realistic
 * reflection ({@code realisticCollision}), delayed death ({@code expirationDelay}), damping
 * ({@code damp}/{@code random_damp}), bounce jitter ({@code bounciness_randomness}), spin
 * drag while touching ({@code collision_rotation_drag}), entity collision
 * ({@code entityCollision}), entity momentum ({@code momentum}), impact splits
 * ({@code split_particle_*}) and the collision events already shipped by the mixin.</p>
 *
 * <p>Entity lookups are cached per emitter per tick — reference effects can push a hundred
 * thousand particles in one frame and each of them would otherwise re-scan the world.</p>
 */
public class SnowstormCollision
{
    /** Cached entity list per (emitter, tick); the box is grown on demand. */
    private static class EntityCache
    {
        public int tick = -1;
        public Box box;
        public List<Entity> entities = Collections.emptyList();
    }

    private static final Map<ParticleEmitter, EntityCache> ENTITY_CACHE = new WeakHashMap<>();

    /* Scratch vectors — the particle system is single threaded (client render tick). */
    private static final Vector3d PREV = new Vector3d();
    private static final Vector3d NOW = new Vector3d();

    public static void update(ParticleComponentMotionCollision self, ParticleEmitter emitter, Particle particle)
    {
        if (emitter.world == null || particle.manualPosition || !Operation.equals(self.enabled.get(), 1))
        {
            return;
        }

        float r = self.radius;

        PREV.set(particle.getGlobalPosition(emitter, particle.prevPosition));
        NOW.set(particle.getGlobalPosition(emitter));

        double x = NOW.x - PREV.x;
        double y = NOW.y - PREV.y;
        double z = NOW.z - PREV.z;

        if (Math.abs(x) > 10 || Math.abs(y) > 10 || Math.abs(z) > 10)
        {
            return;
        }

        IParticleCollisionAccess access = (IParticleCollisionAccess) particle;
        ICollisionExtensionAccess ext = (ICollisionExtensionAccess) self;
        Box box = new Box(PREV.x - r, PREV.y - r, PREV.z - r, PREV.x + r, PREV.y + r, PREV.z + r);

        List<VoxelShape> shapes = ext.bbscgIsEntityCollision() ? collectEntityShapes(emitter, box) : Collections.emptyList();

        if (!shapes.isEmpty() && !access.bbscgIsIntersected())
        {
            access.bbscgMarkIntersected(particle.age);
        }

        Vec3d vec = Entity.adjustMovementForCollisions(null, new Vec3d(x, y, z), box, emitter.world, shapes);

        if (vec.x == x && vec.y == y && vec.z == z)
        {
            /* Nothing in the way this tick. */
            access.bbscgSetRotationCollisionDrag(0);

            return;
        }

        /* We hit something. */
        SnowstormCompat.fireEvents(emitter.scheme, ext.bbscgGetEvents(), emitter, particle, particle.getGlobalPosition(emitter));

        if (self.expireOnImpact && !access.bbscgHasCollided())
        {
            double delay = ext.bbscgGetExpirationDelay().get();

            if (delay == 0)
            {
                particle.setDead();

                return;
            }

            access.bbscgSetExpirationDelay(delay);
        }

        access.bbscgSetCollided(true);
        access.bbscgSetRotationCollisionDrag(ext.bbscgGetRotationCollisionDrag());

        if (particle.relativePosition)
        {
            /* Detach from the emitter's transform so it can't drag a landed particle around. */
            particle.relativePosition = false;
            particle.prevPosition.set(PREV);
        }

        NOW.set(PREV).add(vec.x, vec.y, vec.z);

        if (vec.y != y)
        {
            collisionHandler(emitter, self, ext, particle, access, 1);
        }

        if (vec.x != x)
        {
            collisionHandler(emitter, self, ext, particle, access, 0);
        }

        if (vec.z != z)
        {
            collisionHandler(emitter, self, ext, particle, access, 2);
        }

        particle.position.set(NOW);

        /* Realistic modes skip the drag while basically at rest (Blockbuster parity). */
        if (!(ext.bbscgIsRealisticCollision() || ext.bbscgGetRandomBounciness() != 0) || particle.speed.length() > 1.0E-4F)
        {
            particle.dragFactor += self.collisionDrag;
        }

        if (ext.bbscgIsMomentum())
        {
            applyMomentum(emitter, particle, box);
        }
    }

    private static void collisionHandler(ParticleEmitter emitter, ParticleComponentMotionCollision self, ICollisionExtensionAccess ext, Particle particle, IParticleCollisionAccess access, int axis)
    {
        Vector3f collisionTime = access.bbscgGetCollisionTime();
        float last = getAxis(collisionTime, axis);

        if (ext.bbscgIsRealisticCollision())
        {
            if ((int) last == particle.age - 1)
            {
                /* Also touching last tick — settle down instead of jittering. */
                setSpeedAxis(particle, axis, 0);
            }
            else if (self.bounciness != 0)
            {
                setSpeedAxis(particle, axis, -getSpeedAxis(particle, axis) * self.bounciness);
            }
        }
        else
        {
            setAccelFactorAxis(particle, axis, getAccelFactorAxis(particle, axis) * -self.bounciness);
        }

        if ((int) last != particle.age - 1)
        {
            /* First tick of contact on this axis. */
            float randomBounciness = ext.bbscgGetRandomBounciness();

            if (randomBounciness != 0)
            {
                setSpeedAxis(particle, axis, getSpeedAxis(particle, axis) + (float) (Math.random() * 2.0 - 1.0) * randomBounciness);
            }

            int split = ext.bbscgGetSplitParticleCount();

            /* Blockbuster only splits on ground (Y) impacts, when the impact speed on that
             * axis exceeds the threshold — a slow landing just settles. */
            if (axis == 1 && split > 0 && Math.abs(getSpeedAxis(particle, axis)) > Math.abs(ext.bbscgGetSplitParticleSpeedThreshold()))
            {
                splitParticle(emitter, particle, access, split);

                particle.setDead();
            }

            float damp = ext.bbscgGetDamp();

            if (damp != 0)
            {
                float factor = 1 - damp + ext.bbscgGetRandomDamp() * (float) (Math.random() * 2.0 - 1.0);

                factor = factor < 0 ? 0 : (factor > 1 ? 1 : factor);

                particle.speed.mul(factor);
            }
        }

        setAxis(collisionTime, axis, particle.age);
    }

    private static void splitParticle(ParticleEmitter emitter, Particle parent, IParticleCollisionAccess parentAccess, int count)
    {
        for (int i = 0; i < count && emitter.running; i++)
        {
            int before = emitter.particles.size();

            emitter.spawnParticle(0);

            if (emitter.particles.size() <= before)
            {
                return;
            }

            Particle split = emitter.particles.get(emitter.particles.size() - 1);

            split.position.set(parent.position);
            split.prevPosition.set(parent.prevPosition);
            split.speed.set(parent.speed);
            split.acceleration.set(parent.acceleration);
            split.age = parent.age;
            split.lifetime = parent.lifetime;
            split.rotation = parent.rotation;
            split.rotationVelocity = parent.rotationVelocity;
            split.dragFactor = parent.dragFactor;

            /* Throw it in a random direction with the same magnitude as the parent. */
            float length = split.speed.length();

            if (length > 1.0E-4F)
            {
                split.speed.set((float) (Math.random() * 2.0 - 1.0), (float) (Math.random() * 2.0 - 1.0), (float) (Math.random() * 2.0 - 1.0));
                split.speed.normalize(length);
            }

            /* Inherit the collision state, but mark "already collided this tick" so the
               children don't immediately split again. */
            IParticleCollisionAccess splitAccess = (IParticleCollisionAccess) split;

            if (parentAccess.bbscgIsIntersected())
            {
                splitAccess.bbscgMarkIntersected(split.age);
            }

            splitAccess.bbscgSetCollided(true);
            splitAccess.bbscgGetCollisionTime().set(split.age, split.age, split.age);
        }
    }

    private static void applyMomentum(ParticleEmitter emitter, Particle particle, Box box)
    {
        for (Entity entity : getCachedEntities(emitter, box))
        {
            if (!entity.isAlive() || entity.isSpectator())
            {
                continue;
            }

            if (!entity.getBoundingBox().intersects(box))
            {
                continue;
            }

            particle.speed.x += (float) ((entity.getX() - entity.prevX) * 2.0);
            particle.speed.y += (float) ((entity.getY() - entity.prevY) * 2.0);
            particle.speed.z += (float) ((entity.getZ() - entity.prevZ) * 2.0);
        }
    }

    private static List<VoxelShape> collectEntityShapes(ParticleEmitter emitter, Box box)
    {
        List<VoxelShape> shapes = new ArrayList<>();

        for (Entity entity : getCachedEntities(emitter, box))
        {
            if (entity.getBoundingBox().intersects(box))
            {
                shapes.add(VoxelShapes.cuboid(entity.getBoundingBox()));
            }
        }

        return shapes;
    }

    private static List<Entity> getCachedEntities(ParticleEmitter emitter, Box box)
    {
        if (emitter.world == null)
        {
            return Collections.emptyList();
        }

        EntityCache cache = ENTITY_CACHE.computeIfAbsent(emitter, e -> new EntityCache());

        if (cache.tick != emitter.age || cache.box == null || !cache.box.contains(box.minX, box.minY, box.minZ) || !cache.box.contains(box.maxX, box.maxY, box.maxZ))
        {
            cache.tick = emitter.age;
            cache.box = box.expand(16.0);
            cache.entities = emitter.world.getOtherEntities(null, cache.box, e -> e.isAlive() && !e.isSpectator());
        }

        return cache.entities;
    }

    private static float getAxis(Vector3f vector, int axis)
    {
        return axis == 0 ? vector.x : (axis == 1 ? vector.y : vector.z);
    }

    private static void setAxis(Vector3f vector, int axis, float value)
    {
        if (axis == 0)
        {
            vector.x = value;
        }
        else if (axis == 1)
        {
            vector.y = value;
        }
        else
        {
            vector.z = value;
        }
    }

    private static float getSpeedAxis(Particle particle, int axis)
    {
        return axis == 0 ? particle.speed.x : (axis == 1 ? particle.speed.y : particle.speed.z);
    }

    private static void setSpeedAxis(Particle particle, int axis, float value)
    {
        if (axis == 0)
        {
            particle.speed.x = value;
        }
        else if (axis == 1)
        {
            particle.speed.y = value;
        }
        else
        {
            particle.speed.z = value;
        }
    }

    private static float getAccelFactorAxis(Particle particle, int axis)
    {
        return axis == 0 ? particle.accelerationFactor.x : (axis == 1 ? particle.accelerationFactor.y : particle.accelerationFactor.z);
    }

    private static void setAccelFactorAxis(Particle particle, int axis, float value)
    {
        if (axis == 0)
        {
            particle.accelerationFactor.x = value;
        }
        else if (axis == 1)
        {
            particle.accelerationFactor.y = value;
        }
        else
        {
            particle.accelerationFactor.z = value;
        }
    }
}
