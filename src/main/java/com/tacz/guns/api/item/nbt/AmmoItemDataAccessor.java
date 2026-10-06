package com.tacz.guns.api.item.nbt;

import com.tacz.guns.api.item.ItemBehavior;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.util.ItemNbtUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

public interface AmmoItemDataAccessor extends IAmmo {
    String AMMO_ID_TAG = "AmmoId";

    @Override
    @Nonnull
    default ResourceLocation getAmmoId(ItemStack ammo) {
        CompoundTag nbt = ItemNbtUtils.getTag(ammo);
        if (nbt.contains(AMMO_ID_TAG)) {
            ResourceLocation gunId = ResourceLocation.tryParse(nbt.getStringOr(AMMO_ID_TAG, ""));
            return Objects.requireNonNullElse(gunId, DefaultAssets.EMPTY_AMMO_ID);
        }
        return DefaultAssets.EMPTY_AMMO_ID;
    }

    @Override
    default void setAmmoId(ItemStack ammo, @Nullable ResourceLocation ammoId) {
        ItemNbtUtils.updateTag(ammo, nbt -> {
            if (ammoId != null) {
                nbt.putString(AMMO_ID_TAG, ammoId.toString());
            } else {
                nbt.putString(AMMO_ID_TAG, DefaultAssets.DEFAULT_AMMO_ID.toString());
            }
        });
        applyMaxStackSize(ammo);
    }

    /** Keep the per-ammunition stack limit in the component consumed by Minecraft. */
    static void applyMaxStackSize(ItemStack ammo) {
        if (!(ammo.getItem() instanceof IAmmo iAmmo)) {
            return;
        }
        TimelessAPI.getCommonAmmoIndex(iAmmo.getAmmoId(ammo))
                .map(index -> Math.clamp(index.getStackSize(), 1, 99))
                .ifPresent(size -> ammo.set(DataComponents.MAX_STACK_SIZE, size));
    }

    @Override
    default boolean isAmmoOfGun(ItemStack gun, ItemStack ammo) {
        if (ItemBehavior.of(gun) instanceof IGun iGun && ItemBehavior.of(ammo) instanceof IAmmo iAmmo) {
            ResourceLocation gunId = iGun.getGunId(gun);
            ResourceLocation ammoId = iAmmo.getAmmoId(ammo);
            return TimelessAPI.getCommonGunIndex(gunId).map(gunIndex -> gunIndex.getGunData().getAmmoId().equals(ammoId)).orElse(false);
        }
        return false;
    }
}
