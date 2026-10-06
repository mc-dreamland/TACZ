package com.tacz.guns.compat.immediatelyfast;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Compatibility facade for ImmediatelyFast (upstream 26.2 semantics).
 *
 * <p>Investigated 2026-08: ImmediatelyFast does publish NeoForge 26.1.2 builds
 * (1.15.3). The old TACZ integration, however, targeted its pre-26.x public
 * HUD batching API ({@code ImmediatelyFastApi#getBatching}), which no longer
 * exists. Minecraft 26.1.2 and ImmediatelyFast now both use the
 * extracted/collector rendering path; TACZ's items are submitted normally and
 * no manual batch break is required. This no-op hook preserves call-site
 * compatibility without pretending the integration exists.</p>
 */
public final class ImmediatelyFastCompat {
    private static final String MOD_ID = "immediatelyfast";
    private static boolean installed;

    private ImmediatelyFastCompat() {
    }

    public static void init() {
        installed = ModList.get().isLoaded(MOD_ID);
    }

    public static void renderHotbarItem(ItemStack stack, boolean pre) {
        // Intentionally empty on 26.1.2. See class documentation.
    }

    public static boolean isInstalled() {
        return installed;
    }
}
