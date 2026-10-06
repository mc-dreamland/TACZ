package com.tacz.guns.compat.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.tacz.guns.compat.iris.IrisCompat;
import net.minecraft.world.item.ItemStack;

/**
 * Work package ⑥ shader-pack facade.
 *
 * <p>26.1.2 talks to Iris only through this type / {@link IrisCompat} (public API + optional
 * HAND-fragment mixin). 26.2 Aperture backends should implement the same methods without
 * changing callers in {@code ScopeRenderTypes} / first-person mixins.</p>
 */
public final class ShaderCompat {
    private ShaderCompat() {
    }

    public static boolean isShaderPackInUse() {
        return IrisCompat.isUsingRenderPack();
    }

    public static boolean isHandRendererActive() {
        return IrisCompat.isHandRendererActive();
    }

    public static boolean isRenderingShadow() {
        return IrisCompat.isRenderShadow();
    }

    public static boolean assignPipeline(RenderPipeline pipeline, String program, String debugName) {
        return IrisCompat.assignPipelineToIris(pipeline, program, debugName);
    }

    public static void assignCommonEntityPipelinesToHandIfNeeded() {
        IrisCompat.assignCommonEntityPipelinesToHandIfNeeded();
    }

    public static boolean shouldRenderInCurrentHandPhase(ItemStack stack) {
        return IrisCompat.shouldRenderInCurrentHandPhase(stack);
    }
}
