package com.tacz.guns.crafting;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.resource.CommonAssetsManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.ArrayList;
import java.util.List;

/** Preserves the complete gun-pack recipe JSON while registries and gun indexes load. */
public final class GunSmithTableSerializer {
    private static final Codec<JsonElement> JSON_CODEC = Codec.PASSTHROUGH.xmap(
            dynamic -> dynamic.convert(JsonOps.INSTANCE).getValue(), json -> new Dynamic<>(JsonOps.INSTANCE, json));
    private static final Codec<GunSmithTableResult> RESULT_CODEC = JSON_CODEC.flatXmap(
            json -> parse(json, GunSmithTableResult.class), value -> encode(value::toJson));
    private static final Codec<GunSmithTableIngredient> INGREDIENT_CODEC = JSON_CODEC.flatXmap(
            json -> parse(json, GunSmithTableIngredient.class), value -> encode(value::toJson));

    public static final MapCodec<GunSmithTableRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.optionalFieldOf("recipe_id", ResourceLocation.fromNamespaceAndPath("tacz", "unbound_recipe"))
                    .forGetter(GunSmithTableRecipe::getId),
            RESULT_CODEC.fieldOf("result").forGetter(GunSmithTableRecipe::getResult),
            INGREDIENT_CODEC.listOf().fieldOf("materials").forGetter(GunSmithTableRecipe::getInputs)
    ).apply(instance, GunSmithTableRecipe::new));

    private static <T> DataResult<T> parse(JsonElement json, Class<T> type) {
        try {
            T value = CommonAssetsManager.GSON.fromJson(json, type);
            return value == null ? DataResult.error(() -> "Missing " + type.getSimpleName()) : DataResult.success(value);
        } catch (RuntimeException exception) {
            return DataResult.error(() -> "Invalid " + type.getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static DataResult<JsonElement> encode(java.util.function.Supplier<JsonElement> encoder) {
        try {
            return DataResult.success(encoder.get());
        } catch (RuntimeException exception) {
            return DataResult.error(() -> "Cannot encode gun-smith recipe: " + exception.getMessage());
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, GunSmithTableRecipe> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GunSmithTableRecipe decode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation recipeId = buffer.readResourceLocation();
            int size = buffer.readVarInt();
            if (size < 0 || size > 1024) throw new IllegalArgumentException("Invalid gun-smith material count: " + size);
            List<GunSmithTableIngredient> ingredients = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                ingredients.add(new GunSmithTableIngredient(Ingredient.CONTENTS_STREAM_CODEC.decode(buffer), buffer.readVarInt()));
            }
            ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
            ResourceLocation group = buffer.readResourceLocation();
            return new GunSmithTableRecipe(recipeId, new GunSmithTableResult(result, group), ingredients);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, GunSmithTableRecipe recipe) {
            recipe.init();
            buffer.writeResourceLocation(recipe.getId());
            buffer.writeVarInt(recipe.getInputs().size());
            for (GunSmithTableIngredient ingredient : recipe.getInputs()) {
                Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ingredient.getIngredientOrThrow());
                buffer.writeVarInt(ingredient.getCount());
            }
            ItemStack.STREAM_CODEC.encode(buffer, recipe.getResult().getResult());
            buffer.writeResourceLocation(recipe.getResult().getGroup());
        }
    };

    private GunSmithTableSerializer() {}

    public static RecipeSerializer<GunSmithTableRecipe> create() {
        return new RecipeSerializer<>() {
            @Override public MapCodec<GunSmithTableRecipe> codec() { return CODEC; }
            @Override public StreamCodec<RegistryFriendlyByteBuf, GunSmithTableRecipe> streamCodec() { return STREAM_CODEC; }
        };
    }
}
