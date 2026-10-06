package com.tacz.guns.bridge;

import com.tacz.guns.GunMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Raw Bukkit plugin-message bytes: the bridge protocol has no byte-array length prefix. */
@EventBusSubscriber(modid = GunMod.MOD_ID)
public record PaperBridgePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<PaperBridgePayload> TYPE = new Type<>(ResourceLocation.parse(BridgeProtocol.CHANNEL));
    public static final StreamCodec<FriendlyByteBuf, PaperBridgePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size > BridgeProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Paper bridge payload too large");
                byte[] bytes = new byte[size];
                buffer.readBytes(bytes);
                return new PaperBridgePayload(bytes);
            });

    public PaperBridgePayload {
        if (data.length > BridgeProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Paper bridge payload too large");
        data = data.clone();
    }

    @Override
    public Type<PaperBridgePayload> type() { return TYPE; }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(Integer.toString(BridgeProtocol.VERSION)).optional().playBidirectional(TYPE, STREAM_CODEC,
                (payload, context) -> context.disconnect(Component.literal("The Paper bridge is only supported by TACZ Paper servers.")));
    }
}
