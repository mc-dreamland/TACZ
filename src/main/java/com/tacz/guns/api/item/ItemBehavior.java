package com.tacz.guns.api.item;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Function;

/** Optional client adapter. The dedicated Forge server always keeps the native item behavior. */
public final class ItemBehavior {
    private static Function<ItemStack, Object> resolver = ItemStack::getItem;

    private ItemBehavior() { }

    public static Object of(ItemStack stack) {
        return stack == null ? null : resolver.apply(stack);
    }

    public static void setClientResolver(Function<ItemStack, Object> clientResolver) {
        resolver = Objects.requireNonNull(clientResolver);
    }
}
