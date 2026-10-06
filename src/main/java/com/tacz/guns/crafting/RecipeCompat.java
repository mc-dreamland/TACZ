package com.tacz.guns.crafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.tacz.guns.GunMod;
import com.tacz.guns.util.CraftingHelper;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.Map;
import java.util.Set;

/** Converts legacy gun-pack recipes to the Minecraft 1.21.10 component and ingredient formats. */
public final class RecipeCompat {
    private static final Gson LENIENT_GSON = new GsonBuilder().setStrictness(Strictness.LENIENT).create();
    private static final String RECIPE_DIR = "recipe";
    private static final String LEGACY_RECIPES_DIR = "recipes";

    private RecipeCompat() {
    }

    /** Bare group names get the tacz namespace, matching upstream GunSmithTableResult#decode. */
    public static ResourceLocation parseGroup(String raw) {
        return ResourceLocation.parse(raw.contains(":") ? raw : GunMod.MOD_ID + ":" + raw);
    }

    /**
     * {@code {"tag":"forge:ingots/iron"}} → {@code "#forge:ingots/iron"};
     * {@code {"item":"minecraft:flint"}} → {@code "minecraft:flint"}.
     */
    private static final Set<String> SUPPORTED_FORGE_INGREDIENTS =
            Set.of("forge:partial_nbt", "forge:nbt");

    /**
     * The Forge-era gun packs in the wild use {@code forge:partial_nbt} and {@code forge:nbt}.
     * NeoForge 1.21.10 has an equivalent native custom ingredient, registered as
     * {@code tacz:partial_nbt}; convert only these two known types and leave every unknown
     * custom type untouched rather than guessing its semantics.
     */
    private static JsonElement normalizeCustomIngredient(JsonObject source) {
        JsonElement typeElement = source.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString()
                || !SUPPORTED_FORGE_INGREDIENTS.contains(typeElement.getAsString())) {
            return source;
        }

        JsonElement itemsElement = source.get("items");
        JsonArray items = new JsonArray();
        if (itemsElement != null && itemsElement.isJsonArray()) {
            itemsElement.getAsJsonArray().forEach(items::add);
        } else if (source.has("item") && source.get("item").isJsonPrimitive()) {
            items.add(source.get("item"));
        } else {
            return source;
        }

        JsonElement nbt = source.get("nbt");
        if (nbt == null || !(nbt.isJsonObject() || (nbt.isJsonPrimitive() && nbt.getAsJsonPrimitive().isString()))) {
            return source;
        }

