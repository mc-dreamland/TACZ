package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GunMathTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    @Test void damageUsesPiecewiseFalloffAndSharesDamageAcrossPellets() {
        JsonObject bullet = json("""
            {"damage":64,"extra_damage":{"damage_adjust":[
              {"distance":15,"damage":64},{"distance":30,"damage":40},{"distance":"infinite","damage":24}]}}
            """);
        assertEquals(8, GunMath.damageAt(bullet, 14, 8));
        assertEquals(5, GunMath.damageAt(bullet, 15, 8));
        assertEquals(3, GunMath.damageAt(bullet, 100, 8));
    }
    @Test void missingInfiniteTailMeansNoDamageBeyondFinalBand() {
        assertEquals(0, GunMath.damageAt(json("{\"extra_damage\":{\"damage_adjust\":[{\"distance\":30,\"damage\":9}]}}"), 40, 1));
    }
    @Test void chargeRejectsNaNAndImpossibleInstantCharge() {
        JsonObject charge = json("{\"type\":\"hold\",\"increase_per_tick\":0.1,\"fire_threshold\":0.6,\"max_charge\":1}");
        assertFalse(GunMath.validCharge(charge, Double.NaN, 1000, 0));
        assertFalse(GunMath.validCharge(charge, 1, 0, 0));
        assertFalse(GunMath.validCharge(charge, .3, 1000, 0));
        assertTrue(GunMath.validCharge(charge, .6, 200, 0));
        assertFalse(GunMath.validCharge(new JsonObject(), .1, 1000, 0));
    }
    @Test void automaticChargeNeedsMaximumAndIsStillTimeBounded() {
        JsonObject charge = json("{\"type\":\"auto\",\"increase_per_tick\":0.1,\"fire_threshold\":0.1,\"max_charge\":1}");
        assertFalse(GunMath.validCharge(charge, .2, 1000, 0));
        assertTrue(GunMath.validCharge(charge, 1, 1000, 0));
    }
}
