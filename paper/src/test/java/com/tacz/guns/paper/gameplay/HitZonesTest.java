package com.tacz.guns.paper.gameplay;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.tacz.guns.paper.gameplay.HitZones.Profile.*;
import static com.tacz.guns.paper.gameplay.HitZones.Region.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HitZonesTest {
    @Test void standingUsesFiniteHeadTorsoAndLegs() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        assertRegion(HEAD, front(player, 0, 1.62));
        assertRegion(TORSO, front(player, 0, 1));
        assertRegion(LEGS, front(player, 0, .4));
        assertNull(front(player, 0, 1.9));
        assertNull(front(player, .29, 1.62), "Upper AABB corners are outside the narrower head");
        assertRegion(TORSO, front(player, .29, 1));
    }

    @Test void crouchingUsesActualEyeAndBoundingBoxHeight() {
        Player player = player(Pose.SNEAKING, 1, 0, 0);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(-.3, 0, -.3, .3, 1.5, .3));
        when(player.getEyeLocation()).thenReturn(new Location(null, 0, 1.27, 0));
        assertRegion(HEAD, front(player, 0, 1.27));
        assertRegion(TORSO, front(player, 0, 1));
        assertRegion(LEGS, front(player, 0, .5));
        assertNull(front(player, 0, 1.62));
    }

    @Test void scaledEntitiesKeepProportions() {
        for (double scale : new double[]{.5, 2, 4}) {
            Player player = player(Pose.STANDING, scale, 0, 0);
            assertRegion(HEAD, front(player, 0, 1.62 * scale));
            assertRegion(TORSO, front(player, 0, scale));
            assertRegion(LEGS, front(player, 0, .4 * scale));
        }
    }

    @Test void shouldersCannotBecomeHeadshotsFurtherInsideTarget() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        HitZones.Hit hit = trace(player, new Vector(0, .4, -2), new Vector(0, 1, 1).normalize(), 10, DEFAULT_HUMANOID);
        // This diagonal enters above the head and misses instead of claiming an unbounded head band.
        assertNull(hit);
        HitZones.Hit upwards = trace(player, new Vector(0, -1, 0), new Vector(0, 1, 0), 4, DEFAULT_HUMANOID);
        assertRegion(LEGS, upwards);
        assertEquals(.985, upwards.distance(), 1.0e-8);
    }

    @Test void originInsideTargetReturnsItsCurrentRegionAtZero() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        for (double y : new double[]{.3, 1, 1.62}) {
            HitZones.Hit hit = trace(player, new Vector(0, y, 0), new Vector(0, 1, 0), 10, DEFAULT_HUMANOID);
            assertRegion(y < .8 ? LEGS : y < 1.4 ? TORSO : HEAD, hit);
            assertEquals(0, hit.distance());
            assertEquals(new Vector(0, y, 0), hit.point());
        }
    }

    @Test void finiteSegmentStopsBeforeTargetAndIncludesItsEndpoint() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        Vector origin = new Vector(0, 1.62, -2);
        assertNull(trace(player, origin, new Vector(0, 0, 1), 1.7, DEFAULT_HUMANOID));
        HitZones.Hit endpoint = trace(player, origin, new Vector(0, 0, 1), 1.73, DEFAULT_HUMANOID);
        assertRegion(HEAD, endpoint);
        assertEquals(1.73, endpoint.distance(), 1.0e-8);
        assertNull(trace(player, origin, new Vector(0, 0, -1), 10, DEFAULT_HUMANOID));
    }

    @Test void inputVectorsAreNeverMutated() {
        Vector origin = new Vector(0, 1, -2), direction = new Vector(0, 0, 1);
        trace(player(Pose.STANDING, 1, 0, 0), origin, direction, 10, DEFAULT_HUMANOID);
        assertEquals(new Vector(0, 1, -2), origin);
        assertEquals(new Vector(0, 0, 1), direction);
    }

    @Test void invalidOrZeroRaysDoNotHit() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        assertNull(trace(player, new Vector(0, 1, 0), new Vector(), 10, DEFAULT_HUMANOID));
        assertNull(trace(player, new Vector(Double.NaN, 1, 0), new Vector(0, 0, 1), 10, DEFAULT_HUMANOID));
        assertNull(trace(player, new Vector(0, 1, 0), new Vector(0, 0, 1), -1, DEFAULT_HUMANOID));
        assertNull(trace(player, new Vector(0, 1, 0), new Vector(0, 0, 1), Double.POSITIVE_INFINITY, DEFAULT_HUMANOID));
        assertRegion(TORSO, trace(player, new Vector(0, 1, 0), new Vector(0, 0, 1), 0, DEFAULT_HUMANOID));
    }

    @Test void sharedNeckBoundaryIsStableAndDoesNotExtendIntoTorso() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        assertRegion(TORSO, front(player, 0, 1.44 - .00001));
        assertRegion(TORSO, front(player, 0, 1.44), "At the neck the wider torso is physically reached first");
        assertRegion(HEAD, front(player, 0, 1.44 + .00001));
        assertRegion(TORSO, front(player, 0, .81));
        assertRegion(LEGS, front(player, 0, .81 - .00001));
    }

    @Test void horizontalPlayerUsesPoseEvenWhenNotSwimmingFlag() {
        Player player = player(Pose.SWIMMING, 1, 0, 0);
        assertFalse(player.isSwimming());
        assertTrue(HitZones.horizontal(player));
        assertRegion(HEAD, side(player, .3, .65));
        assertRegion(TORSO, side(player, .3, 0));
        assertRegion(LEGS, side(player, .3, -.7));
        assertNull(side(player, 1.62, 0));
    }

    @Test void proneLegsBlockHeadWhenShotFromBehind() {
        Player player = player(Pose.SWIMMING, 1, 0, 0);
        HitZones.Hit rear = trace(player, new Vector(0, .3, -3), new Vector(0, 0, 1), 10, DEFAULT_HUMANOID);
        assertRegion(LEGS, rear);
        assertEquals(-1.015, rear.point().getZ(), 1.0e-8);
        HitZones.Hit front = trace(player, new Vector(0, .3, 3), new Vector(0, 0, -1), 10, DEFAULT_HUMANOID);
        assertRegion(HEAD, front);
    }

    @Test void crawlingIgnoresLookPitchButUsesBodyYaw() {
        Player player = player(Pose.SWIMMING, 1, 90, 75);
        assertRegion(HEAD, trace(player, new Vector(-.65, .3, -2), new Vector(0, 0, 1), 5, DEFAULT_HUMANOID));
        assertRegion(LEGS, trace(player, new Vector(.7, .3, -2), new Vector(0, 0, 1), 5, DEFAULT_HUMANOID));
        assertNull(side(player, .3, .65), "Yaw turns the entire horizontal body, not just the head");
    }

    @Test void waterSwimmingFollowsPitch() {
        Player player = player(Pose.SWIMMING, 1, 0, 90);
        when(player.isInWater()).thenReturn(true);
        assertRegion(HEAD, side(player, -.65, .3));
        assertRegion(LEGS, side(player, .7, .3));
    }

    @ParameterizedTest
    @EnumSource(value = Pose.class, names = {"FALL_FLYING", "SPIN_ATTACK"})
    void flightHasNoSwimmingTranslation(Pose pose) {
        Player player = player(pose, 1, 0, 0);
        assertTrue(HitZones.horizontal(player));
        assertRegion(HEAD, side(player, 0, 1.65));
        assertRegion(TORSO, side(player, 0, 1.1));
        assertRegion(LEGS, side(player, 0, .4));
        assertNull(side(player, 0, -.7));
    }

    @Test void flightPitchCanAimBodyVertically() {
        Player player = player(Pose.FALL_FLYING, 1, 0, -90);
        assertRegion(HEAD, side(player, 1.65, 0));
        assertRegion(TORSO, side(player, 1.1, 0));
        assertRegion(LEGS, side(player, .4, 0));
    }

    @Test void scaledCrawlingKeepsHeadAndFeetOutsideOriginalBoundingBox() {
        Player player = player(Pose.SWIMMING, 2, 0, 0);
        assertRegion(HEAD, side(player, .6, 1.3));
        assertRegion(LEGS, side(player, .6, -1.4));
    }

    @Test void genericHasForwardHeadAndAllOtherVolumesAreTorso() {
        LivingEntity creature = mock(LivingEntity.class);
        when(creature.getBoundingBox()).thenReturn(new BoundingBox(-.5, 0, -.5, .5, 1.4, .5));
        when(creature.getEyeLocation()).thenReturn(new Location(null, 0, 1.2, 0));
        assertRegion(HEAD, trace(creature, new Vector(0, 1.2, 2), new Vector(0, 0, -1), 4, DEFAULT_GENERIC));
        assertRegion(TORSO, trace(creature, new Vector(0, 1.2, -2), new Vector(0, 0, 1), 4, DEFAULT_GENERIC));
        assertRegion(TORSO, trace(creature, new Vector(0, .2, 2), new Vector(0, 0, -1), 4, DEFAULT_GENERIC));
        assertRegion(TORSO, trace(creature, new Vector(2, 1.2, 0), new Vector(-1, 0, 0), 4, DEFAULT_GENERIC));
    }

    @Test void genericFacingTurnsHeadWithoutCreatingLegs() {
        LivingEntity creature = mock(LivingEntity.class);
        when(creature.getBoundingBox()).thenReturn(new BoundingBox(-.5, 0, -.5, .5, 1.4, .5));
        when(creature.getEyeLocation()).thenReturn(new Location(null, 0, 1.2, 0));
        when(creature.getBodyYaw()).thenReturn(90f);
        assertRegion(HEAD, trace(creature, new Vector(-2, 1.2, 0), new Vector(1, 0, 0), 4, DEFAULT_GENERIC));
        assertRegion(TORSO, trace(creature, new Vector(2, 1.2, 0), new Vector(-1, 0, 0), 4, DEFAULT_GENERIC));
    }

    @Test void diagonalGenericFacingStillExposesHeadAtForwardSurface() {
        LivingEntity creature = mock(LivingEntity.class);
        when(creature.getBoundingBox()).thenReturn(new BoundingBox(-.5, 0, -.5, .5, 1.4, .5));
        when(creature.getEyeLocation()).thenReturn(new Location(null, 0, 1.2, 0));
        when(creature.getBodyYaw()).thenReturn(45f);
        assertRegion(HEAD, trace(creature, new Vector(-2, 1.2, 2), new Vector(1, 0, -1).normalize(), 4, DEFAULT_GENERIC));
        assertRegion(TORSO, trace(creature, new Vector(2, 1.2, -2), new Vector(-1, 0, 1).normalize(), 4, DEFAULT_GENERIC));
    }

    @Test void bodyProfileUsesWholeBoxAndNeverHeadshots() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        for (double y : new double[]{.1, 1, 1.7}) {
            assertRegion(TORSO, trace(player, new Vector(.29, y, -2), new Vector(0, 0, 1), 4, BODY));
        }
    }

    @Test void zeroHeadAndLegRatiosDoNotCreateTinyPhantomRegions() {
        Player player = player(Pose.STANDING, 1, 0, 0);
        HitZones.Profile allTorso = new HitZones.Profile(HitZones.Shape.HUMANOID, 0, 0, 0);
        assertRegion(TORSO, trace(player, new Vector(0, -.01, -2), new Vector(0, 0, 1), 4, allTorso));
        assertRegion(TORSO, trace(player, new Vector(0, 1.7, -2), new Vector(0, 0, 1), 4, allTorso));
    }

    @Test void badProfileNumbersAreRejected() {
        for (double bad : new double[]{-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new HitZones.Profile(HitZones.Shape.HUMANOID, bad, .25, .45));
            assertThrows(IllegalArgumentException.class, () -> new HitZones.Profile(HitZones.Shape.HUMANOID, .85, bad, .45));
            assertThrows(IllegalArgumentException.class, () -> new HitZones.Profile(HitZones.Shape.HUMANOID, .85, .25, bad));
        }
    }

    private static Player player(Pose pose, double scale, float yaw, float pitch) {
        Player player = mock(Player.class);
        boolean horizontal = pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SPIN_ATTACK;
        when(player.getPose()).thenReturn(pose);
        when(player.getBoundingBox()).thenReturn(new BoundingBox(-.3 * scale, 0, -.3 * scale, .3 * scale,
                (horizontal ? .6 : 1.8) * scale, .3 * scale));
        when(player.getEyeLocation()).thenReturn(new Location(null, 0, (horizontal ? .4 : 1.62) * scale, 0));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0, yaw, pitch));
        when(player.getBodyYaw()).thenReturn(yaw);
        return player;
    }

    private static HitZones.Hit front(LivingEntity entity, double x, double y) {
        return trace(entity, new Vector(x, y, -10), new Vector(0, 0, 1), 20, DEFAULT_HUMANOID);
    }

    private static HitZones.Hit side(LivingEntity entity, double y, double z) {
        return trace(entity, new Vector(-10, y, z), new Vector(1, 0, 0), 20, DEFAULT_HUMANOID);
    }

    private static HitZones.Hit trace(LivingEntity entity, Vector origin, Vector direction, double distance, HitZones.Profile profile) {
        return HitZones.trace(entity, origin, direction, distance, profile);
    }

    private static void assertRegion(HitZones.Region region, HitZones.Hit hit) { assertRegion(region, hit, "Expected " + region); }
    private static void assertRegion(HitZones.Region region, HitZones.Hit hit, String message) {
        assertNotNull(hit, message);
        assertEquals(region, hit.region(), message);
    }
}
