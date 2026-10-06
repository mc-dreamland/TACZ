package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Selects one multiplier from the effective bullet data after attachment modifiers are applied. */
public final class HitRegionDamage {
    private HitRegionDamage() { }

    public static double multiplier(JsonObject bullet, HitZones.Region region) {
        if (region == null) return 1;
        JsonObject extra = GunMath.object(bullet, "extra_damage");
        JsonElement configured = switch (region) {
            // Existing head_shot attachment modifiers already affect this field.
            case HEAD -> extra.get("head_shot_multiplier");
            case TORSO -> GunMath.object(extra, "body_part_multipliers").get("torso");
            case LEGS -> GunMath.object(extra, "body_part_multipliers").get("legs");
        };
        if (configured == null || !configured.isJsonPrimitive() || !configured.getAsJsonPrimitive().isNumber()) return 1;
        try {
            double value = configured.getAsDouble();
            return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 1;
        } catch (RuntimeException invalid) {
            return 1;
        }
    }
}
