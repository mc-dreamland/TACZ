package com.tacz.guns.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.config.client.RenderConfig;
import org.joml.Matrix4f;

public final class RenderDistance {
    private static long guiRenderTimestamp;

    private RenderDistance() {
    }

    public static void markGuiRenderTimestamp() {
        guiRenderTimestamp = System.currentTimeMillis();
    }

    public static boolean inRenderHighPolyModelDistance(PoseStack poseStack) {
        if (System.currentTimeMillis() - guiRenderTimestamp < 500L) {
            return true;
        }
        int dist = RenderConfig.GUN_LOD_RENDER_DISTANCE.get();
        if (dist <= 0) {
            return true;
        }
        Matrix4f m = poseStack.last().pose();
        float x = m.m30();
        float y = m.m31();
        float z = m.m32();
        return (x * x + y * y + z * z) < (float) dist * dist;
    }
}
