package com.tacz.guns.network.message.handshake;

import com.tacz.guns.GunMod;
import com.tacz.guns.entity.sync.core.SyncedDataKey;
import com.tacz.guns.entity.sync.core.SyncedEntityData;
import com.tacz.guns.network.IMessage;
import com.tacz.guns.network.LoginIndexHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ServerMessageSyncedEntityDataMapping extends LoginIndexHolder implements IMessage {
    public static final CustomPacketPayload.Type<ServerMessageSyncedEntityDataMapping> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_synced_entity_data_mapping")
    );
    public static final StreamCodec<FriendlyByteBuf, ServerMessageSyncedEntityDataMapping> STREAM_CODEC = StreamCodec.of(
        ServerMessageSyncedEntityDataMapping::encode,
        ServerMessageSyncedEntityDataMapping::decode
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final Marker HANDSHAKE = MarkerManager.getMarker("TACZ_HANDSHAKE");
    public final Map<Identifier, List<Pair<Identifier, Integer>>> keyMap;

    public ServerMessageSyncedEntityDataMapping() {
        this.keyMap = new HashMap<>();
    }

    private ServerMessageSyncedEntityDataMapping(Map<Identifier, List<Pair<Identifier, Integer>>> keyMap) {
        this.keyMap = keyMap;
    }

    public static void encode(FriendlyByteBuf buffer, ServerMessageSyncedEntityDataMapping message) {
        Set<SyncedDataKey<?, ?>> keys = SyncedEntityData.instance().getKeys();
        buffer.writeInt(keys.size());
        keys.forEach(key -> {
            int id = SyncedEntityData.instance().getInternalId(key);
            buffer.writeIdentifier(key.classKey().id());
            buffer.writeIdentifier(key.id());
            buffer.writeVarInt(id);
        });
    }

    public static ServerMessageSyncedEntityDataMapping decode(FriendlyByteBuf buffer) {
        int size = buffer.readInt();
        Map<Identifier, List<Pair<Identifier, Integer>>> keyMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            Identifier classId = buffer.readIdentifier();
            Identifier keyId = buffer.readIdentifier();
            int id = buffer.readVarInt();
            keyMap.computeIfAbsent(classId, c -> new ArrayList<>()).add(Pair.of(keyId, id));
        }
        return new ServerMessageSyncedEntityDataMapping(keyMap);
    }

    @Override
    public void handle(IPayloadContext context) {
        GunMod.LOGGER.debug("Received synced key mappings from server");
        context.enqueueWork(() -> {
            if (!SyncedEntityData.instance().updateMappings(this.keyMap)) {
                context.disconnect(Component.literal("Connection closed - [TacZ] Received unknown synced data keys."));
                return;
            }
            context.reply(Acknowledge.INSTANCE);
        }).exceptionally(error -> {
            GunMod.LOGGER.error("Could not apply TACZ synced entity mappings", error);
            context.disconnect(Component.literal("Could not initialize TACZ entity synchronization."));
            return null;
        });
    }

    public Map<Identifier, List<Pair<Identifier, Integer>>> getKeyMap() {
        return this.keyMap;
    }
}
