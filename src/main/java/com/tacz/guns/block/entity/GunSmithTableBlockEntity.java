package com.tacz.guns.block.entity;

import com.tacz.guns.init.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

/**
 * Work package ②: persist table id. Menu provider is work package ③/④
 * (NeoForge {@code IMenuProvider} / extra-data instead of Fabric {@code ExtendedMenuProvider}).
 */
public class GunSmithTableBlockEntity extends BlockEntity {
    private static final String ID_TAG = "BlockId";

    @Nullable
    private Identifier id = null;

    public GunSmithTableBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlocks.GUN_SMITH_TABLE_BE.get(), pos, blockState);
    }

    public void setId(Identifier id) {
        this.id = id;
        this.setChanged();
    }

    @Nullable
    public Identifier getId() {
        return id;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String raw = input.getStringOr(ID_TAG, "");
        if (!raw.isEmpty()) {
            this.id = Identifier.tryParse(raw);
        } else {
            // Leave the id unset for naturally placed/legacy tables. The block implementation
            // resolves the physical workbench to the corresponding gun-pack index id.
            this.id = null;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (id != null) {
            output.putString(ID_TAG, id.toString());
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }
}
