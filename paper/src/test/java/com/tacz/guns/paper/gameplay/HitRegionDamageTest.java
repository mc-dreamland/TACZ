package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tacz.guns.paper.item.AttachmentModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HitRegionDamageTest {
    @Test void absentConfigurationPreservesDamageForEveryRegion() {
        for (HitZones.Region region : HitZones.Region.values()) {
            assertEquals(1, HitRegionDamage.multiplier(null, region));
            assertEquals(1, HitRegionDamage.multiplier(new JsonObject(), region));
            assertEquals(1, HitRegionDamage.multiplier(json("{\"extra_damage\":{}}"), region));
        }
        assertEquals(1, HitRegionDamage.multiplier(new JsonObject(), null));
    }

    @Test void eachRegionSelectsOnlyItsOwnMultiplier() {
        JsonObject bullet = json("{\"extra_damage\":{\"head_shot_multiplier\":2.5,\"body_part_multipliers\":{\"torso\":0.8,\"legs\":0.4}}}");
        JsonObject original = bullet.deepCopy();
        assertEquals(2.5, HitRegionDamage.multiplier(bullet, HitZones.Region.HEAD));
        assertEquals(0.8, HitRegionDamage.multiplier(bullet, HitZones.Region.TORSO));
        assertEquals(0.4, HitRegionDamage.multiplier(bullet, HitZones.Region.LEGS));
        assertEquals(original, bullet, "Reading a region must not change the effective gun data");
    }

    @Test void legacyHeadshotAndPartialBodyConfigurationKeepIndependentDefaults() {
        JsonObject bullet = json("{\"extra_damage\":{\"head_shot_multiplier\":3,\"body_part_multipliers\":{\"legs\":0}}}");
        assertEquals(3, HitRegionDamage.multiplier(bullet, HitZones.Region.HEAD));
        assertEquals(1, HitRegionDamage.multiplier(bullet, HitZones.Region.TORSO));
        assertEquals(0, HitRegionDamage.multiplier(bullet, HitZones.Region.LEGS));
        bullet.getAsJsonObject("extra_damage").remove("head_shot_multiplier");
        assertEquals(1, HitRegionDamage.multiplier(bullet, HitZones.Region.HEAD));
    }

    @Test void attachmentHeadshotModifierIsConsumedExactlyOnce() {
        JsonObject rawGun = json("{\"bullet\":{\"extra_damage\":{\"head_shot_multiplier\":2,\"body_part_multipliers\":{\"torso\":0.7,\"legs\":0.3}}}}");
        JsonObject attachment = json("{\"head_shot\":{\"multiplier\":1.5}}");
        JsonObject bullet = AttachmentModifiers.apply(rawGun, "semi", List.of(attachment)).getAsJsonObject("bullet");
        assertEquals(3, HitRegionDamage.multiplier(bullet, HitZones.Region.HEAD));
        assertEquals(0.7, HitRegionDamage.multiplier(bullet, HitZones.Region.TORSO));
        assertEquals(0.3, HitRegionDamage.multiplier(bullet, HitZones.Region.LEGS));
    }

    @Test void runtimeDefenseRejectsWrongTypesAndNonFiniteValuesForAllRegions() {
        List<JsonElement> invalid = List.of(JsonParser.parseString("null"), new JsonPrimitive("2"),
                new JsonPrimitive(true), JsonParser.parseString("[]"), new JsonObject(),
                new JsonPrimitive(Double.NaN), new JsonPrimitive(Double.POSITIVE_INFINITY),
                new JsonPrimitive(Double.NEGATIVE_INFINITY), JsonParser.parseString("1e1000"));
        for (JsonElement value : invalid) {
            JsonObject bullet = configuredForEveryRegion(value);
            for (HitZones.Region region : HitZones.Region.values())
                assertEquals(1, HitRegionDamage.multiplier(bullet, region), () -> region + " must reject " + value);
        }
        assertEquals(1, HitRegionDamage.multiplier(json("{\"extra_damage\":[]}"), HitZones.Region.HEAD));
        assertEquals(1, HitRegionDamage.multiplier(json("{\"extra_damage\":{\"body_part_multipliers\":42}}"), HitZones.Region.TORSO));
    }

    @Test void runtimeClampsFiniteOutOfRangeAttachmentResultsWithoutLosingZero() {
        for (HitZones.Region region : HitZones.Region.values()) {
            assertEquals(0, HitRegionDamage.multiplier(configuredForEveryRegion(new JsonPrimitive(-5)), region));
            assertEquals(0, HitRegionDamage.multiplier(configuredForEveryRegion(new JsonPrimitive(0)), region));
            assertEquals(100, HitRegionDamage.multiplier(configuredForEveryRegion(new JsonPrimitive(100)), region));
            assertEquals(100, HitRegionDamage.multiplier(configuredForEveryRegion(new JsonPrimitive(150)), region));
        }
    }

    private static JsonObject configuredForEveryRegion(JsonElement value) {
        JsonObject extra = new JsonObject(), parts = new JsonObject(), bullet = new JsonObject();
        extra.add("head_shot_multiplier", value.deepCopy());
        parts.add("torso", value.deepCopy()); parts.add("legs", value.deepCopy());
        extra.add("body_part_multipliers", parts); bullet.add("extra_damage", extra);
        return bullet;
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
