package com.tacz.guns.paper.pack;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Recursive administrator configuration. Explicit IDs are independent of each file's location. */
public final class DirectoryPackFiles {
    public static final String MARKER_FILE = ".pack-files-v1";
    public static final int MAX_DEPTH = 16;
    private static final int MAX_FILES = 10_000;
    private static final int MAX_ENTRIES = 25_000;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final List<String> CATEGORIES = List.of("guns", "ammos", "attachments", "recipes", "attachment_tags");

    private DirectoryPackFiles() {}

    public static void apply(Path pluginDir, Map<String, Map<String, JsonObject>> objects, Map<String, JsonArray> tags) throws IOException {
        Path base = pluginDir.toAbsolutePath().normalize();
        PackFileIO.directories(base);
        Path marker = base.resolve(MARKER_FILE);
        boolean initialized = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        if (initialized && (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)))
            throw error(base, marker, "initialization marker must be a regular file");
        List<Document> documents = scan(base);
        List<Document> export = new ArrayList<>();
        if (!initialized) {
            Map<String, Set<String>> existing = new HashMap<>();
            for (Document document : documents) existing.computeIfAbsent(document.category, ignored -> new HashSet<>()).add(document.id);
            Set<Path> reserved = new HashSet<>();
            for (Document document : defaults(objects, tags)) {
                if (existing.getOrDefault(document.category, Set.of()).contains(document.id)) continue;
                Path output = destination(base, document.category, document.id, reserved);
                Document generated = new Document(document.category, document.id, output, document.json);
                documents.add(generated); export.add(generated); reserved.add(output);
            }
        }
        // Validate document shapes and tag declarations before export; semantic pack validation belongs to the caller.
        if (documents.size() > MAX_FILES) throw error(base, marker, "configuration exceeds " + MAX_FILES + " JSON files");
        Loaded loaded = assemble(base, documents);
        Map<Document, byte[]> encoded = new LinkedHashMap<>();
        for (Document document : export) {
            try { encoded.put(document, PackFileIO.encode(document.json)); }
            catch (IOException failure) { throw error(base, document.path, "cannot encode exported default", failure); }
        }
        for (Document document : export) {
            PackFileIO.directories(document.path.getParent());
            try { Files.write(document.path, encoded.get(document), StandardOpenOption.CREATE_NEW); }
            catch (IOException failure) { throw error(base, document.path, "cannot export default without overwriting an existing file", failure); }
        }
        if (!initialized) {
            Files.writeString(marker, "1\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        loaded.objects.forEach(objects::put);
        tags.clear(); tags.putAll(loaded.tags);
    }

    private static List<Document> scan(Path base) throws IOException {
        List<Document> documents = new ArrayList<>();
        int[] visited = {0};
        long totalBytes = 0;
        for (String category : CATEGORIES) {
            Path directory = base.resolve(category);
            PackFileIO.directories(directory);
            List<Path> files = new ArrayList<>();
            Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH + 1, new SimpleFileVisitor<>() {
                private void check(Path path) throws IOException {
                    if (++visited[0] > MAX_ENTRIES) throw error(base, path, "configuration tree exceeds " + MAX_ENTRIES + " entries");
                    if (Files.isSymbolicLink(path)) throw error(base, path, "symbolic links are not allowed");
                    if (!path.equals(directory) && directory.relativize(path).getNameCount() > MAX_DEPTH)
                        throw error(base, path, "configuration path exceeds " + MAX_DEPTH + " levels");
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    check(dir); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    check(file);
                    if (attributes.isDirectory()) throw error(base, file, "configuration path exceeds " + MAX_DEPTH + " levels");
                    if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                        if (!attributes.isRegularFile()) throw error(base, file, "JSON entry must be a regular file");
                        files.add(file);
                        if (documents.size() + files.size() > MAX_FILES) throw error(base, file, "configuration exceeds " + MAX_FILES + " JSON files");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    throw error(base, file, "cannot read configuration entry", failure);
                }
            });
            files.sort(Comparator.comparing(path -> relative(base, path)));
            Map<String, Path> ids = new HashMap<>();
            for (Path file : files) {
                JsonObject json;
                try {
                    byte[] bytes = PackFileIO.read(file); totalBytes += bytes.length;
                    if (totalBytes > MAX_TOTAL_BYTES) throw error(base, file, "configuration JSON exceeds 64 MiB in total");
                    json = PackFileIO.object(bytes, relative(base, file));
                }
                catch (IOException failure) { throw error(base, file, failure.getMessage(), failure); }
                String id = string(json, "id", base, file);
                PackFileIO.resourceId(id, relative(base, file));
                Path previous = ids.putIfAbsent(id, file);
                if (previous != null) throw error(base, file, "duplicate id " + id + " (also " + relative(base, previous) + ")");
                documents.add(new Document(category, id, file, json));
            }
        }
        return documents;
    }

