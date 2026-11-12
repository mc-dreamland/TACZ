package com.tacz.guns.client.renderer.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/** Render-thread muzzle samples shared by a local flash and its independently arriving pellets. */
public final class ShotMuzzleSnapshot {
    private static final long SAMPLE_MAX_AGE = 250_000_000L;
    private static final long SHOT_MAX_AGE = 2_000_000_000L;
    private static @Nullable Sample latest;
    private static @Nullable Shot current;

    private ShotMuzzleSnapshot() { }

    private record Sample(Object scene, Identifier gun, long time, Snapshot snapshot) { }

    public static void sample(Object scene, Identifier gun, long time, Snapshot snapshot) {
        latest = new Sample(scene, gun, time, snapshot);
        // A shot before the first hand render waits for one sample instead of borrowing another gun's muzzle.
        Shot shot = current(scene, gun, time);
        if (shot != null && shot.snapshot == null) shot.snapshot = snapshot;
    }

    public static Shot begin(Object scene, Identifier gun, long time) {
        Snapshot snapshot = latest != null && latest.scene == scene && latest.gun.equals(gun)
                && time >= latest.time && time - latest.time <= SAMPLE_MAX_AGE ? latest.snapshot : null;
        current = new Shot(scene, gun, time, snapshot);
        return current;
    }

    public static @Nullable Shot current(Object scene, Identifier gun, long time) {
        return current != null && current.scene == scene && current.gun.equals(gun)
                && time >= current.time && time - current.time <= SHOT_MAX_AGE ? current : null;
    }

    public static void clear() {
        latest = null;
        current = null;
    }

    public static final class Shot {
        private final Object scene;
        private final Identifier gun;
        private final long time;
        private @Nullable Snapshot snapshot;

        private Shot(Object scene, Identifier gun, long time, @Nullable Snapshot snapshot) {
            this.scene = scene;
            this.gun = gun;
            this.time = time;
            this.snapshot = snapshot;
        }

        public @Nullable Snapshot snapshot() { return snapshot; }
    }

    public static final class Snapshot {
        private final Vec3 origin;
        private final Matrix4f worldPose;

        public Snapshot(Matrix4fc muzzlePose, Matrix3fc handCameraBasis, Vec3 cameraPosition,
                        Quaternionfc cameraRotation, double fovScale) {
            Matrix4f viewPose = new Matrix4f().set(handCameraBasis).transpose().mul(muzzlePose);
            Vector3f offset = new Vector3f(viewPose.m30(), viewPose.m31(), (float) (viewPose.m32() * fovScale));
            offset.rotate(cameraRotation);
            origin = cameraPosition.add(offset.x, offset.y, offset.z);
            worldPose = new Matrix4f().rotation(cameraRotation).mul(viewPose);
        }

        public Vec3 origin() { return origin; }

        public Snapshot withOrigin(Vec3 origin) {
            return new Snapshot(origin, worldPose);
        }

        private Snapshot(Vec3 origin, Matrix4fc worldPose) {
            this.origin = origin;
            this.worldPose = new Matrix4f(worldPose);
        }

        /** Reproject the frozen world muzzle into either vanilla's or Iris's current hand pass. */
        public PoseStack pose(Vec3 cameraPosition, Quaternionfc cameraRotation,
                              Matrix3fc handCameraBasis, double fovScale) {
            Quaternionf inverseCamera = new Quaternionf(cameraRotation).conjugate();
            Vector3f offset = origin.subtract(cameraPosition).toVector3f().rotate(inverseCamera);
            Matrix4f viewPose = new Matrix4f().rotation(inverseCamera).mul(worldPose);
            viewPose.setTranslation(offset.x, offset.y, (float) (offset.z / fovScale));
            PoseStack result = new PoseStack();
            result.last().pose().set(handCameraBasis).mul(viewPose);
            result.last().pose().normal(result.last().normal());
            return result;
        }
    }
}
