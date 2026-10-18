package com.tacz.guns.client.particle;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientTrailProgressTest {
    private static final Vec3 FORWARD = new Vec3(1, 0, 0);

    @Test
    void spawnAndTerminalInOneBatchStillHaveOneVisibleSegment() {
        ClientTrailProgress progress = new ClientTrailProgress();
        Vec3 hit = new Vec3(1.2, 0, 0);
        assertEquals(new ClientTrailProgress.Segment(Vec3.ZERO, hit),
                progress.advance(Vec3.ZERO, hit, FORWARD, (start, end) -> end));
        assertNull(progress.advance(hit, hit, FORWARD, (start, end) -> end));
    }

    @Test
    void delayedCorrectionsAndTerminalPacketsCannotReplayAlreadyEmittedSegments() {
        ClientTrailProgress progress = new ClientTrailProgress();
        Vec3 end = new Vec3(2, 0, 0);
        assertNotNull(progress.advance(Vec3.ZERO, end, FORWARD, (start, finish) -> finish));
        assertNull(progress.advance(Vec3.ZERO, FORWARD, FORWARD, (start, finish) -> finish));
        assertNull(progress.advance(FORWARD, end, FORWARD, (start, finish) -> finish));
        assertEquals(new ClientTrailProgress.Segment(end, new Vec3(3, 0, 0)),
                progress.advance(FORWARD, new Vec3(3, 0, 0), FORWARD, (start, finish) -> finish));
    }

    @Test
    void hittingAWallStopsLaterNativePredictionFromDrawingBehindIt() {
        ClientTrailProgress progress = new ClientTrailProgress();
        Vec3 wall = new Vec3(.6, 0, 0);
        var segment = progress.advance(Vec3.ZERO, FORWARD, FORWARD, (start, end) -> wall);
        assertEquals(wall, segment.end());
        assertNull(progress.advance(FORWARD, new Vec3(2, 0, 0), FORWARD, (start, end) -> end));
        assertNull(progress.advance(wall, wall, FORWARD, (start, end) -> end));
    }

    @Test
    void terminalCleanupOnlyRemovesParticlesBeyondTheConfirmedHitPlane() {
        Vec3 hit = new Vec3(2, 0, 0);
        assertTrue(ClientTrailProgress.beyond(new Vec3(2.1, .2, .2), hit, FORWARD));
        assertFalse(ClientTrailProgress.beyond(new Vec3(1.9, .2, .2), hit, FORWARD));
        assertFalse(ClientTrailProgress.beyond(new Vec3(2, .2, .2), hit, FORWARD));
    }

    @Test
    void budgetIsSharedByAllBulletsAndResetsOnTickOrWorldChange() {
        TrailParticleBudget budget = new TrailParticleBudget(2);
        Object world = new Object();
        assertTrue(budget.claim(world, 10));
        assertTrue(budget.claim(world, 10));
        assertFalse(budget.claim(world, 10));
        assertEquals(0, budget.remaining(world, 10));
        assertTrue(budget.claim(world, 11));
        assertTrue(budget.claim(world, 11));
        assertFalse(budget.claim(world, 11));
        assertTrue(budget.claim(new Object(), 11));
    }
}