    private static List<Document> defaults(Map<String, Map<String, JsonObject>> objects, Map<String, JsonArray> tags) throws IOException {
        List<Document> result = new ArrayList<>();
        for (var row : new TreeMap<>(objects.getOrDefault("GUN_INDEX", Map.of())).entrySet()) {
            JsonObject wrapper = item(row.getKey(), row.getValue());
            wrapper.add("data", data(objects, "GUN_DATA", row.getKey(), row.getValue()));
            wrapper.getAsJsonObject("index").addProperty("data", row.getKey());
            wrapper.add("allow_attachments", tags.getOrDefault(allowTag(row.getKey()), new JsonArray()).deepCopy());
            result.add(new Document("guns", row.getKey(), null, wrapper));
        }
        for (var row : new TreeMap<>(objects.getOrDefault("AMMO_INDEX", Map.of())).entrySet())
            result.add(new Document("ammos", row.getKey(), null, item(row.getKey(), row.getValue())));
        for (var row : new TreeMap<>(objects.getOrDefault("ATTACHMENT_INDEX", Map.of())).entrySet()) {
            JsonObject wrapper = item(row.getKey(), row.getValue());
            wrapper.add("data", data(objects, "ATTACHMENT_DATA", row.getKey(), row.getValue()));
            wrapper.getAsJsonObject("index").addProperty("data", row.getKey());
            result.add(new Document("attachments", row.getKey(), null, wrapper));
        }
        for (var row : new TreeMap<>(objects.getOrDefault("RECIPES", Map.of())).entrySet()) {
            JsonObject wrapper = new JsonObject(); wrapper.addProperty("id", row.getKey()); wrapper.add("recipe", row.getValue().deepCopy());
            result.add(new Document("recipes", row.getKey(), null, wrapper));
        }
        for (var row : new TreeMap<>(tags).entrySet()) {
            if (row.getKey().substring(row.getKey().indexOf(':') + 1).startsWith("allow_attachments/")) continue;
            JsonObject wrapper = new JsonObject(); wrapper.addProperty("id", row.getKey()); wrapper.add("values", row.getValue().deepCopy());
            result.add(new Document("attachment_tags", row.getKey(), null, wrapper));
        }
        return result;
    }

    private static JsonObject item(String id, JsonObject index) {
        JsonObject wrapper = new JsonObject(); wrapper.addProperty("id", id); wrapper.add("index", index.deepCopy()); return wrapper;
    }

    private static JsonObject data(Map<String, Map<String, JsonObject>> objects, String type, String id, JsonObject index) throws IOException {
        try {
            String dataId = index.get("data").getAsString();
            JsonObject data = objects.getOrDefault(type, Map.of()).get(dataId);
            if (data == null) throw new IOException("Missing bundled " + type + " for " + id + ": " + dataId);
            return data.deepCopy();
        } catch (RuntimeException failure) { throw new IOException("Invalid bundled data reference for " + id, failure); }
    }

