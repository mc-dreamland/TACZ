package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class PackTransferTest {
    private JsonObject largePack() {
        byte[] bytes = new byte[100_000];
        new Random(9182).nextBytes(bytes);
        JsonObject pack = new JsonObject();
        pack.addProperty("data", Base64.getEncoder().encodeToString(bytes));
        return pack;
    }

    @Test void reassemblesOutOfOrderWithoutExceedingPluginMessageLimit() throws IOException {
        JsonObject original = largePack();
        List<JsonObject> chunks = PackTransfer.encode(original);
        assertTrue(chunks.size() > 1);
        PackTransfer.Receiver receiver = new PackTransfer.Receiver();
        for (int i = chunks.size() - 1; i >= 0; i--) {
            assertTrue(BridgeProtocol.encode("pack", chunks.get(i)).length < BridgeProtocol.MAX_PACKET_BYTES);
            JsonObject result = receiver.accept(chunks.get(i));
            if (i == 0) assertEquals(original, result);
            else assertNull(result);
        }
    }

    @Test void rejectsDuplicateMixedAndCorruptedChunks() throws IOException {
        List<JsonObject> chunks = PackTransfer.encode(largePack());
        PackTransfer.Receiver receiver = new PackTransfer.Receiver();
        receiver.accept(chunks.get(0));
        assertThrows(IOException.class, () -> receiver.accept(chunks.get(0)));
        JsonObject mixed = chunks.get(1).deepCopy();
        mixed.addProperty("hash", "0".repeat(64));
        assertThrows(IOException.class, () -> receiver.accept(mixed));
        receiver.reset();
        JsonObject corrupted = chunks.get(0).deepCopy();
        corrupted.addProperty("bytes", "not/base64??");
        assertThrows(IOException.class, () -> receiver.accept(corrupted));
    }

    @Test void rejectsTamperedContentHash() throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("id", "tacz:ak47");
        JsonObject chunk = PackTransfer.encode(pack).get(0).deepCopy();
        chunk.addProperty("hash", "f".repeat(64));
        assertThrows(IOException.class, () -> new PackTransfer.Receiver().accept(chunk));
    }
}
