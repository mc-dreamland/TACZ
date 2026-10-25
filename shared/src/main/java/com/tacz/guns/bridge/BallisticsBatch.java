package com.tacz.guns.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/** Ordered visual records with both an entry limit and the exact UTF-8 envelope budget. */
public final class BallisticsBatch {
    public static final int MAX_RECORDS = 128;
    private final String dimension;
    private final JsonObject metadata;
    private final int emptyBytes;
    private JsonArray records = new JsonArray();
    private int bytes;

    public BallisticsBatch(String dimension) {
        this(dimension, new JsonObject());
    }

    /** 网易会话字段在组包前加入，参与精确字节预算；默认 Java 批次不携带这些字段。 */
    public BallisticsBatch(String dimension, JsonObject metadata) {
        if (dimension == null || dimension.length() > 256) throw new IllegalArgumentException("Invalid dimension");
        this.dimension = dimension;
        this.metadata = metadata.deepCopy();
        emptyBytes = BridgeProtocol.encode("ballistics", payload()).length;
        bytes = emptyBytes;
    }

    /** False leaves the existing batch untouched; callers must drain it and retry the record. */
    public boolean offer(JsonObject record) {
        JsonObject copy = record.deepCopy();
        int recordBytes = BridgeProtocol.GSON.toJson(copy).getBytes(StandardCharsets.UTF_8).length;
        if (emptyBytes + recordBytes > BridgeProtocol.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Ballistic record exceeds packet limit");
        int additional = recordBytes + (records.isEmpty() ? 0 : 1);
        if (records.size() >= MAX_RECORDS || bytes + additional > BridgeProtocol.MAX_PACKET_BYTES) return false;
        records.add(copy);
        bytes += additional;
        return true;
    }

    public byte[] drain() {
        if (records.isEmpty()) return null;
        byte[] encoded = BridgeProtocol.encode("ballistics", payload());
        records = new JsonArray();
        bytes = emptyBytes;
        return encoded;
    }

    private JsonObject payload() {
        JsonObject data = metadata.deepCopy();
        data.addProperty("dimension", dimension);
        data.add("records", records);
        return data;
    }
}
