package com.tacz.guns.util;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;

public final class RenderHelper {
    private RenderHelper() {
    }

    public static void renderFirstPersonArm(LocalPlayer player, HumanoidArm arm, PoseStack poseStack,
                                            SubmitNodeCollector collector, int light) {
        if (player == null || collector == null) {
            return;
        }
        EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof AvatarRenderer<?> avatar)) {
            return;
        }
        boolean slim = player.getSkin().model() == PlayerModelType.SLIM;
        var texture = player.getSkin().body().texturePath();
        if (arm == HumanoidArm.RIGHT) {
            avatar.renderRightHand(poseStack, collector, light, texture, slim, player);
        } else {
            avatar.renderLeftHand(poseStack, collector, light, texture, slim, player);
        }
    }

}
