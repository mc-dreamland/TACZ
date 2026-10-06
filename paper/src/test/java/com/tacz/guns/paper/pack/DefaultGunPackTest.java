package com.tacz.guns.paper.pack;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultGunPackTest {
    private static byte[] bundledData() throws IOException {
        Path root = Path.of("../src/main/resources/assets/tacz/custom/tacz_default_gun"); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.startsWith(root.resolve("data")) || p.startsWith(root.resolve("assets/tacz/display/attachments")) || p.startsWith(root.resolve("assets/tacz/display/guns"))).sorted().toList()) {
                zip.putNextEntry(new ZipEntry("nested-pack/" + root.relativize(path).toString().replace('\\', '/'))); Files.copy(path, zip); zip.closeEntry();
            }
        } return bytes.toByteArray();
    }
    @Test void bundledCatalogReferencesMappingsAndTagsAreComplete() throws Exception {
        DefaultGunPack pack = new DefaultGunPack(null); pack.load(new ByteArrayInputStream(bundledData()));
        assertTrue(pack.gunIndexes().size() >= 50); assertTrue(pack.attachmentIndexes().size() >= 90);
        for (String id : pack.gunIndexes().keySet()) assertNotNull(pack.gun(id), id);
        for (String id : pack.attachmentIndexes().keySet()) assertNotNull(pack.attachment(id), id);
        for (JsonObject recipe : pack.recipes().values()) if (recipe.has("result")) {
            JsonObject result = recipe.getAsJsonObject("result"); assertTrue(pack.customModelData(result.get("type").getAsString(), result.get("id").getAsString()) >= 0, result.toString());
        }
        assertTrue(pack.allowedAttachment("tacz:ak47", "tacz:ammo_mod_fmj"));
        assertTrue(pack.attachmentHasTag("tacz:ammo_mod_slug", "tacz:intrinsic/slug"));
        assertFalse(pack.allowedAttachment("tacz:ak47", "foreign:scope"));
        Set<Integer> models = new HashSet<>(); for (JsonElement row : pack.mappings()) assertTrue(models.add(row.getAsJsonObject().get("cmd").getAsInt()));
        assertEquals(pack.gunIndexes().size() + pack.ammoIndexes().size() + pack.attachmentIndexes().size() + 3, models.size());
        assertEquals(3_000_002, pack.customModelData("gun", "tacz:ak47"), "Existing saved guns must retain their frozen model");
        assertTrue(pack.networkData().getAsJsonObject("GUN_DATA").has("tacz:ak47_data"));
        assertFalse(pack.scripts().isEmpty());
        assertEquals(2, pack.scopeZoomCount("tacz:scope_standard_8x"));
        assertTrue(pack.laserEditable("gun", "tacz:minigun")); assertFalse(pack.laserEditable("gun", "tacz:ak47"));
        assertTrue(pack.laserEditable("attachment", "tacz:laser_peq15")); assertTrue(pack.laserEditable("attachment", "tacz:laser_peq6"));
        assertFalse(pack.laserEditable("attachment", "tacz:grip_vertical_ranger"), "A built-in laser may explicitly forbid recoloring");
        assertFalse(pack.laserEditable("ammo", "tacz:9mm"));
        JsonArray mapping = pack.mappings(); pack.load(new ByteArrayInputStream(bundledData())); assertEquals(mapping, pack.mappings(), "Reload must keep every carrier model stable");
    }
    @Test void invalidPackDoesNotLoadSilently() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream ignored = new ZipOutputStream(bytes)) {}
        assertThrows(IOException.class, () -> new DefaultGunPack(null).load(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
