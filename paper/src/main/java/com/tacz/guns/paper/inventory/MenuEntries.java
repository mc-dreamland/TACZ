package com.tacz.guns.paper.inventory;

import com.google.gson.*;
import java.util.Map;
import java.util.StringJoiner;

/** Registry-independent menu rows, shared by menu construction and packet-size validation. */
public final class MenuEntries {
    private static final Map<String, String> MATERIAL_NAMES = Map.ofEntries(
            Map.entry("forge:ingots/iron", "铁锭"), Map.entry("forge:ingots/gold", "金锭"),
            Map.entry("forge:ingots/copper", "铜锭"), Map.entry("forge:ingots/netherite", "下界合金锭"),
            Map.entry("forge:gems/diamond", "钻石"), Map.entry("forge:gems/lapis", "青金石"),
            Map.entry("forge:gems/quartz", "下界石英"), Map.entry("forge:gems/amethyst", "紫水晶碎片"),
            Map.entry("forge:dusts/glowstone", "荧石粉"), Map.entry("forge:dusts/redstone", "红石粉"),
            Map.entry("forge:gunpowder", "火药"), Map.entry("forge:leather", "皮革"),
            Map.entry("forge:nuggets/iron", "铁粒"), Map.entry("forge:ores/netherite_scrap", "远古残骸"),
            Map.entry("forge:rods/blaze", "烈焰棒"), Map.entry("forge:glass", "玻璃（含染色玻璃）"),
            Map.entry("minecraft:logs", "任意原木"), Map.entry("minecraft:planks", "任意木板"),
            Map.entry("minecraft:wool", "任意羊毛"), Map.entry("minecraft:crying_obsidian", "哭泣的黑曜石"),
            Map.entry("minecraft:end_crystal", "末影水晶"), Map.entry("minecraft:fire_charge", "火焰弹"));
    private MenuEntries() {}
    public static JsonObject entry(String id, String name, String kind, String description) {
        JsonObject row = new JsonObject(); row.addProperty("id", id); row.addProperty("name", name); row.addProperty("kind", kind); row.addProperty("description", description); return row;
    }
    public static JsonObject recipe(String id, JsonObject recipe) {
        JsonObject result = recipe.getAsJsonObject("result"); StringJoiner text = new StringJoiner("，");
        for (JsonElement element : recipe.getAsJsonArray("materials")) { JsonObject ingredient = element.getAsJsonObject(); text.add(ingredientName(ingredient.get("item")) + " ×" + (ingredient.has("count") ? ingredient.get("count").getAsInt() : 1)); }
        JsonObject row = entry(id, result.get("id").getAsString(), result.get("type").getAsString(), text.toString());
        row.add("itemId", result.get("id"));
        row.addProperty("count", result.has("count") ? result.get("count").getAsInt() : 1); return row;
    }
    private static String ingredientName(JsonElement ingredient) {
        if (ingredient.isJsonArray()) { StringJoiner alternatives = new StringJoiner(" 或 "); ingredient.getAsJsonArray().forEach(value -> alternatives.add(ingredientName(value))); return alternatives.toString(); }
        JsonObject entry = ingredient.getAsJsonObject(); String key = entry.has("item") ? entry.get("item").getAsString() : entry.get("tag").getAsString();
        return MATERIAL_NAMES.getOrDefault(key, key);
    }
}
