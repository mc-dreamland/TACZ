package com.tacz.guns.compat.jei;

import com.tacz.guns.api.item.ItemBehavior;

import com.tacz.guns.api.item.*;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import net.minecraft.world.item.ItemStack;

public class GunModSubtype {
    public static ISubtypeInterpreter<ItemStack> getAmmoSubtype() {
        return (stack, context) -> {
            if (ItemBehavior.of(stack) instanceof IAmmo iAmmo) {
                return iAmmo.getAmmoId(stack).toString();
            }
            return null;
        };
    }

    public static ISubtypeInterpreter<ItemStack> getGunSubtype() {
        return (stack, context) -> {
            if (ItemBehavior.of(stack) instanceof IGun iGun) {
                return iGun.getGunId(stack).toString();
            }
            return null;
        };
    }

    public static ISubtypeInterpreter<ItemStack> getAttachmentSubtype() {
        return (stack, context) -> {
            if (ItemBehavior.of(stack) instanceof IAttachment iAttachment) {
                return iAttachment.getAttachmentId(stack).toString();
            }
            return null;
        };
    }

    public static ISubtypeInterpreter<ItemStack> getTableSubType() {
        return (stack, context) -> {
            if (ItemBehavior.of(stack) instanceof IBlock iBlock) {
                return iBlock.getBlockId(stack).toString();
            }
            return null;
        };
    }


    public static ISubtypeInterpreter<ItemStack> getAmmoBoxSubtype() {
        return (stack, context) -> {
            if (ItemBehavior.of(stack) instanceof IAmmoBox iAmmoBox) {
                if (iAmmoBox.isAllTypeCreative(stack)) {
                    return "all_type_creative";
                }
                if (iAmmoBox.isCreative(stack)) {
                    return "creative";
                }
                return String.format("level_%d", iAmmoBox.getAmmoLevel(stack));
            }
            return null;
        };
    }
}
