package com.tacz.guns.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Common identity checks for vanilla carriers; mutable gun state is validated by the server. */
public final class BridgeItemIdentity {
    public static final List<String> BOX_IDS = List.of("tacz:ammo_box", "tacz:gold_ammo_box", "tacz:diamond_ammo_box");
    private static final int MAX_BYTES = 16_384;
    private static final int MAX_DEPTH = 32;
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private BridgeItemIdentity() { }

    public static String material(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "gun" -> "minecraft:stick";
            case "ammo" -> "minecraft:paper";
            case "attachment" -> "minecraft:flint";
            case "box" -> "minecraft:chest";
            default -> null;
        };
    }

    /** Returns null for ordinary or malformed items. Legacy cmd fields are intentionally ignored. */
    public static JsonObject parse(String raw, String material, BiPredicate<String, String> knownItem) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_BYTES || knownItem == null
                || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return null;
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) return null;
            JsonObject data = value.getAsJsonObject();
            String kind = string(data, "kind"), id = string(data, "id");
            String carrier = material(kind);
            if (carrier == null || !carrier.equals(material) || id == null || id.length() > 256
                    || !RESOURCE_ID.matcher(id).matches() || !knownItem.test(kind, id)) return null;
            if (kind.equals("gun") || kind.equals("box")) {
                String instance = string(data, "instance");
                if (instance == null || instance.length() != 36 || !UUID.fromString(instance).toString().equalsIgnoreCase(instance)) return null;
            }
            return data;
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static String string(JsonObject data, String key) {
        JsonElement value = data.get(key);
        return value instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    // Parse with a bounded depth rather than letting deeply nested item metadata exhaust the stack.
    // JsonParser enables lenient mode internally; this reader also rejects duplicate identity keys.
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Item metadata nesting exceeds limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate item metadata field");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid item metadata value");
        };
    }
}
