package com.tacz.guns.bridge;

/** Shared temporary gate until the replacement gun melee animations are ready. */
public final class GunMeleePolicy {
    private GunMeleePolicy() { }

    public static boolean enabled() {
        return false;
    }
}
