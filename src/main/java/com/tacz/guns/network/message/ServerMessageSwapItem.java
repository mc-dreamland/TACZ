package com.tacz.guns.network.message;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.event.SwapItemWithOffHand;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public class ServerMessageSwapItem implements CustomPacketPayload {
    public static final ServerMessageSwapItem INSTANCE = new ServerMessageSwapItem();
    public static final CustomPacketPayload.Type<ServerMessageSwapItem> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "server_swap_item")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerMessageSwapItem> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private ServerMessageSwapItem() { }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerMessageSwapItem message, IPayloadContext context) {
        context.enqueueWork(() -> com.tacz.guns.network.ClientPacketBridge.invoke("onSwapItem", new Class[]{com.tacz.guns.network.message.ServerMessageSwapItem.class}, message));
    }
}