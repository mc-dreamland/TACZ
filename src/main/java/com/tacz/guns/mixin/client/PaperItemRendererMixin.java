package com.tacz.guns.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tacz.guns.client.paper.GunResolver;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Resolve Paper item data before selecting the client item model in every display context. */
@Mixin(ItemModelResolver.class)
public abstract class PaperItemRendererMixin {
    @WrapMethod(method = "appendItemLayers")
    private void tacz$resolvePaperModel(ItemStackRenderState state, ItemStack stack,
                                        ItemDisplayContext context, Level level, ItemOwner owner, int seed,
                                        Operation<Void> original) {
        // CIT can render and cancel appendItemLayers in a HEAD injection before a
        // ModifyVariable at the same entry point runs. Wrap the complete method so
        // every model selector receives the native view, including cancelling injectors.
        original.call(state, GunResolver.renderStack(stack), context, level, owner, seed);
    }
}
