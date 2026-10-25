package com.tacz.guns.paper.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Server-only cosmetic IDs. The resource is also the source for the Geyser mapping generator. */
public final class ItemModelData {
    private static final JsonObject DEFAULTS = loadDefaults();

    private ItemModelData() { }

    private static JsonObject loadDefaults() {
        try (var stream = ItemModelData.class.getResourceAsStream("/default-modeldata.json")) {
            if (stream == null) throw new IllegalStateException("Missing default-modeldata.json");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load default model data", e); }
    }

    public static String key(String kind, String id) { return kind + "/" + id; }

    public static int defaultValue(String kind, String id) {
        JsonObject values = DEFAULTS.getAsJsonObject(kind);
        return values != null && values.has(id) ? values.get(id).getAsInt() : 0;
    }

    public static Integer parse(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        try {
            int number = value.getAsBigDecimal().intValueExact();
            if (number < 0 || (double) (float) number != number) throw invalid();
            return number == 0 ? null : number;
        } catch (ArithmeticException e) { throw invalid(); }
    }

    public static Integer ammoBox(ConfigurationSection config, int level) {
        String path = "ammo-box.modeldata." + level;
        if (config == null || !config.isSet(path)) return defaultValue("box", DefaultGunPack.BOX_IDS.get(level));
        Object value = config.get(path);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(path + ": " + invalid().getMessage());
        try { return parse(new JsonPrimitive(number)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(path + ": " + e.getMessage(), e); }
    }

    public static void validateAmmoBoxes(ConfigurationSection config) {
        for (int level = 0; level < DefaultGunPack.BOX_IDS.size(); level++) ammoBox(config, level);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("modeldata must be a non-negative 32-bit integer exactly representable as a float (0 disables it)");
    }
}
