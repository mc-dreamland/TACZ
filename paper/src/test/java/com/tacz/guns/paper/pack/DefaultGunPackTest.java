package com.tacz.guns.paper.pack;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultGunPackTest {
    static final Set<String> GUN_IDS = Set.of("bujidao:ar_01", "bujidao:ar_02", "bujidao:hg_01", "bujidao:hg_3",
            "bujidao:sg_01", "bujidao:sg_02", "bujidao:sr_01", "bujidao:sr_02", "bujidao:sr_03", "bujidao:sr_04");

    private static byte[] bundledData() throws IOException {
        Path root = Path.of("../src/main/resources/assets/tacz/custom/tacz_default_gun"); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> bundledEntry(root, p)).sorted().toList()) {
                zip.putNextEntry(new ZipEntry("nested-pack/" + root.relativize(path).toString().replace('\\', '/'))); Files.copy(path, zip); zip.closeEntry();
            }
        } return bytes.toByteArray();
    }
    static boolean bundledEntry(Path root, Path file) {
        String path = root.relativize(file).toString().replace('\\', '/');
        return path.startsWith("data/") || path.matches("assets/[^/]+/display/(attachments|guns)/.+\\.json");
    }
    @Test void bundledCatalogIdentitiesReferencesAndTagsAreComplete() throws Exception {
        DefaultGunPack pack = new DefaultGunPack(null); pack.load(new ByteArrayInputStream(bundledData()));
        assertEquals(GUN_IDS, pack.gunIndexes().keySet(), "Only the ten replacement guns belong to the shipped catalog");
        assertEquals(GUN_IDS.stream().map(id -> id + "_data").collect(java.util.stream.Collectors.toSet()), pack.guns().keySet());
        assertTrue(pack.attachmentIndexes().size() >= 90);
        for (String id : pack.gunIndexes().keySet()) { assertNotNull(pack.gun(id), id); assertTrue(pack.hasItem("gun", id)); }
        for (String id : pack.ammoIndexes().keySet()) assertTrue(pack.hasItem("ammo", id));
        for (String id : pack.attachmentIndexes().keySet()) { assertNotNull(pack.attachment(id), id); assertTrue(pack.hasItem("attachment", id)); }
        for (String id : DefaultGunPack.BOX_IDS) assertTrue(pack.hasItem("box", id));
        for (JsonObject recipe : pack.recipes().values()) if (recipe.has("result")) {
            JsonObject result = recipe.getAsJsonObject("result"); assertTrue(pack.hasItem(result.get("type").getAsString(), result.get("id").getAsString()), result.toString());
        }
        assertTrue(pack.allowedAttachment("bujidao:ar_01", "tacz:ammo_mod_fmj"));
        assertTrue(pack.attachmentHasTag("tacz:ammo_mod_slug", "tacz:intrinsic/slug"));
        assertFalse(pack.allowedAttachment("bujidao:ar_01", "foreign:scope"));
        assertTrue(pack.hasItem("gun", "bujidao:ar_01"));
        assertFalse(pack.hasItem("ammo", "bujidao:ar_01"));
        assertFalse(pack.hasItem("gun", "tacz:ammo_box"));
        assertFalse(pack.hasItem("gun", "missing:gun"));
        assertFalse(pack.hasItem("box", "missing:box"));
        assertFalse(pack.hasItem("unknown", "bujidao:ar_01"));
        assertFalse(pack.hasItem(null, "bujidao:ar_01"));
        assertFalse(pack.hasItem("gun", null));
        assertTrue(pack.networkData().getAsJsonObject("GUN_DATA").has("bujidao:ar_01_data"));
        assertFalse(pack.scripts().isEmpty());
        assertEquals(2, pack.scopeZoomCount("tacz:scope_standard_8x"));
        assertFalse(pack.laserEditable("gun", "tacz:minigun")); assertFalse(pack.laserEditable("gun", "bujidao:ar_01"));
        assertTrue(pack.laserEditable("attachment", "tacz:laser_peq15")); assertTrue(pack.laserEditable("attachment", "tacz:laser_peq6"));
        assertFalse(pack.laserEditable("attachment", "tacz:grip_vertical_ranger"), "A built-in laser may explicitly forbid recoloring");
        assertFalse(pack.laserEditable("ammo", "tacz:9mm"));
        JsonObject network = pack.networkData(); pack.load(new ByteArrayInputStream(bundledData()));
        assertEquals(network, pack.networkData(), "Reload must keep catalog identities and data stable");
    }
    @Test void onlyReplacementGunsAppearInNetworkRecipesAndItemLookup() throws Exception {
        DefaultGunPack pack = new DefaultGunPack(null); pack.load(new ByteArrayInputStream(bundledData()));
        JsonObject network = pack.networkData();
        assertEquals(GUN_IDS, network.getAsJsonObject("GUN_INDEX").keySet());
        Set<String> craftedGuns = new HashSet<>();
        Set<String> gunRecipes = new HashSet<>();
        pack.recipes().forEach((id, recipe) -> {
            JsonObject result = recipe.getAsJsonObject("result");
            if (result.get("type").getAsString().equals("gun")) {
                gunRecipes.add(id);
                assertTrue(craftedGuns.add(result.get("id").getAsString()), "Each replacement gun needs one recipe");
            }
        });
        assertEquals(GUN_IDS, craftedGuns);
        assertEquals(GUN_IDS.stream().map(id -> id.replace("bujidao:", "bujidao:gun/")).collect(java.util.stream.Collectors.toSet()), gunRecipes);
        for (String oldId : List.of("tacz:ak47", "tacz:m4a1", "tacz:minigun", "tacz:ai_awp")) {
            assertFalse(pack.hasItem("gun", oldId), "Removed default gun must not be given or resolved: " + oldId);
            assertNull(pack.gun(oldId));
        }
        JsonObject ar = pack.gun("bujidao:ar_01");
        assertEquals("tacz:762x39", ar.get("ammo").getAsString());
        assertEquals(30, ar.get("ammo_amount").getAsInt());
        assertEquals(600, ar.get("rpm").getAsInt());
    }
    @Test void invalidPackDoesNotLoadSilently() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream ignored = new ZipOutputStream(bytes)) {}
        assertThrows(IOException.class, () -> new DefaultGunPack(null).load(new ByteArrayInputStream(bytes.toByteArray())));
    }
    @Test void namespacesRemainDistinctAcrossDataDisplaysScriptsAndCompatibilityTags() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String namespace : List.of("alpha", "beta")) {
            String root = "nested-pack/data/" + namespace + "/";
            entries.put(root + "index/guns/rifle.json", "{name:'Rifle',type:'rifle',data:'" + namespace + ":rifle_data',display:'" + namespace + ":nested/rifle'}");
            entries.put(root + "data/guns/rifle_data.json", "{ammo:'alpha:round',rpm:600,ammo_amount:30,fire_mode:['semi']}");
            entries.put(root + "index/attachments/scope.json", "{name:'Scope',type:'scope',data:'" + namespace + ":scope_data',display:'" + namespace + ":nested/scope'}");
            entries.put(root + "data/attachments/scope_data.json", "{}");
            entries.put(root + "scripts/sample.lua", "return '" + namespace + "'");
            entries.put(root + "tacz_tags/attachments/allow_attachments/rifle.json", "['" + namespace + ":scope']");
            entries.put("assets/" + namespace + "/display/guns/nested/rifle.json", "{laser:{can_edit:" + namespace.equals("alpha") + "}}");
            entries.put("nested-pack/assets/" + namespace + "/display/attachments/nested/scope.json", namespace.equals("alpha") ? "{zoom:[2,4]}" : "{zoom:[8]}");
        }
        entries.put("data/alpha/index/ammo/round.json", "{name:'Round',display:'alpha:round',stack_size:64}");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        DefaultGunPack pack = new DefaultGunPack(null); pack.load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(Set.of("alpha:rifle", "beta:rifle"), pack.gunIndexes().keySet());
        assertEquals(2, pack.scopeZoomCount("alpha:scope")); assertEquals(1, pack.scopeZoomCount("beta:scope"));
        assertTrue(pack.laserEditable("gun", "alpha:rifle")); assertFalse(pack.laserEditable("gun", "beta:rifle"));
        assertEquals("return 'alpha'", pack.scripts().get("alpha:sample"));
        assertEquals("return 'beta'", pack.scripts().get("beta:sample"));
        assertTrue(pack.allowedAttachment("alpha:rifle", "alpha:scope"));
        assertFalse(pack.allowedAttachment("alpha:rifle", "beta:scope"));
        assertTrue(pack.allowedAttachment("beta:rifle", "beta:scope"));
        assertTrue(pack.networkData().getAsJsonObject("ATTACHMENT_TAGS").has("alpha:allow_attachments/rifle"));
        assertTrue(pack.networkData().getAsJsonObject("ATTACHMENT_TAGS").has("beta:allow_attachments/rifle"));
    }
}
