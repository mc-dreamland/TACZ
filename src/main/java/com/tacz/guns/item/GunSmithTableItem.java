package com.tacz.guns.item;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.BlockItemBuilder;
import com.tacz.guns.api.item.nbt.BlockItemDataAccessor;
import com.tacz.guns.client.renderer.item.GunSmithTableItemRenderer;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import com.tacz.guns.inventory.tooltip.BlockItemTooltip;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.Optional;

public class GunSmithTableItem extends BlockItem implements BlockItemDataAccessor {
    public GunSmithTableItem(Block block, Item.Properties properties) {
        super(block, properties.stacksTo(1));
    }

    public static NonNullList<ItemStack> fillItemCategory() {
        NonNullList<ItemStack> stacks = NonNullList.create();
        TimelessAPI.getAllCommonBlockIndex().forEach((blockIndex) -> {
            ItemStack stack = BlockItemBuilder.create(blockIndex.getValue().getBlock()).setId(blockIndex.getKey()).build();
            stacks.add(stack);
        });
        return stacks;
    }

    @Override
    @Nonnull
    // 双端公共方法，禁用 client 索引（26.1 不剥 @OnlyIn 成员，dedicated 必崩——
    // 本文件即 2026-08-21 专服 /give 崩溃的第一现场）。
    // 详见 AbstractGunItem#getName 注释与 records/SERVER_TEST_20260821_DEDICATED.md。
    public Component getName(@Nonnull ItemStack stack) {
        ResourceLocation blockId = this.getBlockId(stack);
        var blockIndex = TimelessAPI.getCommonBlockIndex(blockId);
        if (blockIndex.isPresent() && blockIndex.get().getPojo().getName() != null) {
            return Component.translatable(blockIndex.get().getPojo().getName());
        }
        return super.getName(stack);
    }

//    @Override
//    @OnlyIn(Dist.CLIENT)
//    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> components, TooltipFlag isAdvanced) {
//        ResourceLocation blockId = this.getBlockId(stack);
//        TimelessAPI.getClientBlockIndex(blockId).ifPresent(index -> {
//            String tooltipKey = index.getTooltipKey();
//            if (tooltipKey != null) {
//                components.add(Component.translatable(tooltipKey).withStyle(style -> style.withColor(0xAAAAAA)));
//            }
//        });
//
//        PackInfo packInfoObject = ClientAssetsManager.INSTANCE.getPackInfo(blockId);
//        if (packInfoObject != null) {
//            MutableComponent component = Component.translatable(packInfoObject.getName()).withStyle(style -> style.withColor(0x5555FF)).withStyle(style -> style.withItalic(true));
//            components.add(component);
//        }
//    }

    @Override
    @NotNull
    public Optional<TooltipComponent> getTooltipImage(ItemStack pStack) {
        return Optional.of(new BlockItemTooltip(this.getBlockId(pStack)));
    }
}
