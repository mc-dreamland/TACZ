package com.tacz.guns.resource.pojo.data.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record LootTableInjection(List<ResourceLocation> lootTables, LootTable lootTable, ResourceLocation id) {

    public static LootTableInjection fromJson(ResourceLocation fileId, JsonElement element, HolderLookup.Provider registries) {
        // Legacy gun packs store GunId/AmmoId in set_nbt. These custom fields now
        // belong to the custom_data component read by ItemNbtUtils.
        JsonElement migrated = LegacyLootCompat.migrateSetNbt(element);
        JsonObject object = GsonHelper.convertToJsonObject(migrated, "loot injection");
        List<ResourceLocation> lootTables = readLootTables(fileId, object);
        if (!object.has("pools")) {
            throw new JsonParseException("Loot injection " + fileId + " must define pools");
        }

        var parsed = LootTable.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), object);
        var lootTable = parsed.result().orElseThrow(() -> new JsonParseException(
                "Failed to parse loot table " + fileId + ": "
                        + parsed.error().map(error -> error.message()).orElse("unknown codec error")));
        lootTable.setLootTableId(fileId);
        return new LootTableInjection(lootTables, lootTable, fileId);
    }

    private static List<ResourceLocation> readLootTables(ResourceLocation fileId, JsonObject object) {
        List<ResourceLocation> lootTables = new ArrayList<>();
        if (object.has("loot_tables")) {
            for (JsonElement table : GsonHelper.getAsJsonArray(object, "loot_tables")) {
                lootTables.add(ResourceLocation.parse(GsonHelper.convertToString(table, "loot table")));
            }
        } else if (object.has("loot_table")) {
            lootTables.add(ResourceLocation.parse(GsonHelper.getAsString(object, "loot_table")));
        } else {
            throw new JsonParseException("Loot injection " + fileId + " must define loot_table or loot_tables");
        }
        return List.copyOf(lootTables);
    }

    public List<ItemStack> createStacks(LootContext context) {
        List<ItemStack> stacks = new ArrayList<>();
        lootTable.getRandomItemsRaw(context, stacks::add);
        return stacks;
    }

    /**
     * 兼容层：递归遍历 loot JSON，把 {function:"minecraft:set_nbt", tag:"<SNBT>"}
     * 改名成 {function:"minecraft:set_custom_data", tag:"<SNBT>"}。
     * Preserves the custom NBT used by legacy TACZ guns and ammunition.
     */
    static final class LegacyLootCompat {
        static JsonElement migrateSetNbt(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return element;
            }
            if (element.isJsonObject()) {
                JsonObject o = element.getAsJsonObject();
                if (o.has("function") && o.get("function").isJsonPrimitive()
                        && "minecraft:set_nbt".equals(o.get("function").getAsString()) && o.has("tag")) {
                    JsonObject replacement = new JsonObject();
                    replacement.addProperty("function", "minecraft:set_custom_data");
                    replacement.add("tag", o.get("tag"));
                    if (o.has("conditions")) {
                        replacement.add("conditions", o.get("conditions"));
                    }
                    return replacement;
                }
                JsonObject out = new JsonObject();
                for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                    out.add(en.getKey(), migrateSetNbt(en.getValue()));
                }
                return out;
            }
            if (element.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement el : element.getAsJsonArray()) {
                    out.add(migrateSetNbt(el));
                }
                return out;
            }
            return element;
        }
    }
}
