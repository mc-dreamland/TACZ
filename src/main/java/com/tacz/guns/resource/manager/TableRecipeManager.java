package com.tacz.guns.resource.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.GunMod;
import com.tacz.guns.crafting.RecipeCompat;
import com.tacz.guns.init.ModRecipe;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.network.DataType;
import com.tacz.guns.resource.pojo.data.recipe.TableRecipe;
import com.tacz.guns.util.ResourceScanner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public class TableRecipeManager extends CommonDataManager<TableRecipe> {
    private static final String RECIPE_TYPE_ID = resolveRecipeTypeId();
    private static final String LEGACY_RECIPE_DIRECTORY = "recipes";
    private static final FileToIdConverter LEGACY_CONVERTER = FileToIdConverter.json(LEGACY_RECIPE_DIRECTORY);

    public TableRecipeManager() {
        super(DataType.RECIPES, TableRecipe.class, CommonAssetsManager.GSON, "recipe", "TableRecipeLoader");
    }

    @NotNull
    @Override
    protected Map<Identifier, JsonElement> prepare(ResourceManager pResourceManager, ProfilerFiller pProfiler) {
        Map<Identifier, JsonElement> legacy =
                ResourceScanner.scanDirectory(pResourceManager, LEGACY_CONVERTER, CommonAssetsManager.GSON);
        Map<Identifier, JsonElement> current = super.prepare(pResourceManager, pProfiler);
        if (legacy.isEmpty()) {
            return current;
        }
        Map<Identifier, JsonElement> merged = new LinkedHashMap<>(legacy);
        merged.putAll(current);
        GunMod.LOGGER.info(getMarker(),
                "Found {} recipe file(s) in legacy 'recipes/' directory (old gun pack layout), {} in 'recipe/'.",
                legacy.size(), current.size());
        return merged;
    }

    private static String resolveRecipeTypeId() {
        Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(ModRecipe.GUN_SMITH_TABLE_CRAFTING.get());
        return id != null ? id.toString() : GunMod.MOD_ID + ":gun_smith_table_crafting";
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> pObject, ResourceManager pResourceManager, ProfilerFiller pProfiler) {
        Map<Identifier, JsonElement> ours = new LinkedHashMap<>();
        int vanillaLegacy = 0;
        for (Map.Entry<Identifier, JsonElement> entry : pObject.entrySet()) {
            if (isGunSmithTableRecipe(entry.getValue())) {
                ours.put(entry.getKey(), entry.getValue());
            } else if (RecipeCompat.isVanillaCraftingRecipe(entry.getValue())) {
                vanillaLegacy++;
            }
        }
        if (vanillaLegacy > 0) {
            GunMod.LOGGER.info(getMarker(),
                    "Left {} vanilla recipe(s) from 'recipes/' to PackResources remapping (RecipeManager registry).",
                    vanillaLegacy);
        }
        GunMod.LOGGER.debug(getMarker(), "Gun smith table recipes: {} accepted, {} vanilla crafting skipped",
                ours.size(), vanillaLegacy);
        super.apply(ours, pResourceManager, pProfiler);
    }

    private static boolean isGunSmithTableRecipe(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        JsonElement type = object.get("type");
        if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) return false;
        return RECIPE_TYPE_ID.equals(type.getAsString());
    }
}
