package com.tacz.guns.compat.controllable;

import com.mrcrayfish.controllable.Controllable;
import com.mrcrayfish.controllable.client.binding.ButtonBinding;
import com.mrcrayfish.controllable.client.binding.context.BindingContext;
import com.mrcrayfish.controllable.client.binding.context.InGameContext;
import com.mrcrayfish.controllable.client.binding.handlers.OnPressAndReleaseHandler;
import com.mrcrayfish.controllable.client.input.Buttons;
import com.mrcrayfish.controllable.client.input.Controller;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.client.input.AimKey;
import com.tacz.guns.client.input.CrawlKey;
import com.tacz.guns.client.input.FireSelectKey;
import com.tacz.guns.client.input.InspectKey;
import com.tacz.guns.client.input.InteractKey;
import com.tacz.guns.client.input.MeleeKey;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.client.input.ShootKey;
import com.tacz.guns.client.input.ZoomKey;
import com.tacz.guns.client.resource.pojo.display.gun.ControllableData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.EnumMap;
import java.util.Optional;

/** Controllable 0.25.4 bindings and rumble integration for Minecraft 1.21.10. */
public final class ControllableInner {
    public static final BindingContext GUN_KEY_CONFLICT =
            new GunKeyConflict(ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "gun_key"));
    public static final ButtonBinding AIM = new ButtonBinding(
            Buttons.LEFT_TRIGGER, "key.tacz.aim.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> AimKey.onAimControllerPress(true)),
                    context -> AimKey.onAimControllerPress(false)));
    public static final ButtonBinding SHOOT = new ButtonBinding(
            Buttons.RIGHT_TRIGGER, "key.tacz.shoot.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(context -> gunAction(() -> {
            }), context -> true));
    public static final ButtonBinding RELOAD = new ButtonBinding(
            Buttons.B, "key.tacz.reload.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> ReloadKey.onReloadControllerPress(true)),
                    context -> ReloadKey.onReloadControllerPress(false)));
    public static final ButtonBinding MELEE = new ButtonBinding(
            Buttons.X, "key.tacz.melee.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> MeleeKey.onMeleeControllerPress(true)),
                    context -> MeleeKey.onMeleeControllerPress(false)));
    public static final ButtonBinding ZOOM = new ButtonBinding(
            Buttons.X, "key.tacz.zoom.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> ZoomKey.onZoomControllerPress(true)),
                    context -> ZoomKey.onZoomControllerPress(false)));
    public static final ButtonBinding CRAWL = new ButtonBinding(
            Buttons.LEFT_THUMB_STICK, "key.tacz.crawl.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> CrawlKey.onCrawlControllerPress(true)),
                    context -> CrawlKey.onCrawlControllerPress(false)));
    public static final ButtonBinding FIRE_SELECT = new ButtonBinding(
            Buttons.DPAD_LEFT, "key.tacz.fire_select.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> FireSelectKey.onFireSelectControllerPress(true)),
                    context -> FireSelectKey.onFireSelectControllerPress(false)));
    public static final ButtonBinding INTERACT = new ButtonBinding(
            -1, "key.tacz.interact.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> InteractKey.onInteractControllerPress(true)),
                    context -> InteractKey.onInteractControllerPress(false)));
    public static final ButtonBinding INSPECT = new ButtonBinding(
            -1, "key.tacz.inspect.desc", "key.category.tacz", GUN_KEY_CONFLICT,
            OnPressAndReleaseHandler.create(
                    context -> gunAction(() -> InspectKey.onInspectControllerPress(true)),
                    context -> InspectKey.onInspectControllerPress(false)));

    private ControllableInner() {
    }

    public static void init() {
        Controllable.getBindingRegistry().register(AIM);
        Controllable.getBindingRegistry().register(SHOOT);
        Controllable.getBindingRegistry().register(RELOAD);
        Controllable.getBindingRegistry().register(MELEE);
        Controllable.getBindingRegistry().register(CRAWL);
        Controllable.getBindingRegistry().register(ZOOM);
        Controllable.getBindingRegistry().register(FIRE_SELECT);
        Controllable.getBindingRegistry().register(INTERACT);
        Controllable.getBindingRegistry().register(INSPECT);

        // Fabric port uses ClientTickEvents.END_CLIENT_TICK; NeoForge equivalent
        // is ClientTickEvent.Post on the game event bus (same polling boundary).
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> onClientTickEnd());
    }

    private static boolean isHoldingGun() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null && IGun.getIGunOrNull(player.getMainHandItem()) != null;
    }

    private static Optional<Runnable> gunAction(Runnable action) {
        return isHoldingGun() ? Optional.of(action) : Optional.empty();
    }

    private static void onClientTickEnd() {
        if (!GUN_KEY_CONFLICT.isActive() || !isHoldingGun()) {
            return;
        }
        Controller controller = Controllable.getController();
        if (controller == null) {
            return;
        }
        ShootKey.shootControllerTick(controller.isButtonPressed(SHOOT.getButton()));
    }

    public static void rumbleShoot(ItemStack mainHandItem, FireMode fireMode) {
        Controller controller = Controllable.getController();
        if (controller == null) {
            return;
        }
        IGun iGun = IGun.getIGunOrNull(mainHandItem);
        if (iGun == null) {
            return;
        }

        TimelessAPI.getGunDisplay(mainHandItem).ifPresent(index -> {
            EnumMap<FireMode, ControllableData> data = index.getControllableData();
            if (data.containsKey(fireMode)) {
                ControllableData controllableData = data.get(fireMode);
                controller.rumble(controllableData.getLowFrequency(), controllableData.getHighFrequency(),
                        controllableData.getTimeInMs());
            } else if (fireMode == FireMode.AUTO) {
                controller.rumble(0.15F, 0.25F, 80);
            } else {
                controller.rumble(0.25F, 0.5F, 100);
            }
        });
    }

    public static final class GunKeyConflict extends InGameContext {
        private GunKeyConflict(ResourceLocation id) {
            super(id);
        }


        @Override
        public int priority() {
            return 1;
        }
    }

}
