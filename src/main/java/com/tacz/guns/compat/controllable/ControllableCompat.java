package com.tacz.guns.compat.controllable;

import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/** Optional Controllable 0.25.4 integration for Minecraft 1.21.10. */
public class ControllableCompat {
    private static final String MOD_ID = "controllable";
    private static volatile boolean installed;

    public static void init() {
        installed = ModList.get().isLoaded(MOD_ID);
        if (installed) {
            ControllableInner.init();
        }
    }

    public static void onGunShoot(ItemStack gunItem, FireMode fireMode) {
        if (installed) {
            ControllableInner.rumbleShoot(gunItem, fireMode);
        }
    }
}
