package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BallisticsBatchTest {
    private static JsonObject record(int id, String op) {
        JsonObject result = new JsonObject();
        result.addProperty("bullet", id);
        result.addProperty("op", op);
        return result;
    }

    @Test void preservesSpawnHitEndOrderAcrossCountLimitedPackets() {
        BallisticsBatch batch = new BallisticsBatch("minecraft:overworld");
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            JsonObject entry = record(i / 3, new String[]{"spawn", "block_hit", "end"}[i % 3]);
            if (!batch.offer(entry)) { packets.add(batch.drain()); assertTrue(batch.offer(entry)); }
        }
        packets.add(batch.drain());
        assertEquals(3, packets.size());
        int offset = 0;
        for (byte[] packet : packets) {
            var message = BridgeProtocol.decode(packet);
            assertEquals("ballistics", message.type());
            assertEquals("minecraft:overworld", message.data().get("dimension").getAsString());
            var records = message.data().getAsJsonArray("records");
            assertTrue(records.size() <= BallisticsBatch.MAX_RECORDS);
            for (var value : records) {
                assertEquals(record(offset / 3, new String[]{"spawn", "block_hit", "end"}[offset % 3]), value);
                offset++;
            }
        }
        assertEquals(300, offset);
        assertNull(batch.drain());
    }

    @Test void splitsOnUtf8BytesIncludingEscapingWithoutLosingTheNextRecord() {
        BallisticsBatch batch = new BallisticsBatch("custom:世界");
        JsonObject entry = record(1, "spawn");
        entry.addProperty("payload", "曳光\"<>&".repeat(150));
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            if (!batch.offer(entry)) { packets.add(batch.drain()); assertTrue(batch.offer(entry)); }
        }
        packets.add(batch.drain());
        assertTrue(packets.size() > 1);
        int count = 0;
        for (byte[] packet : packets) {
            assertTrue(packet.length <= BridgeProtocol.MAX_PACKET_BYTES);
            var records = BridgeProtocol.decode(packet).data().getAsJsonArray("records");
            for (var value : records) assertEquals(entry, value);
            count += records.size();
        }
        assertEquals(50, count);
    }

    @Test void queuedRecordsAreSnapshotsAndOversizedRecordsCannotDestroyThem() {
        BallisticsBatch batch = new BallisticsBatch("minecraft:overworld");
        JsonObject entry = record(2, "spawn");
        assertTrue(batch.offer(entry));
        entry.addProperty("op", "end");
        JsonObject huge = record(3, "spawn");
        huge.addProperty("payload", "x".repeat(BridgeProtocol.MAX_PACKET_BYTES));
        assertThrows(IllegalArgumentException.class, () -> batch.offer(huge));
        assertEquals(record(2, "spawn"), BridgeProtocol.decode(batch.drain()).data().getAsJsonArray("records").get(0));
    }
}
