package com.tacz.guns.compat.shouldersurfing;

import net.neoforged.fml.ModList;

public final class ShoulderSurfingCompat {
    private static final String MOD_ID = "shouldersurfing";
    private static boolean INSTALLED = false;

    private ShoulderSurfingCompat() {
    }

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID) && hasSupportedApi();
    }

    private static boolean hasSupportedApi() {
        try {
            Class.forName("com.github.exopandora.shouldersurfing.api.model.Perspective", false,
                    ShoulderSurfingCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            // Shoulder Surfing 4.x is the API published for Minecraft 1.21.10.
            return false;
        }
    }

    public static boolean showCrosshair() {
        if (INSTALLED) {
            return ShoulderSurfingCompatInner.showCrosshair();
        }
        return false;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
}
