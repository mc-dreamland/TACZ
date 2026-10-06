package com.tacz.guns.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class BridgeItemIdentityTest {
    private static final String INSTANCE = "f73c9a2e-0519-4d72-8727-0187d1b7aa92";
    private static final Map<String, Set<String>> CATALOG = Map.of(
            "gun", Set.of("tacz:ak47", "server_pack:rifles/ak47"),
            "ammo", Set.of("tacz:762x39", "server_pack:762x39"),
            "attachment", Set.of("tacz:ammo_mod_fmj", "server_pack:ammo_mod_fmj"),
            "box", Set.copyOf(BridgeItemIdentity.BOX_IDS));
    private static final BiPredicate<String, String> KNOWN = (kind, id) -> CATALOG.getOrDefault(kind, Set.of()).contains(id);

    @Test void gunWithoutAnyModelNumberRetainsIdentityAndState() {
        JsonObject item = item("gun", "tacz:ak47");
        item.addProperty("ammo", 23);
        item.addProperty("chamber", true);
        item.addProperty("fireMode", "auto");
        JsonObject attachments = new JsonObject();
        attachments.addProperty("extended_mag", "tacz:ammo_mod_fmj");
        item.add("attachments", attachments);
        JsonObject parsed = parse(item, "minecraft:stick");
        assertNotNull(parsed);
        assertEquals(item, parsed);
        assertFalse(parsed.has("cmd"));
    }

    @Test void staleWrongAndNonnumericLegacyCmdFieldsDoNotParticipateInIdentity() {
        JsonObject item = item("gun", "tacz:ak47");
        for (JsonPrimitive oldValue : new JsonPrimitive[]{new JsonPrimitive(-1), new JsonPrimitive(9999999), new JsonPrimitive("old-plugin-model")}) {
            item.add("cmd", oldValue);
            JsonObject parsed = parse(item, "minecraft:stick");
            assertNotNull(parsed);
            assertEquals("tacz:ak47", parsed.get("id").getAsString());
            assertEquals(oldValue, parsed.get("cmd"));
        }
    }

    @Test void ordinaryUnknownAndMismatchedCarriersAreNotBridgeItems() {
        assertNull(BridgeItemIdentity.parse(null, "minecraft:stick", KNOWN));
        assertNull(BridgeItemIdentity.parse("", "minecraft:stick", KNOWN));
        assertNull(BridgeItemIdentity.parse("{}", "minecraft:stick", KNOWN));
        assertNull(parse(item("gun", "tacz:removed"), "minecraft:stick"));
        assertNull(parse(item("ammo", "tacz:ak47"), "minecraft:paper"));
        assertNull(parse(item("gun", "tacz:ak47"), "minecraft:paper"));
        assertNull(parse(item("gun", "tacz:ak47"), "other:stick"));
        assertNull(parse(item("anything", "tacz:ak47"), "minecraft:stick"));
        assertNull(parse(item("box", "server_pack:unknown_box"), "minecraft:chest"));
    }

    @Test void customNamespacesAndStackableAmmoAttachmentsDoNotNeedAnInstance() {
        assertNotNull(parse(item("gun", "server_pack:rifles/ak47"), "minecraft:stick"));
        for (String kind : new String[]{"ammo", "attachment"}) {
            String id = kind.equals("ammo") ? "server_pack:762x39" : "server_pack:ammo_mod_fmj";
            JsonObject item = item(kind, id);
            item.remove("instance");
            assertNotNull(parse(item, BridgeItemIdentity.material(kind)));
        }
    }

    @Test void gunsAndBoxesRequireCanonicalUuidShape() {
        for (String kind : new String[]{"gun", "box"}) {
            JsonObject item = item(kind, kind.equals("gun") ? "tacz:ak47" : "tacz:ammo_box");
            assertNotNull(parse(item, BridgeItemIdentity.material(kind)));
            item.addProperty("instance", INSTANCE.toUpperCase(java.util.Locale.ROOT));
            assertNotNull(parse(item, BridgeItemIdentity.material(kind)));
            for (String bad : new String[]{"", "gun-instance", "1-1-1-1-1", "f73c9a2e-0519-4d72-8727-0187d1b7aa9z"}) {
                item.addProperty("instance", bad);
                assertNull(parse(item, BridgeItemIdentity.material(kind)), bad);
            }
            item.addProperty("instance", 123);
            assertNull(parse(item, BridgeItemIdentity.material(kind)));
            item.remove("instance");
            assertNull(parse(item, BridgeItemIdentity.material(kind)));
        }
    }

    @Test void kindsAndIdsMustBeStringsWithExplicitBoundedResourceIdentifiers() {
        JsonObject item = item("gun", "tacz:ak47");
        item.addProperty("kind", true);
        assertNull(parse(item, "minecraft:stick"));
        item.addProperty("kind", "gun");
        item.addProperty("id", 123);
        assertNull(parse(item, "minecraft:stick"));
        for (String id : new String[]{"ak47", "TACZ:ak47", "tacz:AK47", "tacz:", ":ak47", "tacz:ak 47", "a:" + "x".repeat(255)}) {
            item.addProperty("id", id);
            assertNull(BridgeItemIdentity.parse(item.toString(), "minecraft:stick", (kind, candidate) -> true), id);
        }
    }

    @Test void rejectsMalformedLenientDuplicateAndTrailingJson() {
        String valid = item("gun", "tacz:ak47").toString();
        for (String raw : new String[]{"null", "[]", "{broken", valid + "{}", valid.substring(0, valid.length() - 1),
                "{kind:'gun',id:'tacz:ak47',instance:'" + INSTANCE + "'}",
                valid.replace("\"kind\":\"gun\"", "\"kind\":\"ammo\",\"kind\":\"gun\""),
                valid.replace("\"id\"", "/* comment */\"id\"")}) {
            assertNull(BridgeItemIdentity.parse(raw, "minecraft:stick", KNOWN), raw);
        }
    }

    @Test void oversizedAndDeepMetadataFailWithoutRecursingIntoUnboundedJson() {
        JsonObject item = item("gun", "tacz:ak47");
        item.addProperty("label", "x".repeat(16_384));
        assertNull(parse(item, "minecraft:stick"));
        item.addProperty("label", "枪".repeat(6000));
        assertTrue(item.toString().length() < 16_384, "The byte limit must also cover multibyte metadata");
        assertNull(parse(item, "minecraft:stick"));
        String valid = item("gun", "tacz:ak47").toString();
        String nested = valid.substring(0, valid.length() - 1) + ",\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}";
        assertDoesNotThrow(() -> assertNull(BridgeItemIdentity.parse(nested, "minecraft:stick", KNOWN)));
    }

    @Test void quotedBracketsEscapesAndUnicodeAreOrdinaryMetadata() {
        JsonObject item = item("gun", "tacz:ak47");
        item.addProperty("label", "枪械 \"[" + "{}[]".repeat(100) + "\\\"");
        assertEquals(item, parse(item, "minecraft:stick"));
    }

    @Test void allFixedBoxesUseChestAndKnownKindsHaveStableMaterials() {
        assertEquals("minecraft:stick", BridgeItemIdentity.material("gun"));
        assertEquals("minecraft:paper", BridgeItemIdentity.material("ammo"));
        assertEquals("minecraft:flint", BridgeItemIdentity.material("attachment"));
        assertNull(BridgeItemIdentity.material(null));
        assertNull(BridgeItemIdentity.material("unknown"));
        assertEquals(3, BridgeItemIdentity.BOX_IDS.size());
        for (String box : BridgeItemIdentity.BOX_IDS) assertNotNull(parse(item("box", box), "minecraft:chest"));
    }

    private static JsonObject item(String kind, String id) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        item.addProperty("id", id);
        item.addProperty("instance", INSTANCE);
        return item;
    }

    private static JsonObject parse(JsonObject item, String material) {
        return BridgeItemIdentity.parse(item.toString(), material, KNOWN);
    }
}
