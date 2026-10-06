package com.tacz.guns.client.compat;

import com.tacz.guns.GunMod;
import com.tacz.guns.resource.CommonAssetsManager;
import net.neoforged.fml.ModList;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Rebuilds recipe-viewer registrations after TACZ's server-authoritative gun-pack cache arrives.
 *
 * <p>JEI and REI build their categories/catalysts from {@code BlockId}-bearing workbench stacks.
 * In 26.1.2 the client receives TACZ's full custom recipe table through {@code ServerMessageSyncGunPack}.
 * Without a subsequent viewer reload, a viewer can retain categories built before the sync and collapse
 * a custom workbench into whichever generic table happened to register first.
 *
 * <p>NeoForge JEI 29.5 waits for the native {@code RecipesReceivedEvent} before its first
 * startup, which occurs after TaCZ's synchronized pack cache in the verified 26.1.2 load
 * order. JEI therefore must not trigger a second full resource reload here. REI does not
 * provide the same ordering guarantee, so its client plugin reload entry point is invoked
 * reflectively when REI is present.
 */
public final class RecipeViewerReloadBridge {
    private static boolean reloadRequested;
    private static boolean reloadInProgress;

    private RecipeViewerReloadBridge() {
    }

    /** Called after the synchronized common gun-pack cache has been installed on the client. */
    public static void requestReload() {
        // JEI's NeoForge StartEventObserver waits for RecipesReceivedEvent and starts
        // after this cache is installed. Only REI needs an explicit lightweight reload.
        if (hasRei()) {
            reloadRequested = true;
        }
    }

    /** Drops a queued refresh when leaving a server before its sync has completed. */
    public static void clear() {
        reloadRequested = false;
        reloadInProgress = false;
    }

    /** Runs on the client tick so packet ordering and initial world setup have completed first. */
    public static void tick(Minecraft client) {
        if (!reloadRequested || reloadInProgress || client.level == null || client.player == null) {
            return;
        }

        reloadRequested = false;
        reloadInProgress = true;
        int tableCount = CommonAssetsManager.get().getAllBlocks().size();
        int recipeCount = CommonAssetsManager.get().getAllTableRecipes().size();
        GunMod.LOGGER.info("[TACZ Recipe Viewer] Refreshing after gun-pack sync ({} table(s), {} recipe(s)).",
                tableCount, recipeCount);

        boolean requiresResourceFallback = hasRei() && !refreshRei();

        if (!requiresResourceFallback) {
            reloadInProgress = false;
            GunMod.LOGGER.info("[TACZ Recipe Viewer] JEI/REI refresh completed.");
            return;
        }

        GunMod.LOGGER.info("[TACZ Recipe Viewer] Using the NeoForge client resource reload to rebuild viewer registrations.");
        try {
            client.reloadResourcePacks().whenComplete((unused, throwable) -> client.execute(() -> {
                reloadInProgress = false;
                if (throwable == null) {
                    GunMod.LOGGER.info("[TACZ Recipe Viewer] Fallback client resource refresh completed.");
                } else {
                    GunMod.LOGGER.warn("[TACZ Recipe Viewer] Client resource refresh failed; recipe viewer data may be stale.",
                            throwable);
                }
            }));
        } catch (RuntimeException exception) {
            reloadInProgress = false;
            GunMod.LOGGER.warn("[TACZ Recipe Viewer] Could not start the client resource refresh.", exception);
        }
    }

    /** REI 26.1.2 rebuilds categories/displays through this all-stage plugin reload entry point. */
    private static boolean refreshRei() {
        try {
            Class<?> coreClient = Class.forName("me.shedaniel.rei.RoughlyEnoughItemsCoreClient");
            Method reload = null;
            for (Method candidate : coreClient.getMethods()) {
                if (candidate.getName().equals("reloadPlugins")
                        && Modifier.isStatic(candidate.getModifiers())
                        && candidate.getParameterCount() == 2) {
                    reload = candidate;
                    break;
                }
            }
            if (reload == null) {
                throw new NoSuchMethodException("RoughlyEnoughItemsCoreClient.reloadPlugins(_, _)");
            }
            reload.invoke(null, null, null);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            GunMod.LOGGER.debug("[TACZ Recipe Viewer] REI lightweight refresh unavailable.", exception);
            return false;
        }
    }

    private static boolean hasRei() {
        return ModList.get().isLoaded("roughlyenoughitems") || ModList.get().isLoaded("rei");
    }
}
