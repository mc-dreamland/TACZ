package com.tacz.guns.paper.pack;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded strict JSON and filesystem operations shared by the administrator-owned pack files. */
final class PackFileIO {
    static final int MAX_BYTES = 2_000_000;
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private PackFileIO() {}

    static String resourceId(String id, String context) throws IOException {
        if (id == null || id.length() > 256 || !RESOURCE.matcher(id).matches()) throw new IOException(context + ": invalid resource id " + id);
        String path = id.substring(id.indexOf(':') + 1);
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException(context + ": invalid resource path " + id);
        }
        return id;
    }

    static byte[] read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file, not a symbolic link: " + file);
        if (Files.size(file) > MAX_BYTES) throw new IOException("JSON file exceeds " + MAX_BYTES + " bytes: " + file);
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("JSON file exceeds " + MAX_BYTES + " bytes: " + file);
            return bytes;
        }
    }

    static JsonObject object(byte[] bytes, String context) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            reader.setLenient(false);
            JsonElement parsed = value(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) throw new IOException("Expected one JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            throw new IOException(context + ": invalid JSON: " + failure.getMessage(), failure);
        }
    }

    private static JsonElement value(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("JSON nesting exceeds 64 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject result = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.has(key)) throw new IOException("Duplicate JSON member: " + key);
                    result.add(key, value(reader, depth + 1));
                }
                reader.endObject(); yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray result = new JsonArray();
                while (reader.hasNext()) result.add(value(reader, depth + 1));
                reader.endArray(); yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token " + reader.peek());
        };
    }

    static byte[] encode(JsonObject value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("JSON file exceeds " + MAX_BYTES + " bytes");
        return bytes;
    }

    static void directories(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) directories(parent);
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed: " + current);
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a directory: " + absolute);
        } else Files.createDirectory(absolute);
    }
}
