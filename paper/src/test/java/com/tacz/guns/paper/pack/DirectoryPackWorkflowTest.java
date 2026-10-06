package com.tacz.guns.paper.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Administrator workflows through the public loader, using the complete bundled data. */
class DirectoryPackWorkflowTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SOURCE = Path.of("../src/main/resources/assets/tacz/custom/tacz_default_gun");
    private static byte[] bundle;
    private static DefaultGunPack defaults;

    @TempDir Path directory;

    @BeforeAll static void fixture() throws Exception {
        bundle = zip(Map.of());
        defaults = new DefaultGunPack(null);
        defaults.load(new ByteArrayInputStream(bundle));
    }

    @Test void firstLoadExportsEveryDefaultItemAndRecipeWithItsOriginalBehaviorAndCmd() throws Exception {
        DefaultGunPack pack = loadDirectory();
        assertTrue(Files.isRegularFile(directory.resolve(".pack-files-v1")));
        assertTrue(Files.isRegularFile(directory.resolve("model-mappings.json")));
        assertEquals(defaults.gunIndexes().keySet(), pack.gunIndexes().keySet());
        assertEquals(defaults.ammoIndexes().keySet(), pack.ammoIndexes().keySet());
        assertEquals(defaults.attachmentIndexes().keySet(), pack.attachmentIndexes().keySet());
        assertEquals(defaults.recipes().keySet(), pack.recipes().keySet());
        Map<String, JsonObject> guns = documents("guns"), ammos = documents("ammos");
        Map<String, JsonObject> attachments = documents("attachments"), recipes = documents("recipes");
        assertEquals(defaults.gunIndexes().keySet(), guns.keySet());
        assertEquals(defaults.ammoIndexes().keySet(), ammos.keySet());
        assertEquals(defaults.attachmentIndexes().keySet(), attachments.keySet());
        assertEquals(defaults.recipes().keySet(), recipes.keySet());
        for (String id : defaults.gunIndexes().keySet()) {
            JsonObject file = guns.get(id);
            assertEquals(defaults.gun(id), file.getAsJsonObject("data"), id);
            assertTrue(file.get("allow_attachments").isJsonArray(), id);
            assertEquals(defaults.gun(id), pack.gun(id), id);
            assertEquals(defaults.customModelData("gun", id), pack.customModelData("gun", id), id);
        }
        for (String id : defaults.ammoIndexes().keySet()) {
            assertEquals(defaults.ammoIndexes().get(id), ammos.get(id).getAsJsonObject("index"), id);
            assertEquals(defaults.customModelData("ammo", id), pack.customModelData("ammo", id), id);
        }
        for (String id : defaults.attachmentIndexes().keySet()) {
            assertEquals(defaults.attachment(id), attachments.get(id).getAsJsonObject("data"), id);
            assertEquals(defaults.attachment(id), pack.attachment(id), id);
            assertEquals(defaults.customModelData("attachment", id), pack.customModelData("attachment", id), id);
        }
        for (String id : defaults.recipes().keySet())
            assertEquals(defaults.recipes().get(id), recipes.get(id).getAsJsonObject("recipe"), id);
        assertEquals(defaults.scripts(), pack.scripts(), "Directory numeric configuration must retain the supported bundled scripts");
        assertEquals(3_000_002, pack.customModelData("gun", "tacz:ak47"));
        assertTrue(pack.allowedAttachment("tacz:ak47", "tacz:ammo_mod_fmj"));
        assertTrue(pack.attachmentHasTag("tacz:ammo_mod_slug", "tacz:intrinsic/slug"));
    }

    @Test void editingAndMovingAkIntoArbitraryNestedFoldersChangesServerAndNetworkDataWithoutChangingIdentity() throws Exception {
        DefaultGunPack pack = loadDirectory();
        int cmd = pack.customModelData("gun", "tacz:ak47");
        Path file = fileFor("guns", "tacz:ak47");
        JsonObject gun = read(file);
        gun.getAsJsonObject("data").addProperty("rpm", 780);
        gun.getAsJsonObject("data").addProperty("ammo_amount", 27);
        gun.getAsJsonObject("data").getAsJsonObject("bullet").addProperty("damage", 41.5);
        write(file, gun);
        reload(pack);
        assertAkChanges(pack, cmd);
        Path moved = directory.resolve("guns/my-server/rifles/season-2/any-file-name.json");
        Files.createDirectories(moved.getParent());
        Files.move(file, moved);
        reload(pack);
        assertAkChanges(pack, cmd);
        assertFalse(Files.exists(file), "Reload must not restore the default location of a moved item");
        assertEquals(moved, fileFor("guns", "tacz:ak47"));
    }

    @Test void ammoAttachmentAndCraftingEditsReachTheSameSynchronizedCatalog() throws Exception {
        DefaultGunPack pack = loadDirectory();
        Path ammoFile = fileFor("ammos", "tacz:762x39");
        JsonObject ammo = read(ammoFile);
        ammo.getAsJsonObject("index").addProperty("stack_size", 37);
        write(ammoFile, ammo);
        Path attachmentFile = fileFor("attachments", "tacz:ammo_mod_fmj");
        JsonObject attachment = read(attachmentFile);
        attachment.getAsJsonObject("data").getAsJsonObject("damage").addProperty("multiplier", .72);
        write(attachmentFile, attachment);
        Path recipeFile = fileFor("recipes", "tacz:gun/ak47");
        JsonObject recipe = read(recipeFile);
        recipe.getAsJsonObject("recipe").getAsJsonArray("materials").get(0).getAsJsonObject().addProperty("count", 3);
        recipe.getAsJsonObject("recipe").getAsJsonObject("result").addProperty("count", 2);
        write(recipeFile, recipe);
        reload(pack);
        assertEquals(37, pack.ammoIndexes().get("tacz:762x39").get("stack_size").getAsInt());
        assertEquals(.72, pack.attachment("tacz:ammo_mod_fmj").getAsJsonObject("damage").get("multiplier").getAsDouble(), .00001);
        assertEquals(3, pack.recipes().get("tacz:gun/ak47").getAsJsonArray("materials").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(2, pack.recipes().get("tacz:gun/ak47").getAsJsonObject("result").get("count").getAsInt());
        assertEquals(pack.ammoIndexes().get("tacz:762x39"), network(pack, "AMMO_INDEX", "tacz:762x39"));
        String attachmentDataId = pack.attachmentIndexes().get("tacz:ammo_mod_fmj").get("data").getAsString();
        assertEquals(pack.attachment("tacz:ammo_mod_fmj"), network(pack, "ATTACHMENT_DATA", attachmentDataId));
        assertEquals(pack.recipes().get("tacz:gun/ak47"), network(pack, "RECIPES", "tacz:gun/ak47"));
    }

    @Test void twoDefaultItemsSharingBundledDataBecomeIndependentlyEditableFiles() throws Exception {
        String first = "tacz:stock_m4ss", second = "tacz:stock_tactical_ar";
        JsonObject secondIndex = defaults.attachmentIndexes().get(second).deepCopy();
        secondIndex.addProperty("data", defaults.attachmentIndexes().get(first).get("data").getAsString());
        byte[] shared = zip(Map.of("data/tacz/index/attachments/stock_tactical_ar.json", secondIndex));
        DefaultGunPack pack = new DefaultGunPack(null);
        pack.load(new ByteArrayInputStream(shared), directory);
        assertEquals(pack.attachment(first), pack.attachment(second));
        JsonObject untouched = pack.attachment(second).deepCopy();
        Path firstFile = fileFor("attachments", first);
        JsonObject edited = read(firstFile);
        edited.getAsJsonObject("data").addProperty("weight", 1.25);
        write(firstFile, edited);
        pack.load(new ByteArrayInputStream(shared), directory);
        assertEquals(1.25, pack.attachment(first).get("weight").getAsDouble(), .00001);
        assertEquals(untouched, pack.attachment(second), "Changing one exported item must not mutate its formerly shared source");
        String firstData = pack.attachmentIndexes().get(first).get("data").getAsString();
        String secondData = pack.attachmentIndexes().get(second).get("data").getAsString();
        assertNotEquals(firstData, secondData);
        assertEquals(pack.attachment(first), network(pack, "ATTACHMENT_DATA", firstData));
        assertEquals(untouched, network(pack, "ATTACHMENT_DATA", secondData));
    }

    @Test void newNamespaceWithTheSameItemPathUsesItsOwnAmmoTagsRecipeAndCarrierMapping() throws Exception {
        DefaultGunPack pack = loadDirectory();
        JsonObject ammo = read(fileFor("ammos", "tacz:762x39"));
        ammo.addProperty("id", "example:762x39");
        write(directory.resolve("ammos/custom/deep/ammo.json"), ammo);
        JsonObject attachment = read(fileFor("attachments", "tacz:ammo_mod_fmj"));
        attachment.addProperty("id", "example:ammo_mod_fmj");
        write(directory.resolve("attachments/custom/fmj.json"), attachment);
        JsonObject tag = new JsonObject();
        tag.addProperty("id", "example:my_family");
        tag.add("values", JsonParser.parseString("[\"example:ammo_mod_fmj\",\"#tacz:intrinsic/slug\"]"));
        write(directory.resolve("attachment_tags/custom/family.json"), tag);
        JsonObject gun = read(fileFor("guns", "tacz:ak47"));
        gun.addProperty("id", "example:ak47");
        gun.getAsJsonObject("data").addProperty("ammo", "example:762x39");
        gun.add("allow_attachments", JsonParser.parseString("[\"#example:my_family\"]"));
        write(directory.resolve("guns/custom/rifle.json"), gun);
        JsonObject recipe = read(fileFor("recipes", "tacz:gun/ak47"));
        recipe.addProperty("id", "example:gun/ak47");
        recipe.getAsJsonObject("recipe").getAsJsonObject("result").addProperty("id", "example:ak47");
        write(directory.resolve("recipes/custom/rifle.json"), recipe);
        reload(pack);
        assertEquals("example:762x39", pack.gun("example:ak47").get("ammo").getAsString());
        assertNotNull(pack.gun("tacz:ak47"));
        assertTrue(pack.allowedAttachment("example:ak47", "example:ammo_mod_fmj"));
        assertTrue(pack.allowedAttachment("example:ak47", "tacz:ammo_mod_slug"));
        assertFalse(pack.allowedAttachment("tacz:ak47", "example:ammo_mod_fmj"));
        assertTrue(pack.attachmentHasTag("example:ammo_mod_fmj", "example:my_family"));
        assertEquals("example:ak47", pack.recipes().get("example:gun/ak47").getAsJsonObject("result").get("id").getAsString());
        assertNotEquals(pack.customModelData("gun", "tacz:ak47"), pack.customModelData("gun", "example:ak47"));
        assertTrue(pack.customModelData("gun", "example:ak47") >= 3_000_000);
        assertTrue(pack.customModelData("ammo", "example:762x39") >= 3_100_000);
        assertTrue(pack.customModelData("attachment", "example:ammo_mod_fmj") >= 3_200_000);
        Set<Integer> commands = new HashSet<>();
        for (JsonElement row : pack.mappings()) assertTrue(commands.add(row.getAsJsonObject().get("cmd").getAsInt()));
        assertEquals(pack.gun("example:ak47"), network(pack, "GUN_DATA", pack.gunIndexes().get("example:ak47").get("data").getAsString()));
        JsonArray clientAllow = JsonParser.parseString(pack.networkData().getAsJsonObject("ATTACHMENT_TAGS")
                .get("example:allow_attachments/ak47").getAsString()).getAsJsonArray();
        assertTrue(clientAllow.contains(JsonParser.parseString("\"example:ammo_mod_fmj\"")), "Client compatibility tags must include custom namespace items");
    }

    @Test void deletionAndReadditionKeepTombstonedCmdsAndDoNotRestoreRemovedConfiguration() throws Exception {
        DefaultGunPack pack = loadDirectory();
        JsonObject first = read(fileFor("guns", "tacz:ak47"));
        first.addProperty("id", "example:removed");
        Path firstFile = directory.resolve("guns/custom/removed.json");
        write(firstFile, first);
        reload(pack);
        int originalCmd = pack.customModelData("gun", "example:removed");
        Files.delete(firstFile);
        Path deletedRecipe = fileFor("recipes", "tacz:gun/ak47");
        Files.delete(deletedRecipe);
        pack = loadDirectory(); // A new loader instance models a server restart, not just /reload.
        assertNull(pack.gun("example:removed"));
        assertEquals(-1, pack.customModelData("gun", "example:removed"));
        assertFalse(pack.recipes().containsKey("tacz:gun/ak47"));
        assertFalse(Files.exists(firstFile));
        assertFalse(Files.exists(deletedRecipe));
        JsonObject second = first.deepCopy();
        second.addProperty("id", "example:new_item");
        write(directory.resolve("guns/custom/new-item.json"), second);
        reload(pack);
        assertNotEquals(originalCmd, pack.customModelData("gun", "example:new_item"), "A deleted identity's model number must stay reserved");
        write(directory.resolve("guns/a/new/place/restored.json"), first);
        reload(pack);
        assertEquals(originalCmd, pack.customModelData("gun", "example:removed"));
        assertFalse(Files.exists(firstFile));
        assertFalse(Files.exists(deletedRecipe));
    }

    @Test void cyclicCompatibilityTagsRemainUsableAndAreFlattenedBeforeClientSynchronization() throws Exception {
        DefaultGunPack pack = loadDirectory();
        JsonObject first = new JsonObject();
        first.addProperty("id", "example:cycle_a");
        first.add("values", JsonParser.parseString("[\"#example:cycle_b\",\"tacz:ammo_mod_fmj\"]"));
        write(directory.resolve("attachment_tags/cycles/a.json"), first);
        JsonObject second = new JsonObject();
        second.addProperty("id", "example:cycle_b");
        second.add("values", JsonParser.parseString("[\"#example:cycle_a\",\"tacz:ammo_mod_slug\"]"));
        write(directory.resolve("attachment_tags/cycles/deep/b.json"), second);
        Path gunFile = fileFor("guns", "tacz:ak47");
        JsonObject gun = read(gunFile);
        gun.add("allow_attachments", JsonParser.parseString("[\"#example:cycle_a\"]"));
        write(gunFile, gun);
        reload(pack);
        assertTrue(pack.allowedAttachment("tacz:ak47", "tacz:ammo_mod_fmj"));
        assertTrue(pack.allowedAttachment("tacz:ak47", "tacz:ammo_mod_slug"));
        JsonObject network = pack.networkData();
        for (String id : new String[]{"example:cycle_a", "example:cycle_b", "tacz:allow_attachments/ak47"}) {
            JsonArray leaves = JsonParser.parseString(network.getAsJsonObject("ATTACHMENT_TAGS").get(id).getAsString()).getAsJsonArray();
            Set<String> values = new HashSet<>();
            for (JsonElement value : leaves) {
                assertFalse(value.getAsString().startsWith("#"), "The original client's recursive matcher has no cycle guard");
                assertTrue(values.add(value.getAsString()), "Flattening must deduplicate repeated leaves");
            }
            assertEquals(Set.of("tacz:ammo_mod_fmj", "tacz:ammo_mod_slug"), values);
        }
        JsonArray allowed = JsonParser.parseString(network.getAsJsonObject("ALLOW_ATTACHMENT_TAGS").get("tacz:ak47").getAsString()).getAsJsonArray();
        assertEquals(2, allowed.size());
        for (JsonElement entry : allowed) assertFalse(entry.getAsString().startsWith("#"));
    }

    @Test void firstExportPreservesAnExistingEditedFileInsteadOfCreatingADuplicateDefault() throws Exception {
        JsonObject gun = new JsonObject();
        gun.addProperty("id", "tacz:ak47");
        gun.add("index", defaults.gunIndexes().get("tacz:ak47").deepCopy());
        gun.add("data", defaults.gun("tacz:ak47").deepCopy());
        gun.getAsJsonObject("data").addProperty("rpm", 840);
        gun.add("allow_attachments", JsonParser.parseString("[\"tacz:ammo_mod_fmj\"]"));
        Path existing = directory.resolve("guns/private/rifles/server-ak.json");
        write(existing, gun);
        String before = Files.readString(existing);
        DefaultGunPack pack = loadDirectory();
        assertEquals(840, pack.gun("tacz:ak47").get("rpm").getAsInt());
        assertEquals(before, Files.readString(existing));
        assertEquals(existing, fileFor("guns", "tacz:ak47"));
        reload(pack);
        assertEquals(before, Files.readString(existing));
        assertEquals(840, pack.gun("tacz:ak47").get("rpm").getAsInt());
    }

    @Test void duplicateAndMalformedFilesLeaveTheActiveCatalogAndRegistryUnchanged() throws Exception {
        DefaultGunPack pack = loadDirectory();
        Path original = fileFor("guns", "tacz:ak47");
        Path duplicate = directory.resolve("guns/another/deep/copy.json");
        write(duplicate, read(original));
        assertRejectedWithoutReplacingActive(pack);
        Files.delete(duplicate);
        Path malformed = directory.resolve("attachments/bad.json");
        Files.writeString(malformed, "{broken", StandardCharsets.UTF_8);
        assertRejectedWithoutReplacingActive(pack);
        Files.delete(malformed);
        reload(pack);
        assertNotNull(pack.gun("tacz:ak47"));
    }

    @Test void illegalValuesAndMissingReferencesCannotPartiallyPublishOrAllocateNewModels() throws Exception {
        DefaultGunPack pack = loadDirectory();
        Path original = fileFor("guns", "tacz:ak47");
        JsonObject valid = read(original);
        JsonObject newGun = valid.deepCopy();
        newGun.addProperty("id", "example:pending_allocation");
        write(directory.resolve("guns/custom/pending.json"), newGun);
        JsonObject bad = valid.deepCopy();
        bad.getAsJsonObject("data").addProperty("rpm", 0);
        write(original, bad);
        assertRejectedWithoutReplacingActive(pack);
        assertEquals(-1, pack.customModelData("gun", "example:pending_allocation"));
        bad = valid.deepCopy();
        bad.getAsJsonObject("data").addProperty("ammo", "missing:no_ammo");
        write(original, bad);
        assertRejectedWithoutReplacingActive(pack);
        bad = valid.deepCopy();
        bad.add("allow_attachments", JsonParser.parseString("[\"#missing:no_tag\"]"));
        write(original, bad);
        assertRejectedWithoutReplacingActive(pack);
        write(original, valid);
        reload(pack);
        assertNotNull(pack.gun("example:pending_allocation"));
        assertTrue(pack.customModelData("gun", "example:pending_allocation") >= 0);
    }

    private void assertAkChanges(DefaultGunPack pack, int cmd) {
        JsonObject gun = pack.gun("tacz:ak47");
        assertEquals(780, gun.get("rpm").getAsInt());
        assertEquals(27, gun.get("ammo_amount").getAsInt());
        assertEquals(41.5, gun.getAsJsonObject("bullet").get("damage").getAsDouble(), .00001);
        assertEquals(cmd, pack.customModelData("gun", "tacz:ak47"));
        assertEquals(gun, network(pack, "GUN_DATA", pack.gunIndexes().get("tacz:ak47").get("data").getAsString()));
    }

    private void assertRejectedWithoutReplacingActive(DefaultGunPack pack) throws Exception {
        JsonObject network = pack.networkData();
        JsonArray mappings = pack.mappings();
        JsonObject gun = pack.gun("tacz:ak47").deepCopy();
        byte[] registry = Files.readAllBytes(directory.resolve("model-mappings.json"));
        assertThrows(IOException.class, () -> reload(pack));
        assertEquals(network, pack.networkData());
        assertEquals(mappings, pack.mappings());
        assertEquals(gun, pack.gun("tacz:ak47"));
        assertArrayEquals(registry, Files.readAllBytes(directory.resolve("model-mappings.json")));
    }

    private DefaultGunPack loadDirectory() throws IOException {
        DefaultGunPack pack = new DefaultGunPack(null);
        reload(pack);
        return pack;
    }
    private void reload(DefaultGunPack pack) throws IOException { pack.load(new ByteArrayInputStream(bundle), directory); }
    private Map<String, JsonObject> documents(String category) throws IOException {
        Map<String, JsonObject> result = new TreeMap<>();
        try (var paths = Files.walk(directory.resolve(category))) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject entry = read(file);
                assertNull(result.put(entry.get("id").getAsString(), entry), "Duplicate exported identity in " + category);
            }
        }
        return result;
    }
    private Path fileFor(String category, String id) throws IOException {
        ArrayList<Path> found = new ArrayList<>();
        try (var paths = Files.walk(directory.resolve(category))) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject entry = read(file);
                if (entry.has("id") && entry.get("id").getAsString().equals(id)) found.add(file);
            }
        }
        assertEquals(1, found.size(), category + "/" + id + " must have one physical source file");
        return found.getFirst();
    }
    private static JsonObject read(Path file) throws IOException { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
    private static void write(Path file, JsonObject value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.toJson(value), StandardCharsets.UTF_8);
    }
    private static JsonObject network(DefaultGunPack pack, String category, String id) {
        return JsonParser.parseString(pack.networkData().getAsJsonObject(category).get(id).getAsString()).getAsJsonObject();
    }
    private static byte[] zip(Map<String, JsonObject> overrides) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); var files = Files.walk(SOURCE)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.startsWith(SOURCE.resolve("data"))
                    || path.startsWith(SOURCE.resolve("assets/tacz/display/attachments")) || path.startsWith(SOURCE.resolve("assets/tacz/display/guns"))).sorted().toList()) {
                String path = SOURCE.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry("nested-pack/" + path));
                if (overrides.containsKey(path)) zip.write(JSON.toJson(overrides.get(path)).getBytes(StandardCharsets.UTF_8));
                else Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
