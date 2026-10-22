package com.tacz.guns.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.block.GunSmithTableBlockA;
import com.tacz.guns.block.GunSmithTableBlockB;
import com.tacz.guns.block.GunSmithTableBlockC;
import com.tacz.guns.block.StatueBlock;
import com.tacz.guns.block.TargetBlock;
import com.tacz.guns.block.entity.GunSmithTableBlockEntity;
import com.tacz.guns.block.entity.StatueBlockEntity;
import com.tacz.guns.block.entity.TargetBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(GunMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> TILE_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, GunMod.MOD_ID);

    public static final DeferredBlock<Block> GUN_SMITH_TABLE = BLOCKS.registerBlock(
            "gun_smith_table", GunSmithTableBlockB::new, ModBlocks::wood);
    public static final DeferredBlock<Block> WORKBENCH_111 = BLOCKS.registerBlock(
            "workbench_a", GunSmithTableBlockA::new, ModBlocks::wood);
    public static final DeferredBlock<Block> WORKBENCH_211 = BLOCKS.registerBlock(
            "workbench_b", GunSmithTableBlockB::new, ModBlocks::wood);
    public static final DeferredBlock<Block> WORKBENCH_121 = BLOCKS.registerBlock(
            "workbench_c", GunSmithTableBlockC::new, ModBlocks::wood);

    public static final DeferredBlock<Block> TARGET = BLOCKS.registerBlock(
            "target", TargetBlock::new, ModBlocks::wood);
    public static final DeferredBlock<Block> STATUE = BLOCKS.registerBlock(
            "statue", StatueBlock::new,
            p -> p.sound(SoundType.STONE).strength(2.0F, 3.0F).noOcclusion().pushReaction(PushReaction.DESTROY));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GunSmithTableBlockEntity>> GUN_SMITH_TABLE_BE =
            TILE_ENTITIES.register("gun_smith_table", () -> new BlockEntityType<>(
                    GunSmithTableBlockEntity::new,
                    GUN_SMITH_TABLE.get(), WORKBENCH_111.get(), WORKBENCH_211.get(), WORKBENCH_121.get()
            ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TargetBlockEntity>> TARGET_BE =
            TILE_ENTITIES.register("target", () -> new BlockEntityType<>(TargetBlockEntity::new, TARGET.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StatueBlockEntity>> STATUE_BE =
            TILE_ENTITIES.register("statue", () -> new BlockEntityType<>(StatueBlockEntity::new, STATUE.get()));

    public static final TagKey<Block> BULLET_IGNORE_BLOCKS =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "bullet_ignore"));

    private static BlockBehaviour.Properties wood(BlockBehaviour.Properties properties) {
        return properties.sound(SoundType.WOOD).strength(2.0F, 3.0F).noOcclusion().pushReaction(PushReaction.DESTROY);
    }
}
