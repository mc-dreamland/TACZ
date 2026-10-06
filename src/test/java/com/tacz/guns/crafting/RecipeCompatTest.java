package com.tacz.guns.crafting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.resource.serialize.GunSmithTableIngredientSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCompatTest {
    @Test
    void legacyNbtAndExistingComponentsAreBothRetained() {
        JsonObject legacy = JsonParser.parseString("""
                {"item":"tacz:modern_kinetic_gun","count":2,
                 "nbt":"{GunId:'tacz:ak47',GunCurrentAmmoCount:30}",
                 "components":{"minecraft:custom_data":{"GunCurrentAmmoCount":7,"Skin":"gold"},
                               "minecraft:custom_model_data":{"floats":[1]}}}
                """).getAsJsonObject();
        JsonObject normalized = RecipeCompat.normalizeLegacyResult(legacy).getAsJsonObject();
        assertEquals("tacz:modern_kinetic_gun", normalized.get("id").getAsString());
        assertEquals(2, normalized.get("count").getAsInt());
        JsonObject components = normalized.getAsJsonObject("components");
        JsonObject data = components.getAsJsonObject("minecraft:custom_data");
        assertEquals("tacz:ak47", data.get("GunId").getAsString());
        assertEquals(7, data.get("GunCurrentAmmoCount").getAsInt());
        assertEquals("gold", data.get("Skin").getAsString());
        assertTrue(components.has("minecraft:custom_model_data"));
        assertTrue(legacy.has("nbt"), "normalization must not mutate its input");
    }

    @Test
    void strictAndPartialLegacyIngredientsAcceptSnbt() {
        for (String type : new String[]{"forge:nbt", "forge:partial_nbt"}) {
            JsonObject source = new JsonObject();
            source.addProperty("type", type);
            source.addProperty("item", "tacz:modern_kinetic_gun");
            source.addProperty("nbt", "{GunId:\"tacz:ak47\"}");
            JsonObject normalized = RecipeCompat.normalizeLegacyIngredient(source).getAsJsonObject();
            assertEquals("tacz:partial_nbt", normalized.get("neoforge:ingredient_type").getAsString());
            assertEquals(source.get("nbt"), normalized.get("nbt"));
            assertEquals("forge:nbt".equals(type), normalized.has("strict"));
        }
    }

    @Test
    void lazyMaterialPreservesTheOriginalIngredientAndCount() {
        JsonObject source = JsonParser.parseString("""
                {"item":{"tag":"c:ingots/copper"},"count":12}
                """).getAsJsonObject();
        GunSmithTableIngredient ingredient = new GunSmithTableIngredientSerializer()
                .deserialize(source, GunSmithTableIngredient.class, null);
        assertEquals(12, ingredient.getCount());
        assertEquals(source, ingredient.toJson());
    }
    @Test
    void legacyConventionTagsUseNeoForgeSharedTags() {
        String[][] names = {{"ingots/iron", "ingots/iron"}, {"rods/blaze", "rods/blaze"},
                {"ores/netherite_scrap", "ores/netherite_scrap"}, {"glass", "glass_blocks"},
                {"gunpowder", "gunpowders"}, {"leather", "leathers"}};
        for (String[] mapping : names) {
            JsonObject legacy = new JsonObject();
            legacy.addProperty("tag", "forge:" + mapping[0]);
            assertEquals("#c:" + mapping[1], RecipeCompat.normalizeLegacyIngredient(legacy).getAsString());
            assertEquals("#c:" + mapping[1], RecipeCompat.normalizeLegacyIngredient(
                    new com.google.gson.JsonPrimitive("#forge:" + mapping[0])).getAsString());
        }
        assertEquals("#forge:my_pack_custom", RecipeCompat.normalizeLegacyIngredient(
                new com.google.gson.JsonPrimitive("#forge:my_pack_custom")).getAsString());
    }

}
