package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tacz.guns.client.render.scope.ScopeDepthCopyState;
import com.tacz.guns.client.render.scope.ScopeRenderTypes;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the scope depth backup, ocular aperture copy, world-depth restore and reticle mask binding
 * after vanilla/Iris bind the real destination FBO and before glDraw*.
 *
 * <p>1.21.11 port: {@code DepthTestFunction} has no ALWAYS, so reticle pipelines declare
 * {@code NO_DEPTH_TEST} and this mixin re-enables the depth test with {@code GL_ALWAYS}
 * right before the draw (semantic source: Fabric 1.21.11 sibling mixin).</p>
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderScopeDepthCopyMixin {
    @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true, require = 1)
    private void tacz$copyScopeDepth(@Coerce Object glRenderPass,
                                     int baseVertex,
                                     int firstIndex,
                                     int indexCount,
                                     VertexFormat.IndexType indexType,
                                     GlRenderPipeline glRenderPipeline,
                                     int instanceCount,
                                     CallbackInfo ci) {
        if (!ScopeDepthCopyState.beforeDraw()) {
            ci.cancel();
            return;
        }
        tacz$forceAlwaysDepthIfNeeded(glRenderPipeline);
    }

    /**
     * 1.21.11 的 {@code DepthTestFunction} 没有 ALWAYS；reticle 因而先声明为
     * {@code NO_DEPTH_TEST}，再在 vanilla 应用完管线状态后、真正 {@code glDraw*}
     * 前改回「深度测试开启 + GL_ALWAYS」。
     * <p>
     * 这里只补深度<b>测试</b>状态，绝不手工调用 {@code _depthMask}。常规 reticle
     * pipeline 刻意声明 {@code depthWrite=false}——写入掩码由 pipeline 决定，vanilla
     * 已按各自 pipeline 设置 glDepthMask，这里绝不能一刀切覆盖它。
     * <p>
     * 此逻辑只对白名单中的 TACZ scope 前景管线生效
     * （{@link ScopeRenderTypes#needsForcedAlwaysDepth}）。
     */
    @Unique
    private static void tacz$forceAlwaysDepthIfNeeded(GlRenderPipeline glRenderPipeline) {
        if (glRenderPipeline == null
                || !ScopeRenderTypes.needsForcedAlwaysDepth(glRenderPipeline.info())) {
            return;
        }
        // 顺序要紧: 先 enable 再设函数。vanilla 对 NO_DEPTH_TEST 走的是 _disableDepthTest(),
        // 这里重新打开深度测试并把比较函数设成恒通过。
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_ALWAYS);
    }
}
