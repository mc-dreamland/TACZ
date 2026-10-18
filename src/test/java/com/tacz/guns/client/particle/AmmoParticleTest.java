package com.tacz.guns.client.particle;

import com.google.gson.Gson;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoParticle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmmoParticleTest {
    private static final Gson GSON = new Gson();

    @Test
    void minimumDistancePreservesLegacyDefaultAndAcceptsCloseRangeFlames() {
        assertEquals(3, GSON.fromJson("{}", AmmoParticle.class).getMinDistance());
        AmmoParticle flame = GSON.fromJson("{\"min_distance\":0.25,\"count\":3}", AmmoParticle.class);
        assertEquals(.25, flame.getMinDistance());
        assertEquals(3, flame.getCount());
    }

    @Test
    void minimumDistanceClampsFiniteValuesAndFallsBackForNonFiniteValues() {
        assertEquals(0, GSON.fromJson("{\"min_distance\":-1}", AmmoParticle.class).getMinDistance());
        assertEquals(64, GSON.fromJson("{\"min_distance\":1000}", AmmoParticle.class).getMinDistance());
        assertEquals(3, GSON.fromJson("{\"min_distance\":\"NaN\"}", AmmoParticle.class).getMinDistance());
        assertEquals(3, GSON.fromJson("{\"min_distance\":\"Infinity\"}", AmmoParticle.class).getMinDistance());
    }
}
