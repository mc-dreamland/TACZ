package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded transfer of the default pack; no filesystem paths arrive from the network. */
public final class PackTransfer {
    public static final int MAX_RAW_BYTES = 8 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 2 * 1024 * 1024;
    private static final int CHUNK_BYTES = 18_000;

    private PackTransfer() {}

    public static List<JsonObject> encode(JsonObject pack) throws IOException {
        byte[] raw = BridgeProtocol.GSON.toJson(pack).getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_RAW_BYTES) throw new IOException("Default pack exceeds transfer limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_COMPRESSED_BYTES) throw new IOException("Compressed pack exceeds limit");
        String hash = hash(raw);
        int count = (compressed.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        List<JsonObject> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonObject chunk = new JsonObject();
            chunk.addProperty("hash", hash);
            chunk.addProperty("index", i);
            chunk.addProperty("count", count);
            chunk.addProperty("bytes", Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(
                    compressed, i * CHUNK_BYTES, Math.min(compressed.length, (i + 1) * CHUNK_BYTES))));
            chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    public static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Receiver {
        private String hash;
        private byte[][] chunks;
        private int received;
        private int compressedBytes;

        public void reset() { hash = null; chunks = null; received = 0; compressedBytes = 0; }

        /** Returns null until all chunks have arrived. Rejects mixed/duplicate transfers. */
        public JsonObject accept(JsonObject part) throws IOException {
            int count = part.get("count").getAsInt();
            int index = part.get("index").getAsInt();
            String incomingHash = part.get("hash").getAsString();
            if (count < 1 || count > 128 || index < 0 || index >= count || !incomingHash.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid pack chunk metadata");
            if (chunks == null) { hash = incomingHash; chunks = new byte[count][]; }
            if (!hash.equals(incomingHash) || chunks.length != count || chunks[index] != null)
                throw new IOException("Mixed or duplicate pack chunks");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(part.get("bytes").getAsString()); }
            catch (RuntimeException exception) { throw new IOException("Invalid chunk", exception); }
            if (bytes.length == 0 || bytes.length > CHUNK_BYTES || compressedBytes + bytes.length > MAX_COMPRESSED_BYTES)
                throw new IOException("Pack transfer exceeds limit");
            chunks[index] = bytes;
            compressedBytes += bytes.length;
            if (++received < chunks.length) return null;
            ByteArrayOutputStream zipped = new ByteArrayOutputStream(compressedBytes);
            for (byte[] chunk : chunks) zipped.write(chunk);
            byte[] raw;
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(zipped.toByteArray()))) {
                raw = input.readNBytes(MAX_RAW_BYTES + 1);
            }
            if (raw.length > MAX_RAW_BYTES || !hash(raw).equals(hash)) throw new IOException("Pack checksum/size mismatch");
            JsonObject result = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            reset();
            return result;
        }
    }
}
