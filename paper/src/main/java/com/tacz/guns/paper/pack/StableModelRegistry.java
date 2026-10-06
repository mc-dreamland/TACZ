package com.tacz.guns.paper.pack;

import com.google.gson.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Persistent carrier identities. Removed catalog entries remain reserved tombstones. */
public final class StableModelRegistry {
    private static final Map<String, Integer> BASES = new LinkedHashMap<>();
    static { BASES.put("gun", 3_000_000); BASES.put("ammo", 3_100_000); BASES.put("attachment", 3_200_000); BASES.put("box", 3_300_000); }
    private static final int RANGE = 100_000;

    private StableModelRegistry() {}

    /** Reads and allocates in memory; no file is written until the caller commits its validated candidate. */
    public static Plan prepare(Path registry, JsonObject frozen, Map<String, Collection<String>> catalog) throws IOException {
        Path path = registry.toAbsolutePath().normalize();
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException(path + ": symbolic links are not allowed");
        byte[] original = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? PackFileIO.read(path) : null;
        JsonObject existing = original == null ? new JsonObject() : PackFileIO.object(original, path.toString());
        validate(frozen, "bundled model mappings"); validate(existing, path.toString());
        JsonObject mapping = new JsonObject();
        for (String kind : BASES.keySet()) {
            JsonObject values = frozen.has(kind) ? frozen.getAsJsonObject(kind).deepCopy() : new JsonObject();
            if (existing.has(kind)) for (var entry : existing.getAsJsonObject(kind).entrySet()) {
                if (values.has(entry.getKey()) && values.get(entry.getKey()).getAsInt() != entry.getValue().getAsInt())
                    throw new IOException(path + ": cannot change frozen model " + kind + "/" + entry.getKey());
                values.add(entry.getKey(), entry.getValue().deepCopy());
            }
            mapping.add(kind, values);
        }
        validate(mapping, path.toString());
        for (String kind : catalog.keySet()) if (!BASES.containsKey(kind)) throw new IOException(path + ": unknown model category " + kind);
        for (var category : BASES.entrySet()) {
            String kind = category.getKey(); JsonObject values = mapping.getAsJsonObject(kind);
            Set<Integer> used = new HashSet<>(); values.entrySet().forEach(entry -> used.add(entry.getValue().getAsInt()));
            int next = category.getValue();
            Collection<String> ids = catalog.getOrDefault(kind, List.of());
            if (ids == null) throw new IOException(path + ": null catalog for " + kind);
            for (String id : ids) PackFileIO.resourceId(id, path + " " + kind);
            for (String id : new TreeSet<>(ids)) {
                if (values.has(id)) continue;
                while (used.contains(next) && next < category.getValue() + RANGE) next++;
                if (next >= category.getValue() + RANGE) throw new IOException(path + ": model range exhausted for " + kind);
                values.addProperty(id, next); used.add(next++);
            }
        }
        return new Plan(path, original, mapping);
    }

    private static void validate(JsonObject mapping, String context) throws IOException {
        if (mapping == null) throw new IOException(context + ": expected a model mapping object");
        Set<Integer> used = new HashSet<>();
        for (var category : mapping.entrySet()) {
            Integer base = BASES.get(category.getKey());
            if (base == null || !category.getValue().isJsonObject()) throw new IOException(context + ": unknown or invalid model category " + category.getKey());
            for (var entry : category.getValue().getAsJsonObject().entrySet()) {
                PackFileIO.resourceId(entry.getKey(), context + " " + category.getKey());
                int model;
                try {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("expected integer");
                    model = entry.getValue().getAsBigDecimal().intValueExact();
                } catch (RuntimeException failure) { throw new IOException(context + ": invalid model number for " + entry.getKey(), failure); }
                if (model < base || model >= base + RANGE || !used.add(model)) throw new IOException(context + ": duplicate or out-of-range model for " + entry.getKey() + ": " + model);
            }
        }
    }

    public static final class Plan {
        private final Path path;
        private final byte[] original;
        private final JsonObject mapping;
        private boolean committed;
        private Plan(Path path, byte[] original, JsonObject mapping) { this.path = path; this.original = original; this.mapping = mapping; }
        public JsonObject mapping() { return mapping.deepCopy(); }

        /** Atomic publication is deliberately separate from allocation and the caller's catalog validation. */
        public void commit() throws IOException {
            if (committed) return;
            PackFileIO.directories(path.getParent());
            byte[] current = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? PackFileIO.read(path) : null;
            if (!Arrays.equals(original, current)) throw new IOException(path + ": model registry changed while this reload was being validated; retry reload");
            if (original != null && PackFileIO.object(original, path.toString()).equals(mapping)) { committed = true; return; }
            byte[] bytes = PackFileIO.encode(mapping);
            Path temporary = Files.createTempFile(path.getParent(), ".model-mappings-", ".tmp");
            try {
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                committed = true;
            } finally { Files.deleteIfExists(temporary); }
        }
    }
}
