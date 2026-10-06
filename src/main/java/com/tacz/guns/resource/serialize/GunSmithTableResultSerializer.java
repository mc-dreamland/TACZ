package com.tacz.guns.resource.serialize;

import com.tacz.guns.util.CraftingHelper;
import com.google.gson.*;
import com.tacz.guns.GunMod;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.crafting.RecipeCompat;
import com.tacz.guns.crafting.result.RawGunTableResult;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.pojo.data.recipe.GunResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.lang.reflect.Type;


public class GunSmithTableResultSerializer implements JsonDeserializer<GunSmithTableResult> {
    @Override
    public GunSmithTableResult deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonObject()) {
            JsonObject jsonObject = json.getAsJsonObject();
            String typeName = GsonHelper.getAsString(jsonObject, "type");
            int count = 1;
            CompoundTag extraTag = null;
            ResourceLocation tabOverride = null;
            if (jsonObject.has("count")) {
                count = Math.max(GsonHelper.getAsInt(jsonObject, "count"), 1);
            }
            if (jsonObject.has("nbt")) {
                extraTag = CraftingHelper.getNBT(jsonObject.get("nbt"));
            }
            if (jsonObject.has("group")) {
                String raw = GsonHelper.getAsString(jsonObject, "group");
                if (!raw.contains(":")) {
                    raw = GunMod.MOD_ID + ":" + raw;
                }
                tabOverride = ResourceLocation.parse(raw);
            }

            GunSmithTableResult result;
            switch (typeName) {
                case GunSmithTableResult.GUN, GunSmithTableResult.AMMO, GunSmithTableResult.ATTACHMENT -> {
                    RawGunTableResult raw = new RawGunTableResult(typeName, getId(jsonObject), count);
                    if (extraTag != null) {
                        raw.setNbt(extraTag);
                    }
                    if (typeName.equals(GunSmithTableResult.GUN)) {
                        GunResult gunResult = CommonAssetsManager.GSON.fromJson(jsonObject, GunResult.class);
                        if (gunResult != null) {
                            raw.setExtraData(gunResult);
                        }
                    }

                    result = new GunSmithTableResult(raw, tabOverride);
                }
                case GunSmithTableResult.CUSTOM -> {
                    result = new GunSmithTableResult(normalizeCustomResultJson(jsonObject).deepCopy(), tabOverride);
                }
                default -> {
                    throw new JsonSyntaxException("Unknown or invalid gun-smith result: " + json);
                }
            }
            return result.withRecipeJson(jsonObject);
        }
        throw new JsonSyntaxException("Unknown or invalid gun-smith result: " + json);
    }

    private ResourceLocation getId(JsonObject jsonObject) {
        return ResourceLocation.parse(GsonHelper.getAsString(jsonObject, "id"));
    }

    /** Accepts legacy nested item objects, shorthand item ids and modern component results. */
    private static JsonObject normalizeCustomResultJson(JsonObject jsonObject) {
        JsonElement itemElement = jsonObject.get("item");
        JsonObject inner = itemElement != null && itemElement.isJsonObject()
                ? itemElement.getAsJsonObject() : jsonObject;
        JsonElement itemId = inner.has("id") ? inner.get("id") : inner.get("item");
        if (itemId == null || !itemId.isJsonPrimitive() || !itemId.getAsJsonPrimitive().isString()) {
            throw new JsonSyntaxException("Custom gun-smith result requires an item id: " + jsonObject);
        }
        JsonObject normalized = new JsonObject();
        normalized.add("id", itemId);
        for (String field : new String[]{"count", "nbt", "components"}) {
            JsonElement value = inner.has(field) ? inner.get(field) : jsonObject.get(field);
            if (value != null) normalized.add(field, value.deepCopy());
        }
        return RecipeCompat.normalizeLegacyResult(normalized).getAsJsonObject();
    }
}