        JsonObject normalized = new JsonObject();
        normalized.addProperty("neoforge:ingredient_type", "tacz:partial_nbt");
        normalized.add("items", items);
        normalized.add("nbt", nbt.deepCopy());
        if ("forge:nbt".equals(typeElement.getAsString())) {
            normalized.addProperty("strict", true);
        }
        return normalized;
    }

    public static JsonElement normalizeLegacyIngredient(JsonElement raw) {
        if (raw.isJsonObject()) {
            JsonObject obj = raw.getAsJsonObject();
            if (obj.has("type")) {
                return normalizeCustomIngredient(obj);
            }
            JsonElement tag = obj.get("tag");
            if (tag != null && tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) {
                return new JsonPrimitive("#" + normalizeLegacyTag(tag.getAsString()));
            }
            JsonElement item = obj.get("item");
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                return item;
            }
            return raw;
        }
        if (raw.isJsonArray()) {
            JsonArray src = raw.getAsJsonArray();
            JsonArray out = new JsonArray(src.size());
            boolean changed = false;
            for (JsonElement e : src) {
                JsonElement n = normalizeLegacyIngredient(e);
                changed |= n != e;
                out.add(n);
            }
            return changed ? out : raw;
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString() && raw.getAsString().startsWith("#forge:")) {
            return new JsonPrimitive("#" + normalizeLegacyTag(raw.getAsString().substring(1)));
        }
        return raw;
    }

    /** Standard Forge convention tags moved to the shared c namespace in NeoForge. */
    private static String normalizeLegacyTag(String tag) {
        if (!tag.startsWith("forge:")) return tag;
        String path = tag.substring("forge:".length());
        String modern = switch (path) {
            case "glass" -> "glass_blocks";
            case "glass/colorless" -> "glass_blocks/colorless";
            case "glass/tinted" -> "glass_blocks/tinted";
            case "gunpowder" -> "gunpowders";
            case "leather" -> "leathers";
            default -> null;
        };
        if (modern != null) return "c:" + modern;
        for (String family : new String[]{"dusts", "gems", "ingots", "nuggets", "ores", "rods", "storage_blocks", "glass_panes"}) {
            if (path.equals(family) || path.startsWith(family + "/")) return "c:" + path;
        }
        // An addon may own a nonstandard forge-namespaced tag; leave its meaning intact.
        return tag;
    }

    public static boolean isLegacyRecipesDirectory(String path) {
        return path.contains("/recipes/") && !path.contains("/recipe/");
    }

    /**
     * {@code ResourceManager#listResources} path argument for the recipe registry:
     * exact {@code recipe} or a subdirectory {@code recipe/...}. Does not match
     * {@code recipe_filters} / {@code recipe_priorities}.
     */
    public static boolean isVanillaRecipeDirectory(String paths) {
        return RECIPE_DIR.equals(paths) || paths.startsWith(RECIPE_DIR + "/");
    }

    /**
     * {@code recipe} → {@code recipes}; {@code recipe/ammo} → {@code recipes/ammo}.
     */
    @Nullable
    public static String toLegacyRecipesDirectory(String paths) {
        if (!isVanillaRecipeDirectory(paths)) {
            return null;
        }
        return LEGACY_RECIPES_DIR + paths.substring(RECIPE_DIR.length());
    }

    /**
     * Pack resource id {@code ns:recipes/oldworkbench.json} → {@code ns:recipe/oldworkbench.json}.
     */
    @Nullable
    public static ResourceLocation remapRecipesToRecipe(ResourceLocation location) {
        String path = location.getPath();
        if (!path.equals(LEGACY_RECIPES_DIR) && !path.startsWith(LEGACY_RECIPES_DIR + "/")) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(
                location.getNamespace(),
                RECIPE_DIR + path.substring(LEGACY_RECIPES_DIR.length()));
    }

    /**
     * Inverse of {@link #remapRecipesToRecipe}: {@code ns:recipe/foo.json} → {@code ns:recipes/foo.json}.
     */
    @Nullable
    public static ResourceLocation toLegacyRecipesLocation(ResourceLocation location) {
        String path = location.getPath();
        if (!path.equals(RECIPE_DIR) && !path.startsWith(RECIPE_DIR + "/")) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(
                location.getNamespace(),
                LEGACY_RECIPES_DIR + path.substring(RECIPE_DIR.length()));
    }

    public static boolean isVanillaCraftingRecipe(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonElement typeEl = element.getAsJsonObject().get("type");
        if (typeEl == null || !typeEl.isJsonPrimitive() || !typeEl.getAsJsonPrimitive().isString()) {
            return false;
        }
        String type = typeEl.getAsString();
        return "minecraft:crafting_shaped".equals(type) || "minecraft:crafting_shapeless".equals(type);
    }

    /**
     * Rewrite a 1.20-era vanilla crafting JSON into the 1.21.10 codec form.
     * Returns {@code null} when the element is not a shaped/shapeless crafting recipe
     * (gun-smith-table recipes stay on the {@code recipes/} path and are handled by
     * {@code TableRecipeManager}).
     */
    @Nullable
    public static JsonElement convertLegacyVanillaRecipe(@Nullable JsonElement element) {
        if (!isVanillaCraftingRecipe(element)) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject().deepCopy();
        if (obj.has("result")) {
            obj.add("result", normalizeLegacyResult(obj.get("result")));
        }
        if (obj.has("key") && obj.get("key").isJsonObject()) {
            JsonObject key = obj.getAsJsonObject("key");
            JsonObject newKey = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : key.entrySet()) {
                newKey.add(entry.getKey(), normalizeLegacyIngredient(entry.getValue()));
            }
            obj.add("key", newKey);
        }
        if (obj.has("ingredients")) {
            obj.add("ingredients", normalizeLegacyIngredient(obj.get("ingredients")));
        }
        return obj;
    }

    public static JsonElement normalizeLegacyResult(JsonElement result) {
        if (!result.isJsonObject()) {
            return result;
        }
        JsonObject source = result.getAsJsonObject();
        boolean hasLegacyItem = source.has("item");
        boolean hasLegacyNbt = source.has("nbt");
        if (!hasLegacyItem && !hasLegacyNbt) {
            return source;
        }
        JsonObject out = new JsonObject();
        if (source.has("id")) {
            out.add("id", source.get("id"));
        } else if (hasLegacyItem && source.get("item").isJsonPrimitive()) {
            out.addProperty("id", source.get("item").getAsString());
        }
        if (source.has("count")) {
            out.add("count", source.get("count"));
        }
        JsonObject components = source.has("components") ? source.getAsJsonObject("components").deepCopy() : new JsonObject();
        if (hasLegacyNbt) {
            CompoundTag customData = CraftingHelper.getNBT(source.get("nbt"));
            if (components.has("minecraft:custom_data")) {
                // Explicit modern component values take precedence over their legacy counterparts.
                customData.merge(CraftingHelper.getNBT(components.get("minecraft:custom_data")));
            }
            components.add("minecraft:custom_data", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, customData));
        }
        if (!components.isEmpty()) out.add("components", components);
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("item".equals(key) || "nbt".equals(key) || "id".equals(key)
                    || "count".equals(key) || "components".equals(key)) {
                continue;
            }
            if (!out.has(key)) {
                out.add(key, entry.getValue());
            }
        }
        return out;
    }

    public static JsonElement parseLenient(Reader reader) {
        JsonReader jsonReader = LENIENT_GSON.newJsonReader(reader);
        jsonReader.setStrictness(Strictness.LENIENT);
        return LENIENT_GSON.fromJson(jsonReader, JsonElement.class);
    }
}
