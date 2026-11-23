package com.tacz.guns.paper.item;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Keep Java translation keys while providing text for clients without the MOD language data. */
final class PaperItemNames {
    private static final Map<String, String> FALLBACKS = load();

    private PaperItemNames() { }

    static TranslatableComponent name(String key, String fallback) {
        return Component.translatable(key).fallback(FALLBACKS.getOrDefault(key, fallback));
    }

    private static Map<String, String> load() {
        try (var stream = PaperItemNames.class.getResourceAsStream("/default-item-names.json")) {
            if (stream == null) throw new IllegalStateException("Missing default-item-names.json");
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> names = new HashMap<>();
            json.entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(names);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load default item names", exception);
        }
    }
}
