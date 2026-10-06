package com.tacz.guns.client.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.other.ThirdPersonManager;
import com.tacz.guns.client.gui.overlay.GunHudOverlay;
import com.tacz.guns.client.gui.overlay.HeatBarOverlay;
import com.tacz.guns.client.gui.overlay.InteractKeyTextOverlay;
import com.tacz.guns.client.gui.overlay.KillAmountOverlay;
import com.tacz.guns.client.gui.preview.GunPreviewRenderState;
import com.tacz.guns.client.gui.preview.GunPreviewRenderer;
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
import com.tacz.guns.client.input.TaCZKeyCategory;
import com.tacz.guns.client.input.ZoomKey;
import com.tacz.guns.client.renderer.item.AmmoBoxStatueProperty;
import com.tacz.guns.client.renderer.item.AmmoItemRenderer;
import com.tacz.guns.client.renderer.item.AttachmentItemRenderer;
import com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import com.tacz.guns.client.renderer.item.GunSmithTableItemRenderer;
import com.tacz.guns.client.renderer.item.TaczDynamicItemModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.tooltip.ClientAmmoBoxTooltip;
import com.tacz.guns.client.tooltip.ClientAttachmentItemTooltip;
import com.tacz.guns.client.tooltip.ClientBlockItemTooltip;
import com.tacz.guns.client.tooltip.ClientGunTooltip;
import com.tacz.guns.compat.controllable.ControllableCompat;
import com.tacz.guns.compat.firstperson.FirstPersonAnimationCompat;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.guns.compat.shouldersurfing.ShoulderSurfingCompat;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.inventory.tooltip.AmmoBoxTooltip;
import com.tacz.guns.inventory.tooltip.AttachmentItemTooltip;
import com.tacz.guns.inventory.tooltip.BlockItemTooltip;
import com.tacz.guns.inventory.tooltip.GunTooltip;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * IModBusEvent handlers. 26.1 EventBusSubscriber has no Bus enum; MDK 26.1.2 still
 * delivers IModBusEvent to {@code @EventBusSubscriber(modid, Dist.CLIENT)}.
 * Evidence: official MDK ExampleModClient {@code FMLClientSetupEvent};
 * NeoForge 26.1.2.97 sources: RegisterKeyMappingsEvent / AddClientReloadListenersEvent
 * implement IModBusEvent.
 */
@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public class ClientSetupEvent {
    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(TaCZKeyCategory.TACZ);
        event.register(InspectKey.INSPECT_KEY);
        event.register(ReloadKey.RELOAD_KEY);
        event.register(ShootKey.SHOOT_KEY);
        event.register(InteractKey.INTERACT_KEY);
        event.register(FireSelectKey.FIRE_SELECT_KEY);
        event.register(AimKey.AIM_KEY);
        event.register(CrawlKey.CRAWL_KEY);
        event.register(RefitKey.REFIT_KEY);
        event.register(ZoomKey.ZOOM_KEY);
        event.register(MeleeKey.MELEE_KEY);
        event.register(ConfigKey.OPEN_CONFIG_KEY);
    }

    @SubscribeEvent
    public static void onRegisterTooltips(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(GunTooltip.class, ClientGunTooltip::new);
        event.register(AmmoBoxTooltip.class, ClientAmmoBoxTooltip::new);
        event.register(AttachmentItemTooltip.class, ClientAttachmentItemTooltip::new);
        event.register(BlockItemTooltip.class, ClientBlockItemTooltip::new);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(id("gun_hud"), (graphics, delta) ->
                GunHudOverlay.render(graphics, delta.getRealtimeDeltaTicks()));
        event.registerAboveAll(id("heat_bar"), (graphics, delta) ->
                HeatBarOverlay.render(graphics, delta.getRealtimeDeltaTicks()));
        event.registerAboveAll(id("kill_amount"), (graphics, delta) ->
                KillAmountOverlay.render(graphics, delta.getRealtimeDeltaTicks()));
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, id("interact_key_text"), (graphics, delta) ->
                InteractKeyTextOverlay.render(graphics, delta.getRealtimeDeltaTicks()));
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, id("crosshair_hit"), (graphics, delta) ->
                com.tacz.guns.client.event.RenderCrosshairEvent.onRenderOverlay(
                        graphics, net.minecraft.client.Minecraft.getInstance().getWindow()));
    }

    @SubscribeEvent
    public static void onRegisterItemModels(RegisterItemModelsEvent event) {
        event.register(TaczDynamicItemModel.TYPE_ID, TaczDynamicItemModel.Unbaked.MAP_CODEC);
    }

    @SubscribeEvent
    public static void onRegisterSelectProperties(RegisterSelectItemModelPropertyEvent event) {
        event.register(AmmoBoxStatueProperty.ID, AmmoBoxStatueProperty.TYPE);
    }

    @SubscribeEvent
    public static void onRegisterPip(RegisterPictureInPictureRenderersEvent event) {
        event.register(GunPreviewRenderState.class, GunPreviewRenderer::new);
    }

    @SubscribeEvent
    public static void onClientResourceReload(AddClientReloadListenersEvent event) {
        PlayerAnimatorCompat.init();
        PlayerAnimatorCompat.registerReloadListener(event::addListener);
        ClientAssetsManager.INSTANCE.reloadAndRegister(event);
    }

    @SubscribeEvent
    public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
        com.tacz.guns.client.event.ReloadResourceEvent.onTextureAtlasStitched(event);
    }

    public static void onClientSetup() {
        ThirdPersonManager.registerDefault();
        ShoulderSurfingCompat.init();
        ControllableCompat.init();
    }

    public static void registerItemRenderers() {
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.MODERN_KINETIC_GUN.get(), GunItemRendererWrapper.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.AMMO.get(), AmmoItemRenderer.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.ATTACHMENT.get(), AttachmentItemRenderer.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.GUN_SMITH_TABLE.get(), GunSmithTableItemRenderer.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.WORKBENCH_111.get(), GunSmithTableItemRenderer.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.WORKBENCH_211.get(), GunSmithTableItemRenderer.INSTANCE.get());
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.WORKBENCH_121.get(), GunSmithTableItemRenderer.INSTANCE.get());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, path);
    }
}
