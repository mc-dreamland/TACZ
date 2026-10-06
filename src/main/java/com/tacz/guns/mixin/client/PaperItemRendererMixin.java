package com.tacz.guns.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.paper.GunResolver;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

@Mixin(ItemRenderer.class)
public abstract class PaperItemRendererMixin {
    @Shadow public abstract BakedModel getModel(ItemStack stack, @Nullable Level level, @Nullable LivingEntity entity, int seed);
    @Shadow public abstract void render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers, int light, int overlay, BakedModel model);

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void tacz$renderPaperItem(ItemStack real, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        ItemStack view = GunResolver.renderStack(real);
        if (view == real || view.isEmpty()) return;
        render(view, context, leftHand, pose, buffers, light, overlay, getModel(view, null, null, 0));
        ci.cancel();
    }
}
