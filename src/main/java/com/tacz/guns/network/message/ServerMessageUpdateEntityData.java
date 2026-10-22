package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.entity.sync.core.DataEntry;
import com.tacz.guns.entity.sync.core.SyncedEntityData;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ServerMessageUpdateEntityData implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageUpdateEntityData> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_update_entity_data")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageUpdateEntityData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, message -> message.entityId,
        DataEntry.STREAM_CODEC.apply(ByteBufCodecs.collection(NonNullList::createWithCapacity)), message -> message.entries,
        ServerMessageUpdateEntityData::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int entityId;
    private final List<DataEntry<?, ?>> entries;

    public ServerMessageUpdateEntityData(int entityId, List<DataEntry<?, ?>> entries) {
        this.entityId = entityId;
        this.entries = entries;
    }

    public static void handle(ServerMessageUpdateEntityData message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onUpdateEntityData", new Class[]{com.tacz.guns.network.message.ServerMessageUpdateEntityData.class}, message));
    }

    public List<DataEntry<?, ?>> getEntries() {
        return entries;
    }

}
