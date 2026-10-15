package com.tacz.guns.client.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.bridge.BridgeItemIdentity;
import com.tacz.guns.bridge.BridgeProtocol;
import com.tacz.guns.bridge.BridgeRecipeIngredients;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.crafting.result.RawGunTableResult;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import com.tacz.guns.util.ItemNbtUtils;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display-only recipe views. The Paper transaction still selects and consumes all real items. */
public final class PaperWorkbenchRecipes {
    private PaperWorkbenchRecipes() { }

    public static Map<ResourceLocation, GunSmithTableRecipe> load(Map<ResourceLocation, String> source) {
        Map<ResourceLocation, GunSmithTableRecipe> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                JsonObject recipe = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                List<GunSmithTableIngredient> ingredients = new ArrayList<>();
                for (JsonElement value : recipe.getAsJsonArray("materials")) {
                    JsonObject material = value.getAsJsonObject();
                    ingredients.add(new GunSmithTableIngredient(ingredient(material.get("item")), count(material)));
                }
                JsonObject output = recipe.getAsJsonObject("result");
                String kind = output.get("type").getAsString();
                ResourceLocation id = ResourceLocation.parse(output.get("id").getAsString());
                GunSmithTableResult preview;
                if (kind.equals("box")) {
                    int level = BridgeItemIdentity.BOX_IDS.indexOf(id.toString());
                    if (level < 0) throw new IllegalArgumentException("Unknown ammunition box");
                    ItemStack stack = new ItemStack(ModItems.AMMO_BOX.get(), count(output));
                    ItemNbtUtils.updateTag(stack, tag -> {
                        tag.putInt("Level", level);
                        tag.putString("AmmoId", DefaultAssets.EMPTY_AMMO_ID.toString());
                        tag.putInt("AmmoCount", 0);
                    });
                    preview = new GunSmithTableResult(stack, TabConfig.TAB_MISC);
                } else {
                    if (!kind.equals("gun") && !kind.equals("ammo") && !kind.equals("attachment"))
                        throw new IllegalArgumentException("Unknown recipe output kind");
                    // Paper creates an ordinary catalog item; client-only recipe NBT must not
                    // advertise extra ammunition or attachments that the transaction never grants.
                    ResourceLocation group = null;
                    if (output.has("group")) {
                        String raw = output.get("group").getAsString();
                        group = ResourceLocation.tryParse(raw.contains(":") ? raw : GunMod.MOD_ID + ":" + raw);
                    }
                    preview = new GunSmithTableResult(new RawGunTableResult(kind, id, count(output)), group);
                    preview.init();
                }
                if (preview.getResult().isEmpty()) throw new IllegalArgumentException("Recipe output is unavailable");
                result.put(entry.getKey(), new GunSmithTableRecipe(entry.getKey(), preview, ingredients));
            } catch (RuntimeException invalid) {
                GunMod.LOGGER.warn("Cannot display Paper workbench recipe {}", entry.getKey(), invalid);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static int count(JsonObject object) {
        return object.has("count") ? Math.max(1, object.get("count").getAsInt()) : 1;
    }

    @Nullable
    private static Ingredient ingredient(JsonElement raw) {
        JsonArray alternatives = new JsonArray();
        flatten(raw, alternatives);
        List<Ingredient> parts = new ArrayList<>();
        for (JsonElement alternative : alternatives) {
            try {
                JsonObject object = alternative.getAsJsonObject();
                // Modern ingredients use item IDs or #tag IDs. Preserve Paper's exact tags;
                // the ordinary mod recipe adapter also rewrites Forge tags to Common tags.
                String value = object.has("tag") ? "#" + object.get("tag").getAsString()
                        : object.get("item").getAsString();
                Ingredient part = Ingredient.CODEC.parse(
                        RegistryOps.create(JsonOps.INSTANCE, ItemNbtUtils.getLookupProvider()),
                        new JsonPrimitive(value)).getOrThrow();
                if (part.isEmpty()) return null;
                parts.add(part);
            } catch (RuntimeException unavailable) {
                // A newer Paper item may have no client registry entry. Keep the recipe visible;
                // the screen shows an unknown material and lets the server decide whether it fits.
                return null;
            }
        }
        if (parts.isEmpty()) return null;
        // Resolve item tags only after the server has sent its vanilla tag table at login.
        return Ingredient.of(HolderSet.direct(parts.stream()
                .flatMap(part -> part.getValues().stream()).distinct().toList()));
    }

    private static void flatten(JsonElement raw, JsonArray output) {
        if (raw.isJsonArray()) {
            for (JsonElement entry : raw.getAsJsonArray()) flatten(entry, output);
            return;
        }
        JsonObject object = raw.getAsJsonObject();
        if (object.has("tag")) {
            String tag = object.get("tag").getAsString();
            String item = BridgeRecipeIngredients.FORGE_ITEMS.get(tag);
            if (item != null) { output.add(item(item)); return; }
            if (tag.equals("forge:glass")) {
                BridgeRecipeIngredients.GLASS_ITEMS.forEach(id -> output.add(item(id)));
                return;
            }
        }
        output.add(object.deepCopy());
    }

    private static JsonObject item(String id) {
        JsonObject item = new JsonObject();
        item.addProperty("item", id);
        return item;
    }

    /** Mirrors the server's ordered allocation from storage, excluding every bridge-tagged item. */
    public static Int2IntArrayMap available(GunSmithTableRecipe recipe, Inventory inventory) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && !ItemNbtUtils.getTag(stack).getCompoundOrEmpty("PublicBukkitValues").contains(BridgeProtocol.ITEM_KEY))
                stacks.add(stack);
        }
        int[] remaining = stacks.stream().mapToInt(ItemStack::getCount).toArray();
        Int2IntArrayMap counts = new Int2IntArrayMap(recipe.getInputs().size());
        for (int index = 0; index < recipe.getInputs().size(); index++) {
            GunSmithTableIngredient requirement = recipe.getInputs().get(index);
            Ingredient ingredient = requirement.getIngredient();
            int have = 0, needed = requirement.getCount();
            for (int slot = 0; slot < stacks.size(); slot++) {
                if (remaining[slot] <= 0 || ingredient == null || !ingredient.test(stacks.get(slot))) continue;
                have += remaining[slot];
                int used = Math.min(remaining[slot], needed);
                remaining[slot] -= used;
                needed -= used;
            }
            counts.put(index, have);
        }
        return counts;
    }
}
