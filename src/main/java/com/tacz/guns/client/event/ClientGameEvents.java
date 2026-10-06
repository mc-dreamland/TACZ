package com.tacz.guns.client.event;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.event.BeforeRenderHandEvent;
import com.tacz.guns.api.client.event.RenderItemInHandBobEvent;
import com.tacz.guns.api.client.event.SwapItemWithOffHand;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.client.animation.screen.RefitTransform;
import com.tacz.guns.client.input.AimKey;
import com.tacz.guns.client.input.ConfigKey;
import com.tacz.guns.client.input.CrawlKey;
import com.tacz.guns.client.input.FireSelectKey;
import com.tacz.guns.client.input.InspectKey;
import com.tacz.guns.client.input.InteractKey;
import com.tacz.guns.client.input.MeleeKey;
import com.tacz.guns.client.input.RefitKey;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.client.input.ShootKey;
import com.tacz.guns.client.input.ZoomKey;
import com.tacz.guns.client.sound.SoundPlayManager;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Game-bus client listeners. Evidence: NeoForge 26.1.2.97
 * ClientTickEvent / RenderFrameEvent / ViewportEvent / InputEvent.
 */
@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        InventoryEvent.onPlayerChangeSelect(mc, false);
        RefreshClonePlayerDataEvent.onClientTick(mc);
        TickAnimationEvent.tickAnimation(mc);
        AimKey.onAimHoldingPreInput(mc);
        ShootKey.autoShoot(mc, false);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        InventoryEvent.onPlayerChangeSelect(mc, true);
        TickAnimationEvent.tickAnimation(mc);
        AimKey.onAimHoldingPreInput(mc);
        AimKey.cancelAim(mc);
        ShootKey.autoShoot(mc, true);
        SoundPlayManager.onClientTick(mc);
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        RefitTransform.tickInterpolation(event);
        TickAnimationEvent.tickAnimation(event);
        RenderCrosshairEvent.onRenderTick(event);
    }

    @SubscribeEvent
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        // NeoForge applies hideGui only to vanilla layers, so custom HUD layers need it too.
        if (GunMod.MOD_ID.equals(event.getName().getNamespace()) && Minecraft.getInstance().options.hideGui) {
            event.setCanceled(true);
            return;
        }
        if (VanillaGuiLayers.CROSSHAIR.equals(event.getName())
                && RenderCrosshairEvent.shouldHideVanillaCrosshair()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        ConfigKey.onOpenConfig(event);
        CrawlKey.onCrawlPress(event);
        FireSelectKey.onFireSelectKeyPress(event);
        InspectKey.onInspectPress(event);
        InteractKey.onInteractKeyPress(event);
        MeleeKey.onMeleeKeyPress(event);
        RefitKey.onRefitPress(event);
        ReloadKey.onReloadPress(event);
        ZoomKey.onZoomKeyPress(event);
    }

    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Post event) {
        AimKey.onAimPress(event);
        FireSelectKey.onFireSelectMousePress(event);
        InteractKey.onInteractMousePress(event);
        MeleeKey.onMeleeMousePress(event);
        ZoomKey.onZoomMousePress(event);
    }

    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        ClientPreventGunClick.onClickInput(event);
    }

    @SubscribeEvent
    public static void onComputeCamera(ViewportEvent.ComputeCameraAngles event) {
        CameraSetupEvent.applyLevelCameraAnimation(event);
        CameraSetupEvent.applyCameraRecoil(event);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        CameraSetupEvent.applyScopeMagnification(event);
        CameraSetupEvent.applyGunModelFovModifying(event);
    }

    @SubscribeEvent
    public static void onComputeMovementFov(ComputeFovModifierEvent event) {
        CameraSetupEvent.onComputeMovementFov(event);
    }

    @SubscribeEvent
    public static void onBeforeHand(BeforeRenderHandEvent event) {
        CameraSetupEvent.applyItemInHandCameraAnimation(event);
    }

    @SubscribeEvent
    public static void onHandBobView(RenderItemInHandBobEvent.BobView event) {
        FirstPersonRenderGunEvent.cancelItemInHandViewBobbing(event);
    }

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        CameraSetupEvent.initialCameraRecoil(event);
        FirstPersonRenderGunEvent.onGunFire(event);
    }

    @SubscribeEvent
    public static void onEntityHurt(EntityHurtByGunEvent.Post event) {
        ClientHitMark.onEntityHurt(event);
        PlayerHurtByGunEvent.onPlayerHurtByGun(event);
    }

    @SubscribeEvent
    public static void onEntityKill(EntityKillByGunEvent event) {
        ClientHitMark.onEntityKill(event);
    }

    @SubscribeEvent
    public static void onSwap(SwapItemWithOffHand event) {
        InventoryEvent.onPlayerSwapMainHand(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        InventoryEvent.onPlayerLoggedOut(event);
        CommonNetworkCacheEvent.onClientPlayerLoggingOut(event);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        PlayerEnterWorld.onPlayerEnterWorld(event);
    }

    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        ReloadKey.autoReload(event);
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Post<?, ?, ?> event) {
        RenderHeadShotAABB.onRenderEntity(event);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        TooltipEvent.onTooltip(event.getItemStack(), event.getFlags(), event.getToolTip());
    }
}
