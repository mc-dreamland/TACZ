package com.tacz.guns.compat.firstperson;

import com.tacz.guns.api.client.other.KeepingItemRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

public final class FirstPersonAnimationCompat {
    private FirstPersonAnimationCompat() {
    }


    public static ItemStack getMainRenderStack(LocalPlayer player) {
        ItemStack kept = KeepingItemRenderer.getRenderer().getCurrentItem();
        return com.tacz.guns.client.paper.GunResolver.renderStack(kept.isEmpty() ? player.getMainHandItem() : kept);
    }


    public static boolean isTaczViewmodel(ItemStack stack) {
        return com.tacz.guns.client.renderer.item.BuiltinItemRendererRegistry.INSTANCE.get(com.tacz.guns.client.paper.GunResolver.renderStack(stack).getItem())
                instanceof com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer<?, ?>;
    }
}

