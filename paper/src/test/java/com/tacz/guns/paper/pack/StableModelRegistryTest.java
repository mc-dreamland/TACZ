package com.tacz.guns.paper.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StableModelRegistryTest {
    @TempDir Path directory;
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject frozen() { return json("{gun:{'tacz:ak47':3000002},ammo:{'tacz:762x39':3100000},attachment:{},box:{}}"); }
    private static Map<String, Collection<String>> catalog(String... guns) { return Map.of("gun", List.of(guns)); }
    private Path registry() { return directory.resolve("model-mappings.json"); }

    @Test void prepareDoesNotPublishFilesOrExposeItsMutableMapping() throws Exception {
        Path registry = directory.resolve("not-created-yet/model-mappings.json");
        StableModelRegistry.Plan plan = StableModelRegistry.prepare(registry, frozen(), catalog("tacz:ak47", "custom:new"));
        assertFalse(Files.exists(registry.getParent()), "Preparing a rejected candidate must not create its registry directory");
        JsonObject mapping = plan.mapping();
        assertEquals(3_000_002, mapping.getAsJsonObject("gun").get("tacz:ak47").getAsInt());
        assertEquals(3_000_000, mapping.getAsJsonObject("gun").get("custom:new").getAsInt());
        mapping.getAsJsonObject("gun").addProperty("tacz:ak47", 9);
        assertEquals(3_000_002, plan.mapping().getAsJsonObject("gun").get("tacz:ak47").getAsInt());
        assertFalse(Files.exists(registry));
    }

    @Test void commitPersistsAtomicallyAndIsIdempotent() throws Exception {
        StableModelRegistry.Plan plan = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:new"));
        plan.commit();
        assertEquals(plan.mapping(), JsonParser.parseString(Files.readString(registry())).getAsJsonObject());
        byte[] saved = Files.readAllBytes(registry());
        FileTime originalTime = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(registry(), originalTime);
        plan.commit();
        assertArrayEquals(saved, Files.readAllBytes(registry()));
        assertEquals(originalTime, Files.getLastModifiedTime(registry()));
        StableModelRegistry.Plan unchanged = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:new"));
        unchanged.commit();
        assertEquals(originalTime, Files.getLastModifiedTime(registry()), "An unchanged reload does not rewrite the registry");
        try (var files = Files.list(directory)) { assertEquals(List.of(registry()), files.toList(), "Atomic-write temporary files must be removed"); }
    }

    @Test void changedFrozenDuplicateAndWrongCategoryNumbersAreRejectedWithoutWrites() throws Exception {
        for (String invalid : List.of(
                "{gun:{'tacz:ak47':3000003}}",
                "{gun:{'custom:first':3000000,'custom:second':3000000}}",
                "{gun:{'custom:first':3100000}}",
                "{gun:{'custom:first':3000002}}")) {
            String contents = json(invalid).toString(); Files.writeString(registry(), contents);
            IOException error = assertThrows(IOException.class, () -> StableModelRegistry.prepare(registry(), frozen(), catalog("tacz:ak47")));
            assertTrue(error.getMessage().contains("model-mappings.json"));
            assertEquals(contents, Files.readString(registry()));
        }
    }

    @Test void removedIdsKeepTheirReservationsAndRegainTheirOriginalNumber() throws Exception {
        StableModelRegistry.Plan first = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:old"));
        int previous = first.mapping().getAsJsonObject("gun").get("custom:old").getAsInt(); first.commit();
        StableModelRegistry.Plan deleted = StableModelRegistry.prepare(registry(), frozen(), catalog()); deleted.commit();
        assertEquals(previous, deleted.mapping().getAsJsonObject("gun").get("custom:old").getAsInt());
        StableModelRegistry.Plan replacement = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:new")); replacement.commit();
        assertNotEquals(previous, replacement.mapping().getAsJsonObject("gun").get("custom:new").getAsInt());
        StableModelRegistry.Plan restored = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:old", "custom:new"));
        assertEquals(previous, restored.mapping().getAsJsonObject("gun").get("custom:old").getAsInt());
        assertEquals(3_000_002, restored.mapping().getAsJsonObject("gun").get("tacz:ak47").getAsInt(), "Inactive bundled IDs also remain reserved");
    }

    @Test void commitRejectsConcurrentEditsWithoutOverwritingTheirContents() throws Exception {
        StableModelRegistry.prepare(registry(), frozen(), catalog()).commit();
        StableModelRegistry.Plan pending = StableModelRegistry.prepare(registry(), frozen(), catalog("custom:pending"));
        JsonObject external = JsonParser.parseString(Files.readString(registry())).getAsJsonObject();
        external.getAsJsonObject("gun").addProperty("custom:external", 3_000_099);
        String edited = external.toString(); Files.writeString(registry(), edited);
        IOException error = assertThrows(IOException.class, pending::commit);
        assertTrue(error.getMessage().contains("changed while"));
        assertEquals(edited, Files.readString(registry()));
        assertFalse(JsonParser.parseString(Files.readString(registry())).getAsJsonObject().getAsJsonObject("gun").has("custom:pending"));
    }
}
