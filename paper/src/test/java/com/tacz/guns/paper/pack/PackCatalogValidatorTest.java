package com.tacz.guns.paper.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PackCatalogValidatorTest {
    private static JsonObject bundledNetwork;
    private static String akDataId;
    private static String fmjDataId;

    @BeforeAll static void fixture() throws IOException {
        DefaultGunPack pack = new DefaultGunPack(null);
        try (InputStream input = DefaultGunPack.class.getResourceAsStream("/default-pack.zip")) {
            assertNotNull(input, "Validate the same complete bundled pack shipped by processResources");
            pack.load(input);
        }
        bundledNetwork = pack.networkData();
        akDataId = pack.gunIndexes().get("tacz:ak47").get("data").getAsString();
        fmjDataId = pack.attachmentIndexes().get("tacz:ammo_mod_fmj").get("data").getAsString();
    }

    @Test void completeBundledCatalogPassesWithoutDroppingAnyCategory() {
        Catalog catalog = catalog();
        assertTrue(catalog.objects.get("GUN_INDEX").size() >= 50);
        assertTrue(catalog.objects.get("ATTACHMENT_INDEX").size() >= 90);
        assertFalse(catalog.objects.get("RECIPES").isEmpty());
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
    }

    @TestFactory Stream<DynamicTest> unsafeGunValuesAreRejectedWithTheAffectedResource() {
        return Stream.of(
                gunCase("zero RPM", gun -> gun.addProperty("rpm", 0)),
                gunCase("RPM beyond scheduler limit", gun -> gun.addProperty("rpm", 2401)),
                gunCase("fractional RPM", gun -> gun.addProperty("rpm", 600.5)),
                gunCase("zero magazine", gun -> gun.addProperty("ammo_amount", 0)),
                gunCase("missing magazine capacity", gun -> gun.remove("ammo_amount")),
                gunCase("incomplete extended capacities", gun -> gun.add("extended_mag_ammo_amount", json("[40,50]"))),
                gunCase("duplicate fire mode", gun -> gun.add("fire_mode", json("[\"auto\",\"auto\"]"))),
                gunCase("unknown fire mode", gun -> gun.add("fire_mode", json("[\"rapid\"]"))),
                gunCase("unknown bolt", gun -> gun.addProperty("bolt", "automatic")),
                gunCase("unsupported server script", gun -> gun.addProperty("script", "example:custom_lua")),
                gunCase("negative bullet damage", gun -> gun.getAsJsonObject("bullet").addProperty("damage", -1)),
                gunCase("stationary bullet", gun -> gun.getAsJsonObject("bullet").addProperty("speed", 0)),
                gunCase("missing ammunition", gun -> gun.addProperty("ammo", "example:missing")),
                gunCase("unsupported manual reload", gun -> gun.getAsJsonObject("reload").addProperty("type", "manual")),
                gunCase("unsupported fuel reload", gun -> gun.getAsJsonObject("reload").addProperty("type", "fuel")),
                gunCase("string infinite flag", gun -> gun.getAsJsonObject("reload").addProperty("infinite", "true")),
                gunCase("unrecognized infinite range", gun -> damageRange(gun, "forever"))
        ).map(test -> DynamicTest.dynamicTest(test.name, () -> {
            Catalog catalog = catalog();
            test.change.accept(catalog.objects.get("GUN_DATA").get(akDataId));
            rejected(catalog, "GUN_DATA", akDataId);
        }));
    }

    @TestFactory Stream<DynamicTest> invalidIndexesAttachmentsAndRecipesFailBeforePublishing() {
        return Stream.of(
                new InvalidCase("missing gun data", "GUN_INDEX", "tacz:ak47", value -> value.addProperty("data", "example:missing")),
                new InvalidCase("invalid display identifier", "GUN_INDEX", "tacz:ak47", value -> value.addProperty("display", "Uppercase:Gun")),
                new InvalidCase("blank name", "AMMO_INDEX", "tacz:762x39", value -> value.addProperty("name", " ")),
                new InvalidCase("unsafe ammunition stack size", "AMMO_INDEX", "tacz:762x39", value -> value.addProperty("stack_size", 65)),
                new InvalidCase("missing attachment data", "ATTACHMENT_INDEX", "tacz:ammo_mod_fmj", value -> value.addProperty("data", "example:missing")),
                new InvalidCase("unsupported attachment slot", "ATTACHMENT_INDEX", "tacz:ammo_mod_fmj", value -> value.addProperty("type", "ammo")),
                new InvalidCase("unsupported attachment formula", "ATTACHMENT_DATA", fmjDataId,
                        value -> value.getAsJsonObject("damage").addProperty("function", "y = x * 987")),
                new InvalidCase("invalid extended magazine level", "ATTACHMENT_DATA", fmjDataId, value -> value.addProperty("extended_mag_level", 4)),
                new InvalidCase("empty recipe materials", "RECIPES", "tacz:gun/ak47", value -> value.add("materials", new JsonArray())),
                new InvalidCase("zero recipe ingredient count", "RECIPES", "tacz:gun/ak47",
                        value -> value.getAsJsonArray("materials").get(0).getAsJsonObject().addProperty("count", 0)),
                new InvalidCase("ambiguous recipe ingredient", "RECIPES", "tacz:gun/ak47",
                        value -> value.getAsJsonArray("materials").get(0).getAsJsonObject().add("item", json("{\"item\":\"minecraft:iron_ingot\",\"tag\":\"minecraft:logs\"}"))),
                new InvalidCase("missing recipe result", "RECIPES", "tacz:gun/ak47", value -> value.getAsJsonObject("result").addProperty("id", "example:missing")),
                new InvalidCase("unbounded recipe output count", "RECIPES", "tacz:gun/ak47", value -> value.getAsJsonObject("result").addProperty("count", 4097)),
                new InvalidCase("unsupported recipe type", "RECIPES", "tacz:gun/ak47", value -> value.addProperty("type", "minecraft:crafting_shaped"))
        ).map(test -> DynamicTest.dynamicTest(test.name, () -> {
            Catalog catalog = catalog();
            test.change.accept(catalog.objects.get(test.category).get(test.id));
            rejected(catalog, test.category, test.id);
        }));
    }

    @Test void symbolicInfiniteDamageRangeAndInventoryFeedingRemainSupported() {
        Catalog catalog = catalog();
        JsonObject gun = catalog.objects.get("GUN_DATA").get(akDataId);
        damageRange(gun, "infinite");
        gun.remove("ammo_amount");
        gun.getAsJsonObject("reload").addProperty("type", "inventory");
        gun.getAsJsonObject("reload").addProperty("infinite", true);
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
    }

    @Test void nestedNonFiniteNumbersCannotBypassValidationInOptionalProperties() {
        for (JsonElement number : List.of(json("1e1000"), new com.google.gson.JsonPrimitive(Double.NaN),
                new com.google.gson.JsonPrimitive(Double.POSITIVE_INFINITY))) {
            Catalog catalog = catalog();
            JsonObject nested = new JsonObject();
            JsonArray array = new JsonArray();
            array.add(number);
            nested.add("nested", array);
            catalog.objects.get("GUN_DATA").get(akDataId).add("optional_extension", nested);
            IOException error = rejected(catalog, "GUN_DATA", akDataId);
            assertTrue(error.getMessage().contains("non-finite"));
        }
    }

    @Test void cyclicTagsAreLegalButDanglingTagsAndAttachmentLeavesAreNot() {
        Catalog catalog = catalog();
        catalog.tags.put("example:a", json("[\"#example:b\",\"tacz:ammo_mod_fmj\"]").getAsJsonArray());
        catalog.tags.put("example:b", json("[\"#example:a\",\"tacz:ammo_mod_slug\"]").getAsJsonArray());
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
        catalog.tags.put("example:a", json("[\"#example:missing\"]").getAsJsonArray());
        rejected(catalog, "ATTACHMENT_TAGS", "example:a");
        catalog.tags.put("example:a", json("[\"example:missing_attachment\"]").getAsJsonArray());
        rejected(catalog, "ATTACHMENT_TAGS", "example:a");
    }

    @Test void bodyPartMultipliersAreOptionalAndAllowZeroAndUpperBound() {
        Catalog catalog = catalog();
        JsonObject extra = catalog.objects.get("GUN_DATA").get(akDataId).getAsJsonObject("bullet").getAsJsonObject("extra_damage");
        extra.remove("head_shot_multiplier");
        extra.remove("body_part_multipliers");
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
        extra.addProperty("head_shot_multiplier", 100);
        extra.add("body_part_multipliers", json("{\"torso\":0,\"legs\":100}"));
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
        extra.addProperty("head_shot_multiplier", 0);
        extra.add("body_part_multipliers", json("{\"legs\":0.65}"));
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
        extra.add("body_part_multipliers", new JsonObject());
        assertDoesNotThrow(() -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
    }

    @TestFactory Stream<DynamicTest> invalidHitRegionConfigurationIsRejectedBeforeReload() {
        return Stream.of(
                new InvalidRegion("negative head multiplier", "head_shot_multiplier", json("-0.1")),
                new InvalidRegion("head multiplier above limit", "head_shot_multiplier", json("100.01")),
                new InvalidRegion("head multiplier string", "head_shot_multiplier", json("\"2\"")),
                new InvalidRegion("null head multiplier", "head_shot_multiplier", json("null")),
                new InvalidRegion("non-finite head multiplier", "head_shot_multiplier", new com.google.gson.JsonPrimitive(Double.NaN)),
                new InvalidRegion("array body multipliers", "body_part_multipliers", json("[1,1]")),
                new InvalidRegion("null body multipliers", "body_part_multipliers", json("null")),
                new InvalidRegion("string body multipliers", "body_part_multipliers", json("\"1\"")),
                new InvalidRegion("duplicate head channel", "body_part_multipliers", json("{\"head\":2}")),
                new InvalidRegion("unknown body region", "body_part_multipliers", json("{\"arms\":1}")),
                new InvalidRegion("case-sensitive body region", "body_part_multipliers", json("{\"TORSO\":1}")),
                new InvalidRegion("negative torso multiplier", "body_part_multipliers", json("{\"torso\":-1}")),
                new InvalidRegion("legs multiplier above limit", "body_part_multipliers", json("{\"legs\":101}")),
                new InvalidRegion("torso multiplier string", "body_part_multipliers", json("{\"torso\":\"0.8\"}")),
                new InvalidRegion("legs multiplier boolean", "body_part_multipliers", json("{\"legs\":true}")),
                new InvalidRegion("legs multiplier null", "body_part_multipliers", json("{\"legs\":null}")),
                new InvalidRegion("non-finite legs multiplier", "body_part_multipliers", json("{\"legs\":1e1000}"))
        ).map(test -> DynamicTest.dynamicTest(test.name, () -> {
            Catalog catalog = catalog();
            JsonObject extra = catalog.objects.get("GUN_DATA").get(akDataId).getAsJsonObject("bullet").getAsJsonObject("extra_damage");
            extra.add(test.field, test.value.deepCopy());
            rejected(catalog, "GUN_DATA", akDataId);
        }));
    }

    private static void damageRange(JsonObject gun, String distance) {
        JsonObject bullet = gun.getAsJsonObject("bullet");
        JsonObject extra = bullet.has("extra_damage") ? bullet.getAsJsonObject("extra_damage") : new JsonObject();
        JsonObject point = new JsonObject();
        point.addProperty("distance", distance);
        point.addProperty("damage", 10);
        JsonArray points = new JsonArray();
        points.add(point);
        extra.add("damage_adjust", points);
        bullet.add("extra_damage", extra);
    }

    private static GunCase gunCase(String name, Consumer<JsonObject> change) { return new GunCase(name, change); }
    private static JsonElement json(String value) { return JsonParser.parseString(value); }
    private static IOException rejected(Catalog catalog, String category, String id) {
        IOException error = assertThrows(IOException.class, () -> PackCatalogValidator.validate(catalog.objects, catalog.tags));
        assertTrue(error.getMessage().contains(category), error.getMessage());
        assertTrue(error.getMessage().contains(id), error.getMessage());
        return error;
    }

    private static Catalog catalog() {
        Map<String, Map<String, JsonObject>> objects = new TreeMap<>();
        Map<String, JsonArray> tags = new TreeMap<>();
        for (var category : bundledNetwork.entrySet()) {
            if (category.getKey().equals("ALLOW_ATTACHMENT_TAGS")) continue;
            if (category.getKey().equals("ATTACHMENT_TAGS")) {
                category.getValue().getAsJsonObject().entrySet().forEach(entry -> tags.put(entry.getKey(), json(entry.getValue().getAsString()).getAsJsonArray()));
            } else {
                Map<String, JsonObject> values = new TreeMap<>();
                category.getValue().getAsJsonObject().entrySet().forEach(entry -> values.put(entry.getKey(), json(entry.getValue().getAsString()).getAsJsonObject()));
                objects.put(category.getKey(), values);
            }
        }
        return new Catalog(objects, tags);
    }

    private record Catalog(Map<String, Map<String, JsonObject>> objects, Map<String, JsonArray> tags) { }
    private record GunCase(String name, Consumer<JsonObject> change) { }
    private record InvalidCase(String name, String category, String id, Consumer<JsonObject> change) { }
    private record InvalidRegion(String name, String field, JsonElement value) { }
}
