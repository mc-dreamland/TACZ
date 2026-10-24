package com.tacz.guns.client.model.functional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MuzzleFlashPoseTest {
    @Test
    void recoilCannotRotateOrMoveAnAlreadyVisibleFlash() {
        MuzzleFlashRender.ShotPose shot = new MuzzleFlashRender.ShotPose();
        PoseStack muzzle = new PoseStack();
        muzzle.translate(0.4, -0.2, -1.2);
        muzzle.mulPose(Axis.YP.rotationDegrees(15));
        PoseStack initial = shot.snapshot(muzzle);

        muzzle.translate(0, 0.3, 0.1);
        muzzle.mulPose(Axis.XP.rotationDegrees(70));
        muzzle.mulPose(Axis.ZP.rotationDegrees(35));
        PoseStack nextFrame = shot.snapshot(muzzle);

        assertPoseEquals(initial, nextFrame);
    }

    @Test
    void nextShotUsesItsOwnMuzzlePoseAndCannotChangeQueuedPreviousShot() {
        MuzzleFlashRender.ShotPose shot = new MuzzleFlashRender.ShotPose();
        PoseStack muzzle = new PoseStack();
        muzzle.mulPose(Axis.YP.rotationDegrees(20));
        PoseStack queuedPreviousShot = shot.snapshot(muzzle);
        Matrix4f previousPose = new Matrix4f(queuedPreviousShot.last().pose());
        Matrix3f previousNormal = new Matrix3f(queuedPreviousShot.last().normal());

        shot.reset();
        muzzle.translate(1, 2, 3);
        muzzle.mulPose(Axis.XP.rotationDegrees(60));
        PoseStack newShot = shot.snapshot(muzzle);

        assertPoseEquals(muzzle, newShot);
        assertTrue(previousPose.equals(queuedPreviousShot.last().pose(), 1.0e-6F));
        assertTrue(previousNormal.equals(queuedPreviousShot.last().normal(), 1.0e-6F));
    }

    @Test
    void layerTransformsCannotLeakIntoOtherFrames() {
        MuzzleFlashRender.ShotPose shot = new MuzzleFlashRender.ShotPose();
        PoseStack muzzle = new PoseStack();
        muzzle.mulPose(Axis.YP.rotationDegrees(30));
        PoseStack layer = shot.snapshot(muzzle);
        layer.mulPose(Axis.ZP.rotationDegrees(120));
        layer.scale(0.2F, 0.2F, 0.2F);
        layer.translate(0, -1, 0);

        assertPoseEquals(muzzle, shot.snapshot(muzzle));
    }

    private static void assertPoseEquals(PoseStack expected, PoseStack actual) {
        assertTrue(expected.last().pose().equals(actual.last().pose(), 1.0e-6F));
        assertTrue(expected.last().normal().equals(actual.last().normal(), 1.0e-6F));
    }
}
