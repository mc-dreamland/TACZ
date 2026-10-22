package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class ServerMessageSyncBaseTimestamp implements CustomPacketPayload {
    public static final ServerMessageSyncBaseTimestamp INSTANCE = new ServerMessageSyncBaseTimestamp();
    public static final CustomPacketPayload.Type<ServerMessageSyncBaseTimestamp> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_sync_base_timestamp")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageSyncBaseTimestamp> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private ServerMessageSyncBaseTimestamp() { }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static final Marker MARKER = MarkerManager.getMarker("SYNC_BASE_TIMESTAMP");

    public static void handle(ServerMessageSyncBaseTimestamp message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onSyncBaseTimestamp", new Class[]{com.tacz.guns.network.message.ServerMessageSyncBaseTimestamp.class}, message));
    }

}
