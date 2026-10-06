package com.tacz.guns.util;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.tacz.guns.GunMod;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.function.Consumer;

/** Component-backed item data and nested attachment serialization for Minecraft 1.21.10. */
public final class ItemNbtUtils {
    private static volatile net.minecraft.core.HolderLookup.Provider lookupProvider;

    private ItemNbtUtils() {
    }

    /** Updated on both server and client when their dynamic registries and tags are ready. */
    public static void setLookupProvider(net.minecraft.core.HolderLookup.Provider provider) {
        lookupProvider = java.util.Objects.requireNonNull(provider);
    }

    public static net.minecraft.core.HolderLookup.Provider getLookupProvider() {
        var current = lookupProvider;
        return current != null ? current : RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static RegistryOps<Tag> getOps() {
        return RegistryOps.create(NbtOps.INSTANCE, getLookupProvider());
    }

    /**
     * Get a copy of the item's custom data tag. Returns an empty CompoundTag if none exists.
     */
    public static CompoundTag getTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && !data.isEmpty()) {
            return data.copyTag();
        }
        return new CompoundTag();
    }

    /**
     * Update the item's custom data tag in-place.
     */
    public static void updateTag(ItemStack stack, Consumer<CompoundTag> consumer) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, consumer);
    }

    /** Uses the optional codec so removing an attachment can persist an empty stack. */
    public static CompoundTag saveItemStack(ItemStack stack) {
        DataResult<Tag> result = ItemStack.OPTIONAL_CODEC.encodeStart(getOps(), stack);
        return (CompoundTag) result.getOrThrow();
    }

    /** Malformed or empty attachment data resolves to an empty stack. */
    public static ItemStack loadItemStack(CompoundTag tag) {
        CompoundTag serialized = tag;
        if (tag.contains("Count") || (tag.contains("tag") && !tag.contains("components"))) {
            // Vanilla only data-fixes the containing gun stack. Attachments inside custom_data
            // are opaque to it, so upgrade their complete legacy ItemStack here as well.
            try {
                serialized = (CompoundTag) DataFixers.getDataFixer().update(References.ITEM_STACK,
                        new Dynamic<>(NbtOps.INSTANCE, tag.copy()), 3465,
                        SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
            } catch (RuntimeException exception) {
                GunMod.LOGGER.error("Failed to upgrade a legacy nested attachment; original data was retained", exception);
                return ItemStack.EMPTY;
            }
        }
        DataResult<ItemStack> result = ItemStack.OPTIONAL_CODEC.parse(getOps(), serialized);
        result.error().ifPresent(error -> GunMod.LOGGER.error("Invalid nested attachment data: {}", error.message()));
        return result.result().orElse(ItemStack.EMPTY);
    }
}
