package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.network.DataType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class ServerMessageSyncGunPack implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageSyncGunPack> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_sync_gun_pack")
    );
    public static final StreamCodec<FriendlyByteBuf, ServerMessageSyncGunPack> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.fromCodec(DataType.CODEC),
            ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.STRING_UTF8)),
        ServerMessageSyncGunPack::getCache,
        ServerMessageSyncGunPack::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final Map<DataType, Map<Identifier, String>> cache;

    public ServerMessageSyncGunPack(Map<DataType, Map<Identifier, String>> cache) {
        this.cache = cache;
    }

    public static void handle(ServerMessageSyncGunPack message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onSyncGunPack", new Class[]{com.tacz.guns.network.message.ServerMessageSyncGunPack.class}, message));
    }


    public Map<DataType, Map<Identifier, String>> getCache() {
        return cache;
    }

}