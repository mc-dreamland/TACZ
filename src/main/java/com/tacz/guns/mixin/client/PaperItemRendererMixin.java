package com.tacz.guns.mixin.client;

import com.tacz.guns.client.paper.GunResolver;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Resolve Paper item data before selecting the client item model in every display context. */
@Mixin(ItemModelResolver.class)
public abstract class PaperItemRendererMixin {
    @ModifyVariable(method = "appendItemLayers", at = @At("HEAD"), argsOnly = true)
    private ItemStack tacz$resolvePaperModel(ItemStack stack) {
        return GunResolver.renderStack(stack);
    }
}
