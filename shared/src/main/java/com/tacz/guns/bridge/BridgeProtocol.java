package com.tacz.guns.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Versioned, registry-independent plugin messages, shared by Forge and Paper. */
public final class BridgeProtocol {
    public static final String CHANNEL = "tacz:paper";
    public static final String ITEM_KEY = "tacz:bridge";
    public static final int VERSION = 3;
    public static final int MAX_PACKET_BYTES = 30_000;
    public static final Gson GSON = new Gson();
    private static final Set<String> TYPES = Set.of("hello", "welcome", "pack", "ready", "action",
            "state", "event", "ballistics", "menu", "menu_action", "error", "inventory_changed", "goodbye");

    private BridgeProtocol() {}

    public record Message(String type, JsonObject data) {}

    public static byte[] encode(String type, JsonObject data) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown bridge message: " + type);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", VERSION);
        envelope.addProperty("type", type);
        envelope.add("data", data);
        byte[] result = GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Bridge message exceeds limit");
        return result;
    }

    public static Message decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Invalid message size");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            checkDepth(json);
            JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
            if (!envelope.has("version") || !envelope.get("version").isJsonPrimitive()
                    || !envelope.getAsJsonPrimitive("version").isNumber()
                    || !Integer.toString(VERSION).equals(envelope.get("version").getAsString()))
                throw new IllegalArgumentException("Unsupported bridge version");
            String type = envelope.get("type").getAsString();
            if (!TYPES.contains(type) || !envelope.get("data").isJsonObject()) throw new IllegalArgumentException("Invalid envelope");
            return new Message(type, envelope.getAsJsonObject("data"));
        } catch (CharacterCodingException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid TACZ bridge message", exception);
        }
    }

    private static void checkDepth(String json) {
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 32) throw new IllegalArgumentException("JSON nesting exceeds limit");
            } else if (c == '}' || c == ']') depth--;
        }
    }
}
