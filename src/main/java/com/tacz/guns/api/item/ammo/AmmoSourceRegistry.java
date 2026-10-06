package com.tacz.guns.api.item.ammo;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.ItemBehavior;
import com.tacz.guns.api.item.IAmmoBox;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class AmmoSourceRegistry {
    private static final AmmoSource ENTITY_INVENTORY = new AmmoSource() {
        @Override
        public boolean hasAmmo(LivingEntity shooter, ItemStack gunItem) {
            ResourceHandler<ItemResource> inventory = shooter.getCapability(Capabilities.Item.ENTITY);
            if (inventory != null) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.getResource(slot).toStack(inventory.getAmountAsInt(slot));
                    if (isAmmo(gunItem, stack)) return true;
                }
                return false;
            }
            return AmmoSourceRegistry.hasAmmo(handler(shooter), gunItem);
        }

        @Override
        public int consumeAmmo(LivingEntity shooter, ItemStack gunItem, int requestedAmount) {
            ResourceHandler<ItemResource> inventory = shooter.getCapability(Capabilities.Item.ENTITY);
            if (inventory != null) return AmmoSourceRegistry.consumeAmmo(inventory, gunItem, requestedAmount);
            return AmmoSourceRegistry.consumeAmmo(handler(shooter), gunItem, requestedAmount);
        }
    };

    private AmmoSourceRegistry() {
    }

    private static IItemHandler handler(LivingEntity shooter) {
        if (shooter instanceof Player player) {
            return new InvWrapper(player.getInventory());
        }
        return EmptyItemHandler.INSTANCE;
    }

    public static AmmoSource getAmmoSource(LivingEntity shooter, ItemStack gunItem) {
        return ENTITY_INVENTORY;
    }

    public static boolean hasAmmo(LivingEntity shooter, ItemStack gunItem) {
        return getAmmoSource(shooter, gunItem).hasAmmo(shooter, gunItem);
    }

    public static int consumeAmmo(LivingEntity shooter, ItemStack gunItem, int requestedAmount) {
        if (requestedAmount <= 0) {
            return 0;
        }
        int consumed = getAmmoSource(shooter, gunItem).consumeAmmo(shooter, gunItem, requestedAmount);
        return Math.max(0, Math.min(consumed, requestedAmount));
    }

    public static boolean hasAmmo(IItemHandler itemHandler, ItemStack gunItem) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack ammoStack = itemHandler.getStackInSlot(i);
            if (ItemBehavior.of(ammoStack) instanceof IAmmo ammo && ammo.isAmmoOfGun(gunItem, ammoStack)) {
                return true;
            }
            if (ItemBehavior.of(ammoStack) instanceof IAmmoBox ammoBox && ammoBox.isAmmoBoxOfGun(gunItem, ammoStack)) {
                return true;
            }
        }
        return false;
    }

    public static int consumeAmmo(IItemHandler itemHandler, ItemStack gunItem, int requestedAmount) {
        if (requestedAmount <= 0) {
            return 0;
        }
        int remaining = requestedAmount;
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack ammoStack = itemHandler.getStackInSlot(i);
            if (ItemBehavior.of(ammoStack) instanceof IAmmo ammo && ammo.isAmmoOfGun(gunItem, ammoStack)) {
                ItemStack extracted = itemHandler.extractItem(i, remaining, false);
                remaining -= extracted.getCount();
                if (remaining <= 0) {
                    break;
                }
            }
            if (ItemBehavior.of(ammoStack) instanceof IAmmoBox ammoBox && ammoBox.isAmmoBoxOfGun(gunItem, ammoStack)) {
                int boxAmmoCount = ammoBox.getAmmoCount(ammoStack);
                int extractCount = Math.min(boxAmmoCount, remaining);
                int remainCount = boxAmmoCount - extractCount;
                ammoBox.setAmmoCount(ammoStack, remainCount);
                if (remainCount <= 0) {
                    ammoBox.setAmmoId(ammoStack, DefaultAssets.EMPTY_AMMO_ID);
                }
                remaining -= extractCount;
                if (remaining <= 0) {
                    break;
                }
            }
        }
        return requestedAmount - remaining;
    }

    private static boolean isAmmo(ItemStack gun, ItemStack stack) {
        return !stack.isEmpty() && (ItemBehavior.of(stack) instanceof IAmmo ammo && ammo.isAmmoOfGun(gun, stack)
                || ItemBehavior.of(stack) instanceof IAmmoBox box && box.isAmmoBoxOfGun(gun, stack));
    }

    /** Transfer API handlers return immutable resources; replace ammo-box components transactionally. */
    private static int consumeAmmo(ResourceHandler<ItemResource> inventory, ItemStack gun, int requested) {
        int remaining = requested;
        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemResource resource = inventory.getResource(slot);
            int count = inventory.getAmountAsInt(slot);
            ItemStack stack = resource.toStack(count);
            if (stack.isEmpty()) continue;
            if (ItemBehavior.of(stack) instanceof IAmmo ammo && ammo.isAmmoOfGun(gun, stack)) {
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = inventory.extract(slot, resource, remaining, transaction);
                    transaction.commit();
                    remaining -= extracted;
                }
            } else if (ItemBehavior.of(stack) instanceof IAmmoBox box && box.isAmmoBoxOfGun(gun, stack)) {
                int available = box.getAmmoCount(stack);
                int consumed = Math.min(Math.max(available, 0), remaining);
                if (consumed == 0) continue;
                box.setAmmoCount(stack, available - consumed);
                if (available == consumed) box.setAmmoId(stack, DefaultAssets.EMPTY_AMMO_ID);
                try (Transaction transaction = Transaction.openRoot()) {
                    if (inventory.extract(slot, resource, count, transaction) == count
                            && inventory.insert(slot, ItemResource.of(stack), count, transaction) == count) {
                        transaction.commit();
                        remaining -= consumed;
                    }
                }
            }
        }
        return requested - remaining;
    }
}
