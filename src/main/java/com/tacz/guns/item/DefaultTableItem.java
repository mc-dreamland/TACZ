package com.tacz.guns.item;

import com.tacz.guns.GunMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultTableItem extends GunSmithTableItem {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "gun_smith_table");

    public DefaultTableItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    @NotNull
    public ResourceLocation getBlockId(ItemStack block) {
        return ID;
    }

    @Override
    public void setBlockId(ItemStack block, @Nullable ResourceLocation blockId) {
        // 默认块的id无效，锁定为"tacz:gun_smith_table"
    }
}
