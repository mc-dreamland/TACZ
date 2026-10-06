package com.tacz.guns.mixin.client;

import com.tacz.guns.client.renderer.other.TaczArmedRenderState;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmedEntityRenderState.class)
public class ArmedEntityRenderStateMixin implements TaczArmedRenderState {
    @Unique private ItemStack tacz$mainHand = ItemStack.EMPTY;
    @Unique private ItemStack tacz$offHand = ItemStack.EMPTY;

    @Inject(method = "extractArmedEntityRenderState", at = @At("TAIL"))
    private static void tacz$captureStacks(LivingEntity entity, ArmedEntityRenderState state,
                                          ItemModelResolver resolver, CallbackInfo ci) {
        ((TaczArmedRenderState) state).tacz$setHeldStacks(entity.getMainHandItem(), entity.getOffhandItem());
    }

    @Override
    public ItemStack tacz$getMainHandStack() {
        return tacz$mainHand;
    }

    @Override
    public ItemStack tacz$getOffHandStack() {
        return tacz$offHand;
    }

    @Override
    public void tacz$setHeldStacks(ItemStack mainHand, ItemStack offHand) {
        tacz$mainHand = mainHand.copy();
        tacz$offHand = offHand.copy();
    }
}
