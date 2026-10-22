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
            Class.forName("com.github.exopandora.shouldersurfing.api.client.Perspective", false,
                    ShoulderSurfingCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            // The 1.21.11 integration uses Shoulder Surfing's 5.x event API.
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
