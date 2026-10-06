package com.tacz.guns.util;

import net.minecraft.client.Minecraft;

public final class InputExtraCheck {
    private InputExtraCheck() {
    }

    public static boolean isInGame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        if (mc.screen != null) {
            return false;
        }
        return mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed();
    }
}
