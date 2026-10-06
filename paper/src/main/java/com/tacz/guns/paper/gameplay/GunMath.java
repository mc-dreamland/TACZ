package com.tacz.guns.paper.gameplay;

import com.google.gson.*;

/** Shared, deterministic gun-data calculations; all values originate on the server. */
public final class GunMath {
    private GunMath() {}
    static JsonObject object(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }
    static String text(JsonObject o, String key, String fallback) {
        try { return o.has(key) ? o.get(key).getAsString() : fallback; } catch (RuntimeException ex) { return fallback; }
    }
    static double number(JsonObject o, String key, double fallback) {
        try { double v = o.get(key).getAsDouble(); return Double.isFinite(v) ? v : fallback; } catch (RuntimeException ex) { return fallback; }
    }
    static boolean flag(JsonObject o, String key, boolean fallback) {
        try { return o.has(key) ? o.get(key).getAsBoolean() : fallback; } catch (RuntimeException ex) { return fallback; }
    }
    static long millis(JsonObject o, String key, double fallback) { return (long) (clamp(number(o, key, fallback), 0, 120) * 1000); }
    static int integer(JsonObject o, String key, int fallback) { return (int) number(o, key, fallback); }
    static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    public static double damageAt(JsonObject bullet, double distance, int pellets) {
        JsonObject extra = object(bullet, "extra_damage");
        double damage = number(bullet, "damage", 5);
        if (extra.has("damage_adjust") && extra.get("damage_adjust").isJsonArray()) {
            damage = 0;
            for (JsonElement element : extra.getAsJsonArray("damage_adjust")) {
                if (!element.isJsonObject()) continue;
                JsonObject pair = element.getAsJsonObject();
                double limit = "infinite".equals(text(pair, "distance", "")) ? Double.POSITIVE_INFINITY : number(pair, "distance", 0);
                if (distance < limit) { damage = number(pair, "damage", 0); break; }
            }
        }
        return Math.max(0, damage) / Math.max(pellets, 1);
    }

    public static boolean validCharge(JsonObject charge, double claimed, long elapsedMillis, double previous) {
        if (!Double.isFinite(claimed) || claimed < 0) return false;
        if (charge.isEmpty()) return claimed <= .001;
        double max = Math.max(0, number(charge, "max_charge", 1));
        double minimum = "hold".equals(text(charge, "type", "hold")) ? number(charge, "fire_threshold", max) : max;
        double possible = Math.min(max, previous + (Math.max(0, elapsedMillis) / 50.0 + 4) * Math.max(0, number(charge, "increase_per_tick", 0)));
        return claimed + .001 >= Math.min(minimum, max) && claimed <= possible + .001;
    }
}
