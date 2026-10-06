package com.tacz.guns.block.entity;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.block.TargetBlock;
import com.tacz.guns.config.common.OtherConfig;
import com.tacz.guns.init.ModBlocks;
import com.tacz.guns.init.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.Set;

import static com.tacz.guns.block.TargetBlock.OUTPUT_POWER;
import static com.tacz.guns.block.TargetBlock.STAND;

public class TargetBlockEntity extends BlockEntity implements Nameable {
    /**
     * 标靶复位时间，暂定为 5 秒
     */
    private static final int RESET_TIME = 5 * 20;
    private static final String OWNER_TAG = "Owner";
    private static final String CUSTOM_NAME_TAG = "CustomName";
    public float rot = 0;
    public float oRot = 0;
    private @Nullable GameProfile owner;
    private @Nullable Component name;

    public TargetBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlocks.TARGET_BE.get(), pos, blockState);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, TargetBlockEntity pBlockEntity) {
        pBlockEntity.oRot = pBlockEntity.rot;
        if (state.getValue(STAND)) {
            pBlockEntity.rot = Math.max(pBlockEntity.rot - 18, 0);
        } else {
            pBlockEntity.rot = Math.min(pBlockEntity.rot + 45, 90);
        }
    }

    @Nullable
    public GameProfile getOwner() {
        return owner;
    }

    public void setOwner(@Nullable GameProfile owner) {
        this.owner = owner;
        this.refresh();
    }

    @Override
    public void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        this.owner = input.read(OWNER_TAG, CompoundTag.CODEC).map(TargetBlockEntity::readOwner).orElse(null);
        // Also read saves produced by the transitional UUID/name representation.
        if (owner == null && !input.getStringOr("owner_name", "").isEmpty()) {
            CompoundTag legacy = new CompoundTag();
            legacy.putString("Name", input.getStringOr("owner_name", ""));
            try {
                legacy.put("Id", net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,
                        java.util.UUID.fromString(input.getStringOr("owner_uuid", ""))).getOrThrow());
            } catch (IllegalArgumentException ignored) {
                // Older profiles could omit a UUID; the name still gives a stable offline identity.
            }
            owner = readOwner(legacy);
        }
        this.name = input.read("custom_name", net.minecraft.network.chat.ComponentSerialization.CODEC).orElse(null);
        String legacyName = input.getStringOr(CUSTOM_NAME_TAG, "");
        if (name == null && !legacyName.isEmpty()) {
            this.name = net.minecraft.network.chat.ComponentSerialization.CODEC.parse(
                    com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(legacyName)).getOrThrow();
        }
    }

    private static GameProfile readOwner(CompoundTag data) {
        String name = data.getStringOr("Name", "");
        java.util.UUID id = data.contains("Id") ? net.minecraft.core.UUIDUtil.CODEC.parse(
                net.minecraft.nbt.NbtOps.INSTANCE, data.get("Id")).result().orElse(null) : null;
        if (id == null) id = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
        var properties = com.google.common.collect.ImmutableMultimap.<String, com.mojang.authlib.properties.Property>builder();
        CompoundTag storedProperties = data.getCompoundOrEmpty("Properties");
        for (String key : storedProperties.keySet()) {
            for (net.minecraft.nbt.Tag value : storedProperties.getListOrEmpty(key)) {
                if (value instanceof CompoundTag property) {
                    properties.put(key, new com.mojang.authlib.properties.Property(key,
                            property.getStringOr("Value", ""), property.getString("Signature").orElse(null)));
                }
            }
        }
        return new GameProfile(id, name, new com.mojang.authlib.properties.PropertyMap(properties.build()));
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        if (owner != null) {
            CompoundTag data = new CompoundTag();
            data.putString("Name", owner.name());
            data.put("Id", net.minecraft.core.UUIDUtil.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, owner.id()).getOrThrow());
            CompoundTag properties = new CompoundTag();
            owner.properties().asMap().forEach((key, values) -> {
                net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
                for (var property : values) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("Value", property.value());
                    if (property.signature() != null) entry.putString("Signature", property.signature());
                    entries.add(entry);
                }
                properties.put(key, entries);
            });
            data.put("Properties", properties);
            output.store(OWNER_TAG, CompoundTag.CODEC, data);
        }
        output.storeNullable("custom_name", net.minecraft.network.chat.ComponentSerialization.CODEC, name);
    }

    @Override
    public Component getName() {
        return this.name != null ? this.name : Component.empty();
    }

    @Nullable
    @Override
    public Component getCustomName() {
        return this.name;
    }

    public void setCustomName(Component name) {
        this.name = name;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    public void refresh() {
        this.setChanged();
        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    public void hit(Level level, BlockState state, BlockHitResult hit, boolean isUpperBlock) {
        if (this.level != null && state.getValue(STAND)) {
            BlockPos blockPos = hit.getBlockPos();
            // 如果是击中上方，把状态移动到下方处理
            if (isUpperBlock) {
                blockPos = blockPos.below();
                state = level.getBlockState(blockPos);
            }
            int redstoneStrength = TargetBlock.getRedstoneStrength(hit, isUpperBlock);
            level.setBlock(blockPos, state.setValue(STAND, false).setValue(OUTPUT_POWER, redstoneStrength), Block.UPDATE_ALL);
            level.scheduleTick(blockPos, state.getBlock(), RESET_TIME);
            // 原版的声音传播距离由 volume 决定
            // 当声音大于 1 时，距离为 = 16 * volume
            float volume = OtherConfig.TARGET_SOUND_DISTANCE.get() / 16.0f;
            volume = Math.max(volume, 0);
            level.playSound(null, blockPos, ModSounds.TARGET_HIT.get(), SoundSource.BLOCKS, volume, this.level.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }
}
