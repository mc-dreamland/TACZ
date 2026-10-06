package com.tacz.guns.client.renderer.other;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** Held stacks captured alongside vanilla's item render states. */
public interface TaczArmedRenderState {
    ItemStack tacz$getMainHandStack();

    ItemStack tacz$getOffHandStack();

    void tacz$setHeldStacks(ItemStack mainHand, ItemStack offHand);
}
