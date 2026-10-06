package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BridgeProtocolTest {
    @Test void preservesNamespacedIdsUnicodeAndSequence() {
        JsonObject data = new JsonObject();
        data.addProperty("id", "tacz:ak47");
        data.addProperty("label", "配件改装");
        data.addProperty("seq", 1234567890123L);
        var decoded = BridgeProtocol.decode(BridgeProtocol.encode("action", data));
        assertEquals("action", decoded.type());
        assertEquals(data, decoded.data());
    }

    @Test void rejectsUnsupportedVersionAndMalformedUtf8() {
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(
                "{\"version\":1,\"type\":\"action\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(
                "{\"version\":2,\"type\":\"event\",\"data\":{\"op\":\"shoot\"}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(
                "{\"version\":3,\"type\":\"pack\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(
                "{\"version\":1.5,\"type\":\"action\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(new byte[BridgeProtocol.MAX_PACKET_BYTES + 1]));
    }

    @Test void boundsJsonDepthBeforeParsing() {
        String input = "[".repeat(1000) + "0" + "]".repeat(1000);
        assertThrows(IllegalArgumentException.class, () -> BridgeProtocol.decode(input.getBytes(StandardCharsets.UTF_8)));
    }
}
