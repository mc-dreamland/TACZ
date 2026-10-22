package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageCraft implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ServerMessageCraft> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_craft")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageCraft> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, message -> message.menuId,
        ServerMessageCraft::new
    );

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public final int menuId;

    public ServerMessageCraft(int menuId) {
        this.menuId = menuId;
    }

    public static void handle(ServerMessageCraft message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onCraft", new Class[]{com.tacz.guns.network.message.ServerMessageCraft.class}, message));
    }

}
