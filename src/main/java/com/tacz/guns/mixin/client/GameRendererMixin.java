package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.event.RenderItemInHandBobEvent;
import com.tacz.guns.api.client.event.RenderLevelBobEvent;
import com.tacz.guns.client.renderer.other.GunHurtBobTweak;
import com.tacz.guns.compat.shader.ShaderCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.11 bob hooks. RenderFrameEvent is already fired by NeoForge ClientHooks. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;

    @Unique
    private boolean tacz$renderingItemInHand;

    // 1.21.11: renderItemInHand(float partialTick, boolean renderHand, Matrix4f projection)
    // 26.1 的签名是 (CameraRenderState, float, Matrix4fc)——多了 state、少了 boolean、
    // 且是 Matrix4fc 接口而非 Matrix4f 实现类。三处都要跟着改（javap 核实，
    // 语义来源：姊妹项目 1.21.11 分支同款修正）。
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void tacz$beginHandPass(float partialTick,
                                    boolean renderHand,
                                    Matrix4f projection,
                                    CallbackInfo ci) {
        this.tacz$renderingItemInHand = true;
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void tacz$endHandPass(float partialTick,
                                  boolean renderHand,
                                  Matrix4f projection,
                                  CallbackInfo ci) {
        this.tacz$renderingItemInHand = false;
    }

    @Unique
    private boolean tacz$isItemInHandBobPass() {
        return this.tacz$renderingItemInHand || ShaderCompat.isHandRendererActive();
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    // 1.21.11: bobHurt(PoseStack, float partialTick)；26.1 是 (CameraRenderState, PoseStack)。
    // partialTick 由形参直接给出，比原先从 DeltaTracker 现取更准。
    private void tacz$bobHurt(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (minecraft.getCameraEntity() instanceof LocalPlayer player && !player.isDeadOrDying()) {
            if (GunHurtBobTweak.onHurtBobTweak(player, poseStack, partialTick)) {
                ci.cancel();
                return;
            }
        }

        if (this.tacz$isItemInHandBobPass()) {
            RenderItemInHandBobEvent.BobHurt event = new RenderItemInHandBobEvent.BobHurt();
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                ci.cancel();
            }
        } else {
            RenderLevelBobEvent.BobHurt event = new RenderLevelBobEvent.BobHurt();
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    // 1.21.11: bobView(PoseStack, float partialTick)；26.1 是 (CameraRenderState, PoseStack)。
    private void tacz$bobView(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (this.tacz$isItemInHandBobPass()) {
            RenderItemInHandBobEvent.BobView event = new RenderItemInHandBobEvent.BobView();
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                ci.cancel();
            }
        } else {
            RenderLevelBobEvent.BobView event = new RenderLevelBobEvent.BobView();
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                ci.cancel();
            }
        }
    }
}
