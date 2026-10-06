package com.tacz.guns.api.item.nbt;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.IBlock;
import com.tacz.guns.util.ItemNbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

public interface BlockItemDataAccessor extends IBlock {
    String BLOCK_ID = "BlockId";

    @Override
    @Nonnull
    default ResourceLocation getBlockId(ItemStack block) {
        CompoundTag nbt = ItemNbtUtils.getTag(block);
        if (nbt.contains(BLOCK_ID)) {
            ResourceLocation gunId = ResourceLocation.tryParse(nbt.getStringOr(BLOCK_ID, ""));
            return Objects.requireNonNullElse(gunId, DefaultAssets.EMPTY_BLOCK_ID);
        }
        return DefaultAssets.EMPTY_BLOCK_ID;
    }

    @Override
    default void setBlockId(ItemStack block, @Nullable ResourceLocation blockId) {
        ItemNbtUtils.updateTag(block, nbt -> {
            if (blockId != null) {
                nbt.putString(BLOCK_ID, blockId.toString());
            } else {
                nbt.putString(BLOCK_ID, DefaultAssets.EMPTY_BLOCK_ID.toString());
            }
        });
    }

}
