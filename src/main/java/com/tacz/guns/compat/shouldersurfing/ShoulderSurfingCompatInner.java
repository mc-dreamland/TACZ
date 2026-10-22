package com.tacz.guns.compat.shouldersurfing;

import com.github.exopandora.shouldersurfing.api.client.Perspective;
import com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing;

/** Direct calls isolated behind {@link ShoulderSurfingCompat}'s installed-mod guard. */
public final class ShoulderSurfingCompatInner {
    private ShoulderSurfingCompatInner() {
    }

    public static boolean showCrosshair() {
        return Perspective.current() == Perspective.SHOULDER_SURFING
                && !IShoulderSurfing.getInstance().isFreeLooking();
    }
}
