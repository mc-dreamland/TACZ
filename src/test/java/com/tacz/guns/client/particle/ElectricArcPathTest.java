package com.tacz.guns.client.particle;

import com.google.gson.Gson;
import com.tacz.guns.client.resource.pojo.display.gun.ElectricArc;
import com.tacz.guns.client.resource.pojo.display.gun.GunAmmo;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ElectricArcPathTest {
    private static final Gson GSON = new Gson();

    @Test
    void arcIsOptInAndHasBoundedDefaults() {
        assertNull(GSON.fromJson("{}", GunAmmo.class).getElectricArc());
        ElectricArc config = GSON.fromJson("{\"electric_arc\":{}}", GunAmmo.class).getElectricArc();
        assertNotNull(config);
        assertEquals(.10, config.spacing());
        assertEquals(.16, config.jitter());
        assertEquals(2, config.branches());
        assertEquals(.35, config.branchLength());
        assertEquals(3, config.lifeTime());
        assertEquals(160, config.maxParticles());
    }

    @Test
    void unsafeDisplayValuesCannotProduceUnboundedOrNonFiniteGeometry() {
        ElectricArc config = GSON.fromJson("""
                {"spacing":"NaN","jitter":"Infinity","branches":999,
                 "branch_length":-20,"life_time":999,"max_particles":2147483647}
                """, ElectricArc.class);
        assertEquals(.10, config.spacing());
        assertEquals(.16, config.jitter());
        assertEquals(3, config.branches());
        assertEquals(0, config.branchLength());
        assertEquals(10, config.lifeTime());
        assertEquals(256, config.maxParticles());
        var points = ElectricArcPath.points(Vec3.ZERO, new Vec3(0, 10, 0), config, new Random(4)::nextDouble, 1000);
        assertTrue(points.size() <= 256);
        assertTrue(points.stream().allMatch(ElectricArcPath::finite));
    }

    @Test
    void denseArcKeepsItsClippedEndpointWithinTheAvailableBudget() {
        ElectricArc config = GSON.fromJson("{\"spacing\":0.025,\"max_particles\":256}", ElectricArc.class);
        Vec3 end = new Vec3(10, 0, 0);
        var points = ElectricArcPath.points(Vec3.ZERO, end, config, new Random(5)::nextDouble, 17);
        assertEquals(17, points.size());
        assertEquals(end, points.get(points.size() - 1));
        assertTrue(points.stream().allMatch(point -> point.x >= 0 && point.x <= 10));
        assertTrue(ElectricArcPath.points(Vec3.ZERO, end, config, () -> .5, 0).isEmpty());
    }

    @Test
    void branchesRemainLocalAndDoNotExtendPastTheAuthoritativeEndpoint() {
        ElectricArc config = GSON.fromJson("{\"jitter\":0,\"branches\":2,\"branch_length\":0.35}", ElectricArc.class);
        Vec3 end = new Vec3(0, 0, 3);
        var points = ElectricArcPath.points(Vec3.ZERO, end, config, () -> .5, 160);
        assertTrue(points.stream().anyMatch(point -> Math.abs(point.x) > .2));
        assertTrue(points.stream().allMatch(point -> Math.abs(point.x) <= .35 && point.z >= 0 && point.z <= 3));
        assertEquals(end, points.get(points.size() - 1));
    }

    @Test
    void malformedOrUnreasonablyLongSegmentsHaveNoEffect() {
        ElectricArc config = new ElectricArc();
        assertTrue(ElectricArcPath.points(Vec3.ZERO, Vec3.ZERO, config, () -> .5, 160).isEmpty());
        assertTrue(ElectricArcPath.points(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), config, () -> .5, 160).isEmpty());
        assertTrue(ElectricArcPath.points(Vec3.ZERO, new Vec3(1000, 0, 0), config, () -> .5, 160).isEmpty());
    }
}
