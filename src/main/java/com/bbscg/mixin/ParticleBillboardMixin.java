package com.bbscg.mixin;

import com.bbscg.particles.ParticleComponentCollisionAppearance;
import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.ParticleComponentBase;
import mchorse.bbs_mod.particles.components.appearance.BillboardDirection;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.emitter.Particle;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the four {@code facing_camera_mode} values Snowstorm supports that stock BBS 2.7 lacks
 * — {@code lookat_direction}, {@code emitter_transform_xy}, {@code emitter_transform_xz} and
 * {@code emitter_transform_yz} (stock degrades all of them to {@code rotate_xyz}) — plus
 * Snowstorm's {@code "custom"} spelling of the billboard direction mode (stock only recognizes
 * {@code "custom_direction"}, so a custom direction silently degrades to derive-from-velocity).
 *
 * <p>Everything here is inert while the {@code snowstorm_particles} setting is off. The four new
 * facing modes replace the whole render pass (they need their own orientation math), so the
 * injection point is the head of {@code render} with cancellation — the rest of the stock
 * method is replicated verbatim below the orientation switch.</p>
 */
@Mixin(ParticleComponentAppearanceBillboard.class)
public abstract class ParticleBillboardMixin
{
    @Shadow
    private float w;

    @Shadow
    private float h;

    @Shadow
    private Matrix4f transform;

    @Shadow
    private Matrix4f rotation;

    @Shadow
    private Vector4f[] vertices;

    @Shadow
    private Vector3f vector;

    @Shadow
    private Vector3f n;

    @Shadow
    protected abstract void calculateUVs(Particle particle, ParticleEmitter emitter, float transition);

    @Shadow
    private void updateFacingDirection(ParticleEmitter emitter, Particle particle, boolean staticSpace)
    {
        throw new AbstractMethodError();
    }

    @Shadow
    private void build(BufferBuilder builder, VertexFormat format, Matrix4f matrix, Particle particle, int overlay)
    {
        throw new AbstractMethodError();
    }

    @Shadow
    protected abstract void toData(MapType data);

    /* The raw facing_camera_mode string as written in JSON (kept for the new modes) */
    @Unique
    private String bbscgRawFacing;

    @Unique
    private static boolean bbscgFacingDiagLogged;

    @Unique
    private static boolean bbscgIsNewFacing(String facing)
    {
        return "lookat_direction".equals(facing)
            || "emitter_transform_xy".equals(facing)
            || "emitter_transform_xz".equals(facing)
            || "emitter_transform_yz".equals(facing)
            || "direction_x".equals(facing)
            || "direction_y".equals(facing);
    }

    /* Keep the raw facing mode string around — stock BBS replaces unknown values with
     * rotate_xyz, losing the original. */
    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbscgSaveRawFacing(BaseType data, MolangParser parser, CallbackInfoReturnable<ParticleComponentBase> cir)
    {
        if (!data.isMap())
        {
            return;
        }

        String raw = data.asMap().getString("facing_camera_mode");

        if (!raw.isEmpty())
        {
            this.bbscgRawFacing = raw;
        }
    }

    /* Snowstorm writes direction.mode as "custom"; BBS's enum id is "custom_direction" —
     * fix the parse result after the stock direction parsing ran. */
    @Inject(method = "parseDirection", at = @At("TAIL"))
    private void bbscgFixCustomDirectionMode(MapType data, MolangParser parser, CallbackInfo ci)
    {
        if (SnowstormCompat.isEnabled() && "custom".equals(data.getString("mode")))
        {
            ((ParticleComponentAppearanceBillboard) (Object) this).directionMode = BillboardDirection.CUSTOM_DIRECTION;
        }
    }

