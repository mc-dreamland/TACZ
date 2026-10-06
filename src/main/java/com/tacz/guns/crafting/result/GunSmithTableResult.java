package com.tacz.guns.crafting.result;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import com.tacz.guns.GunMod;
import com.tacz.guns.util.CraftingHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A resolved gun-smith recipe result.
 *
 * <p>Gun, ammo and attachment results are deliberately kept as
 * {@link RawGunTableResult} until the common indexes have finished loading. Custom results are
 * kept as JSON for the same reason: constructing an {@link ItemStack} during resource reload can
 * happen before all registries/components are ready. {@link #init()} is idempotent and is called
 * by the client screen and the server menu immediately before the result is used.</p>
 */
public class GunSmithTableResult {
    private static final ResourceLocation EMPTY_GROUP = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "empty");
    public static final String GUN = "gun";
    public static final String AMMO = "ammo";
    public static final String ATTACHMENT = "attachment";
    public static final String CUSTOM = "custom";

    private ItemStack result;
    private ResourceLocation group;
    @Nullable
    private final RawGunTableResult rawResult;
    @Nullable
    private final JsonElement customResult;
    @Nullable
    private final ResourceLocation groupOverride;
    private boolean initialized;
    @Nullable private JsonObject recipeJson;

    public GunSmithTableResult(ItemStack result, @Nullable ResourceLocation group) {
        this.result = result == null ? ItemStack.EMPTY : result;
        this.group = group == null ? EMPTY_GROUP : group;
        this.rawResult = null;
        this.customResult = null;
        this.groupOverride = null;
        this.initialized = true;
    }

    public GunSmithTableResult(RawGunTableResult raw, @Nullable ResourceLocation group) {
        this.result = ItemStack.EMPTY;
        this.group = group == null ? EMPTY_GROUP : group;
        this.rawResult = raw;
        this.customResult = null;
        this.groupOverride = group == null || EMPTY_GROUP.equals(group) ? null : group;
        this.initialized = false;
    }

    public GunSmithTableResult(JsonElement json, @Nullable ResourceLocation group) {
        this.result = ItemStack.EMPTY;
        this.group = group == null ? EMPTY_GROUP : group;
        this.rawResult = null;
        this.customResult = json == null ? null : json.deepCopy();
        this.groupOverride = group;
        this.initialized = false;
    }

    /** Resolves once indexes are ready; invalid results remain visible as loading errors. */
    public synchronized void init() {
        if (initialized) return;
        if (rawResult != null) {
            GunSmithTableResult resolved = RawGunTableResult.init(rawResult);
            this.result = resolved.result;
            this.group = groupOverride == null ? resolved.group : groupOverride;
        } else if (customResult != null && customResult.isJsonObject()) {
            this.result = CraftingHelper.getItemStack(customResult.getAsJsonObject(), true, true);
        }
        if (this.result.isEmpty()) throw new IllegalStateException("Gun-smith recipe resolved to an empty result: " + recipeJson);
        this.initialized = true;
    }

    public GunSmithTableResult withRecipeJson(JsonObject source) {
        this.recipeJson = source.deepCopy();
        return this;
    }

    public JsonElement toJson() {
        if (recipeJson != null) return recipeJson.deepCopy();
        init();
        JsonObject json = new JsonObject();
        json.addProperty("type", CUSTOM);
        json.add("item", ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,
                com.tacz.guns.util.ItemNbtUtils.getLookupProvider()), result).getOrThrow());
        json.addProperty("group", group.toString());
        return json;
    }

    public ItemStack getResult() {
        return result;
    }

    public ResourceLocation getGroup() {
        return group;
    }
}
