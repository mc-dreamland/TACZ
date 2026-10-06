package com.tacz.guns.crafting;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tacz.guns.init.ModRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.minecraft.resources.HolderSetCodec;

import com.mojang.serialization.Codec;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * NeoForge-native equivalent of the old Forge {@code forge:partial_nbt} ingredient.
 *
 * <p>The built-in {@code DataComponentIngredient} compares the whole custom-data component
 * as one value. That is not enough for old gun packs: their partial NBT only requires a
 * {@code GunId} while the actual gun stack also contains ammo, fire-mode and other fields.
 * This ingredient keeps the original nested partial-match semantics.</p>
 */
public final class PartialNbtIngredient implements ICustomIngredient {
    public static final MapCodec<PartialNbtIngredient> CODEC = RecordCodecBuilder.mapCodec(builder -> builder.group(
            HolderSetCodec.create(Registries.ITEM, BuiltInRegistries.ITEM.holderByNameCodec(), false)
                    .fieldOf("items").forGetter(PartialNbtIngredient::itemSet),
            CustomData.COMPOUND_TAG_CODEC.fieldOf("nbt").forGetter(PartialNbtIngredient::nbt),
            Codec.BOOL.optionalFieldOf("strict", false).forGetter(PartialNbtIngredient::strict)
    ).apply(builder, PartialNbtIngredient::new));

    private final HolderSet<Item> items;
    private final net.minecraft.nbt.CompoundTag nbt;
    private final boolean strict;

    public PartialNbtIngredient(HolderSet<Item> items, net.minecraft.nbt.CompoundTag nbt, boolean strict) {
        this.items = items;
        this.nbt = nbt.copy();
        this.strict = strict;
    }

    public static Ingredient of(ItemLike item, net.minecraft.nbt.CompoundTag nbt) {
        return new PartialNbtIngredient(
                HolderSet.direct(item.asItem().builtInRegistryHolder()), nbt, false
        ).toVanilla();
    }

    @Override
    public boolean test(ItemStack stack) {
        if (!items.contains(stack.getItemHolder())) {
            return false;
        }
        CustomData customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return false;
        }
        if (strict) {
            return customData.copyTag().equals(nbt);
        }
        return customData.matchedBy(nbt);
    }

    @Override
    public Stream<Holder<Item>> items() {
        return items.stream();
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public net.neoforged.neoforge.common.crafting.IngredientType<?> getType() {
        return ModRecipe.PARTIAL_NBT_INGREDIENT.get();
    }

    @Override
    public SlotDisplay display() {
        return new SlotDisplay.Composite(items.stream().map(item -> {
            ItemStack stack = new ItemStack(item);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(nbt.copy()));
            return (SlotDisplay) new SlotDisplay.ItemStackSlotDisplay(stack);
        }).toList());
    }

    public HolderSet<Item> itemSet() {
        return items;
    }

    public net.minecraft.nbt.CompoundTag nbt() {
        return nbt;
    }

    public boolean strict() {
        return strict;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof PartialNbtIngredient other
                && Objects.equals(items, other.items)
                && Objects.equals(nbt, other.nbt)
                && strict == other.strict;
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, nbt, strict);
    }
}