    /* Blockbuster falls back to (1, 0, 0) when a custom direction evaluates to (nearly) zero
     * (BedrockComponentAppearanceBillboard#render, the lengthSq < 0.0001 branch); stock BBS
     * keeps the zero vector, which orients the quad face-on to +Z. Effects that sit still and
     * point their billboard at variable.particle_speed — the shields and the black holes —
     * end up edge-on and effectively invisible from most view angles. */
    @Inject(method = "updateFacingDirection", at = @At("TAIL"))
    private void bbscgZeroDirectionFallback(ParticleEmitter emitter, Particle particle, boolean staticSpace, CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        if (((ParticleComponentAppearanceBillboard) (Object) this).directionMode == BillboardDirection.CUSTOM_DIRECTION && particle.facingDirection.lengthSquared() < 1.0e-4F)
        {
            particle.facingDirection.set(1F, 0F, 0F);
        }
    }

    /* Round-trip: write the raw facing string back so imported files don't degrade */
    @Inject(method = "toData", at = @At("TAIL"))
    private void bbscgWriteRawFacing(MapType data, CallbackInfo ci)
    {
        if (this.bbscgRawFacing != null && bbscgIsNewFacing(this.bbscgRawFacing))
        {
            data.putString("facing_camera_mode", this.bbscgRawFacing);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bbscgRenderNewFacings(ParticleEmitter emitter, VertexFormat format, Particle particle, BufferBuilder builder, Matrix4f matrix, int overlay, float transition, CallbackInfo ci)
    {
        /* Collision appearance guard: once a particle has collided and the scheme has an
         * active collision appearance, the regular billboard must not also draw it (the
         * collision appearance component renders its own quad at sorting index 200). This
         * must run before the new-facing branch below — hence one combined injector. */
        if (SnowstormCompat.isEnabled()
            && !((Object) this instanceof ParticleComponentCollisionAppearance)
            && SnowstormCompat.isCollisionTexture(emitter, particle))
        {
            ci.cancel();

            return;
        }

        if (!SnowstormCompat.isEnabled() || this.bbscgRawFacing == null || !bbscgIsNewFacing(this.bbscgRawFacing))
        {
            return;
        }

        String facing = this.bbscgRawFacing;

        if (!bbscgFacingDiagLogged)
        {
            bbscgFacingDiagLogged = true;
            System.out.println("[BBS-CG] Render path: new facing '" + facing + "' is being handled by the addon");
        }

        this.calculateUVs(particle, emitter, transition);

        double px = Lerps.lerp(particle.prevPosition.x, particle.position.x, transition);
        double py = Lerps.lerp(particle.prevPosition.y, particle.position.y, transition);
        double pz = Lerps.lerp(particle.prevPosition.z, particle.position.z, transition);
        float angle = Lerps.lerp(particle.prevRotation, particle.rotation, transition);
        float scale = 1F;
        boolean staticSpace = particle.relativePosition && particle.relativeRotation;

        if (staticSpace)
        {
            this.vector.set((float) px, (float) py, (float) pz);
            emitter.rotation.transform(this.vector);

            px = this.vector.x;
            py = this.vector.y;
            pz = this.vector.z;

            px += emitter.lastGlobal.x;
            py += emitter.lastGlobal.y;
            pz += emitter.lastGlobal.z;
        }

        if (particle.textureScale)
        {
            scale = (staticSpace ? emitter.rotation : particle.matrix).getRow(0, Vectors.TEMP_3F).length();
        }

        px -= emitter.cX;
        py -= emitter.cY;
        pz -= emitter.cZ;

        this.vertices[0].set(-this.w / 2, -this.h / 2, 0, 1);
        this.vertices[1].set(this.w / 2, -this.h / 2, 0, 1);
        this.vertices[2].set(this.w / 2, this.h / 2, 0, 1);
        this.vertices[3].set(-this.w / 2, this.h / 2, 0, 1);
        this.transform.identity();

        if ("lookat_direction".equals(facing))
        {
            /* Blockbuster LOOKAT_DIRECTION geometry: the quad's local Y axis aligns with
             * the direction vector (hence rotateX by -pitch + 90°), then the quad is
             * rotated around that axis (local Y = direction) so its plane faces the
             * camera as closely as possible (camera direction projected onto the plane). */
            this.updateFacingDirection(emitter, particle, staticSpace);

            float nx = particle.facingDirection.x;
            float ny = particle.facingDirection.y;
            float nz = particle.facingDirection.z;

            /* Straight up/down degenerate handling, matching Snowstorm/Wintersky */
            if (ny == 1F)
            {
                ny = -1F;
            }
            else if (ny == -1F)
            {
                ny = 1F;
                nz = -1.0E-5F;
            }

            float yaw = (float) Math.atan2(nx, nz);
            float pitch = (float) Math.atan2(ny, Math.sqrt(nx * nx + nz * nz));

            this.transform.rotateY(yaw).rotateX(-pitch + (float) (Math.PI / 2D));

            /* Camera alignment: the camera sits at the origin of the (px, py, pz) space,
             * so the camera-to-particle direction is simply (-px, -py, -pz). Project out
             * the direction component, then rotate around local Y towards the camera. */
            Vector3f cameraDir = new Vector3f((float) -px, (float) -py, (float) -pz);
            Vector3f rotatedNormal = new Vector3f(0F, 0F, 1F);

            this.transform.transformDirection(rotatedNormal);

            Vector3f dir = new Vector3f(nx, ny, nz);
            Vector3f projectDir = new Vector3f(dir).mul(cameraDir.dot(dir));

            cameraDir.sub(projectDir);

            if (cameraDir.lengthSquared() >= 1.0e-30)
            {
                cameraDir.normalize();

                Vector3f rotationDirection = new Vector3f(cameraDir).cross(rotatedNormal);
                float a = (float) Math.acos(Math.min(1.0, Math.max(-1.0, cameraDir.dot(rotatedNormal))));

                this.transform.rotateY(-Math.copySign(a, rotationDirection.dot(dir)));
            }
        }
        else if ("direction_x".equals(facing) || "direction_y".equals(facing))
        {
            /* Blockbuster DIRECTION_X/Y geometry (the stock BBS formulas don't match
             * Blockbuster's): DIRECTION_X = rotY(yaw)·rotX(pitch)·rotY(90°),
             * DIRECTION_Y = rotY(yaw)·rotX(pitch + 90°). */
            this.updateFacingDirection(emitter, particle, staticSpace);

            float nx = particle.facingDirection.x;
            float ny = particle.facingDirection.y;
            float nz = particle.facingDirection.z;

            if (ny == 1F)
            {
                ny = -1F;
            }
            else if (ny == -1F)
            {
                ny = 1F;
                nz = -1.0E-5F;
            }

            float yaw = (float) Math.atan2(nx, nz);
            float pitch = (float) Math.atan2(ny, Math.sqrt(nx * nx + nz * nz));

            if ("direction_x".equals(facing))
            {
                this.transform.rotateY(yaw).rotateX(-pitch).rotateY((float) (Math.PI / 2D));
            }
            else
            {
                this.transform.rotateY(yaw).rotateX(-pitch + (float) (Math.PI / 2D));
            }
        }
        else
        {
            /* emitter_transform_* — Blockbuster EMITTER_* geometry: the quad is rotated
             * onto the requested plane with a fixed 180°/90° orientation correction and
             * does NOT inherit the emitter's rotation (Blockbuster renders these planes
             * world-fixed): XY = rotX(180°), XZ = rotX(90°), YZ = rotZ(180°)·rotY(90°). */
            if ("emitter_transform_xy".equals(facing))
            {
                this.transform.rotateX((float) Math.PI);
            }
            else if ("emitter_transform_xz".equals(facing))
            {
                this.transform.rotateX((float) (Math.PI / 2D));
            }
            else
            {
                this.transform.rotateZ((float) Math.PI).rotateY((float) (Math.PI / 2D));
            }
        }

        if (format != VertexFormats.POSITION_TEXTURE_COLOR_LIGHT)
        {
            this.n.set(0F, 0F, 1F);

            Matrices.EMPTY_3F.set(this.transform).transform(this.n);
            this.n.normalize();
        }

        this.rotation.identity();
        this.rotation.rotateZ(angle / 180 * (float) Math.PI);
        this.transform.mul(this.rotation);

        this.transform.scale(scale);
        this.transform.setTranslation(new Vector3f((float) px, (float) py, (float) pz));

        this.build(builder, format, matrix, particle, overlay);

        ci.cancel();
    }
}
