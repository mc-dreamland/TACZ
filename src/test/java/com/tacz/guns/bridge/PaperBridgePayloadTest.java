package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperBridgePayloadTest {
    @Test
    void keepsBukkitPluginMessageBytesWithoutLengthPrefix() {
        JsonObject data = new JsonObject();
        data.addProperty("nonce", "connection-123");
        byte[] wire = BridgeProtocol.encode("ready", data);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PaperBridgePayload.STREAM_CODEC.encode(buffer, new PaperBridgePayload(wire));
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            assertArrayEquals(wire, encoded);
            PaperBridgePayload decoded = PaperBridgePayload.STREAM_CODEC.decode(buffer);
            assertArrayEquals(wire, decoded.data());
            assertEquals("connection-123", BridgeProtocol.decode(decoded.data()).data().get("nonce").getAsString());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedMessagesInBothDirections() {
        byte[] oversized = new byte[BridgeProtocol.MAX_PACKET_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> new PaperBridgePayload(oversized));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(oversized));
        try {
            assertThrows(IllegalArgumentException.class, () -> PaperBridgePayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsMaximumSizeAndOwnsItsBytes() {
        byte[] data = new byte[BridgeProtocol.MAX_PACKET_BYTES];
        PaperBridgePayload payload = new PaperBridgePayload(data);
        data[0] = 1;
        assertEquals(0, payload.data()[0]);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PaperBridgePayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(BridgeProtocol.MAX_PACKET_BYTES, buffer.readableBytes());
            assertArrayEquals(payload.data(), PaperBridgePayload.STREAM_CODEC.decode(buffer).data());
        } finally {
            buffer.release();
        }
    }
}