    private static Loaded assemble(Path base, List<Document> documents) throws IOException {
        Map<String, Map<String, JsonObject>> objects = new LinkedHashMap<>();
        for (String type : List.of("GUN_INDEX", "GUN_DATA", "AMMO_INDEX", "ATTACHMENT_INDEX", "ATTACHMENT_DATA", "RECIPES")) objects.put(type, new TreeMap<>());
        Map<String, JsonArray> tags = new TreeMap<>(); Map<String, Path> tagSources = new HashMap<>();
        for (Document document : documents) {
            JsonObject json = document.json; Path path = document.path;
            switch (document.category) {
                case "guns", "attachments", "ammos" -> {
                    JsonObject index = object(json, "index", base, path).deepCopy();
                    String type = switch (document.category) { case "guns" -> "GUN"; case "attachments" -> "ATTACHMENT"; default -> "AMMO"; };
                    if (!type.equals("AMMO")) {
                        JsonObject data = object(json, "data", base, path).deepCopy();
                        // Shared bundled attachment data must become independent per editable item.
                        index.addProperty("data", document.id);
                        objects.get(type + "_DATA").put(document.id, data);
                    }
                    objects.get(type + "_INDEX").put(document.id, index);
                    if (type.equals("GUN")) addTag(base, path, allowTag(document.id), array(json, "allow_attachments", base, path), tags, tagSources);
                }
                case "recipes" -> objects.get("RECIPES").put(document.id, object(json, "recipe", base, path).deepCopy());
                case "attachment_tags" -> addTag(base, path, document.id, array(json, "values", base, path), tags, tagSources);
                default -> throw error(base, path, "unknown category " + document.category);
            }
        }
        return new Loaded(objects, tags);
    }

    private static void addTag(Path base, Path source, String id, JsonArray values, Map<String, JsonArray> tags, Map<String, Path> sources) throws IOException {
        Path previous = sources.putIfAbsent(id, source);
        if (previous != null) throw error(base, source, "duplicate attachment tag " + id + " (also " + relative(base, previous) + ")");
        for (JsonElement entry : values) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw error(base, source, "tag " + id + " must contain string IDs");
            String value = entry.getAsString();
            PackFileIO.resourceId(value.startsWith("#") ? value.substring(1) : value, relative(base, source) + " tag " + id);
        }
        tags.put(id, values.deepCopy());
    }

    private static String allowTag(String id) { int colon = id.indexOf(':'); return id.substring(0, colon + 1) + "allow_attachments/" + id.substring(colon + 1); }
    private static String string(JsonObject json, String key, Path base, Path path) throws IOException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw error(base, path, "expected string field " + key);
        return value.getAsString();
    }
    private static JsonObject object(JsonObject json, String key, Path base, Path path) throws IOException {
        if (!json.has(key) || !json.get(key).isJsonObject()) throw error(base, path, "expected object field " + key);
        return json.getAsJsonObject(key);
    }
    private static JsonArray array(JsonObject json, String key, Path base, Path path) throws IOException {
        if (!json.has(key) || !json.get(key).isJsonArray()) throw error(base, path, "expected array field " + key);
        return json.getAsJsonArray(key);
    }

    private static Path destination(Path base, String category, String id, Set<Path> reserved) throws IOException {
        PackFileIO.resourceId(id, "bundled " + category);
        int colon = id.indexOf(':');
        Path parent = base.resolve(category).resolve(id.substring(0, colon));
        Path target = parent.resolve(id.substring(colon + 1) + ".json").normalize();
        if (!target.startsWith(base.resolve(category))) throw error(base, target, "configuration destination leaves its category");
        if (base.resolve(category).relativize(target).getNameCount() > MAX_DEPTH) throw error(base, target, "configuration path exceeds " + MAX_DEPTH + " levels");
        String name = target.getFileName().toString(); int suffix = 0;
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || reserved.contains(target)) {
            target = target.resolveSibling(name.substring(0, name.length() - 5) + ".default-" + (++suffix) + ".json");
        }
        return target;
    }

    private static String relative(Path base, Path path) { return path == null ? "bundled default" : base.relativize(path).toString().replace('\\', '/'); }
    private static IOException error(Path base, Path path, String message) { return new IOException(relative(base, path) + ": " + message); }
    private static IOException error(Path base, Path path, String message, Exception cause) { return new IOException(relative(base, path) + ": " + message, cause); }
    private record Document(String category, String id, Path path, JsonObject json) {}
    private record Loaded(Map<String, Map<String, JsonObject>> objects, Map<String, JsonArray> tags) {}
}
