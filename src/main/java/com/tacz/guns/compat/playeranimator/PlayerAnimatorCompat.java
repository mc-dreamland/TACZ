package com.tacz.guns.compat.playeranimator;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.compat.playeranimator.pal.PalAnimationManager;
import com.tacz.guns.compat.playeranimator.pal.PalAssetManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Optional Player Animation Library 1.1.3 integration for Minecraft 1.21.10. */
public final class PlayerAnimatorCompat {
    public static final ResourceLocation LOWER_ANIMATION = ResourceLocation.fromNamespaceAndPath("tacz", "lower_animation");
    public static final ResourceLocation LOOP_UPPER_ANIMATION = ResourceLocation.fromNamespaceAndPath("tacz", "loop_upper_animation");
    public static final ResourceLocation ONCE_UPPER_ANIMATION = ResourceLocation.fromNamespaceAndPath("tacz", "once_upper_animation");
    public static final ResourceLocation ROTATION_ANIMATION = ResourceLocation.fromNamespaceAndPath("tacz", "rotation");

    private static final String PAL = "player_animation_library";
    private static boolean installed;

    private PlayerAnimatorCompat() {
    }

    public static void init() {
        installed = ModList.get().isLoaded(PAL);
        GunMod.LOGGER.info("[TACZ PAL] init: installed={} (modid={})", installed, PAL);
        if (installed) {
            PalAnimationManager.init();
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** 已报告过 miss 原因的 display id —— 每个只打一条，避免每帧刷屏。 */
    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();

    public static boolean hasPlayerAnimator3rd(LivingEntity livingEntity, GunDisplayInstance display) {
        if (!installed) {
            if (REPORTED.add(ResourceLocation.fromNamespaceAndPath("tacz", "not_installed"))) {
                GunMod.LOGGER.warn("[TACZ PAL] compat inactive: modid '{}' is not loaded", PAL);
            }
            return false;
        }
        if (!(livingEntity instanceof AbstractClientPlayer)) {
            return false;
        }
        // 诊断：明确指出每个 display 走不进 PAL 分支的原因（每 id 一次）。
        var fileId = display.getPlayerAnimator3rd();
        if (fileId == null) {
            if (REPORTED.add(display.getDisplayId())) {
                GunMod.LOGGER.info("[TACZ PAL] display {} has no player_animator_3rd data (vanilla third-person animation used)", display.getDisplayId());
            }
            return false;
        }
        if (!PalAnimationManager.hasAnimations(display)) {
            if (REPORTED.add(fileId)) {
                GunMod.LOGGER.warn("[TACZ PAL] animation file {} is NOT loaded (expected as assets/{}/player_animator/{}.json in a gun pack)", fileId, fileId.getNamespace(), fileId.getPath());
            }
            return false;
        }
        return true;
    }

    public static void playAnimation(LivingEntity livingEntity, GunDisplayInstance display, float limbSwingAmount) {
        if (installed && livingEntity instanceof AbstractClientPlayer player) {
            PalAnimationManager.play(player, display, limbSwingAmount);
        }
    }

    public static void stopAllAnimation(LivingEntity livingEntity) {
        stopAllAnimation(livingEntity, 8);
    }

    public static void stopAllAnimation(LivingEntity livingEntity, int fadeTime) {
        if (installed && livingEntity instanceof AbstractClientPlayer player) {
            PalAnimationManager.stopAll(player, fadeTime);
        }
    }

    /**
     * Registers the PAL asset reload listener onto the client reload event.
     * Pass {@code event::addListener} from
     * {@code AddClientReloadListenersEvent#addListener(ResourceLocation, PreparableReloadListener)}.
     */
    public static void registerReloadListener(BiConsumer<ResourceLocation, PreparableReloadListener> register) {
        if (installed) {
            GunMod.LOGGER.info("[TACZ PAL] reload listener registered as {}", PalAssetManager.ID);
            register.accept(PalAssetManager.ID, PalAssetManager.INSTANCE);
        }
    }
}
