package com.tacz.guns.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShotPresentationTrackerTest {
    @Test void ak47SoundsFollowLocalHundredMillisecondSendsDespiteBunchedConfirmations() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        int[] arrival = {120, 130, 330, 335, 560, 565, 770, 775, 1020, 1025};
        List<Long> effects = new ArrayList<>();
        int nextAck = 0;
        for (long now = 0; now <= 1_100; now++) {
            if (now <= 900 && now % 100 == 0) tracker.begin(now / 100 + 1, "ak", 1, 1, 0, now, true, true);
            while (nextAck < arrival.length && arrival[nextAck] == now) {
                long sequence = ++nextAck;
                assertFalse(tracker.confirm(sequence, "ak", 0, true));
                tracker.result(sequence, "ak", "complete", 1);
                tracker.snapshot("ak", sequence, 0);
            }
            var shot = tracker.poll(now);
            if (shot != null) effects.add(now);
        }
        assertEquals(List.of(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L), effects);
        assertEquals(0, tracker.reservedAmmo("ak"));
    }

    @Test void earlyBurstAcknowledgementDoesNotAdvanceOrEraseTheLocalDeadline() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "burst", 3, 3, 100, 0, true, true);
        assertEquals(0, tracker.poll(0).shotIndex());
        assertFalse(tracker.confirm(1, "burst", 1, true));
        tracker.result(1, "burst", "complete", 3);
        assertNull(tracker.poll(50));
        assertEquals(1, tracker.poll(100).shotIndex());
        assertEquals(2, tracker.poll(200).shotIndex());
        assertNull(tracker.poll(200));
        assertFalse(tracker.confirm(1, "burst", 2, true));
    }

    @Test void terminalPrefixKeepsSuccessfulUnplayedShotsButRemovesUnfiredTail() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "burst", 3, 3, 100, 0, true, true);
        assertNotNull(tracker.poll(0));
        tracker.result(1, "burst", "queued", 0);
        assertEquals(3, tracker.reservedAmmo("burst"));
        tracker.result(1, "burst", "cancelled", 2);
        assertEquals(1, tracker.poll(100).shotIndex());
        assertNull(tracker.poll(200));
        tracker.begin(2, "burst", 1, 1, 0, 200, true, true);
        tracker.result(2, "burst", "rejected", 0);
        assertNull(tracker.poll(200));
        assertFalse(tracker.confirm(2, "burst", 0, true));
    }

    @Test void pendingAmmoIsNotFreedByQueuedAckAndOnlySnapshotWatermarkReconcilesIt() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "gun", 2, 2, 50, 0, true, true);
        tracker.begin(2, "gun", 1, 1, 0, 100, true, true);
        assertEquals(3, tracker.reservedAmmo("gun"));
        tracker.result(1, "gun", "queued", 0);
        assertEquals(3, tracker.reservedAmmo("gun"));
        tracker.snapshot("gun", 1, 0);
        assertEquals(2, tracker.reservedAmmo("gun"));
        tracker.snapshot("gun", 1, 1);
        assertEquals(1, tracker.reservedAmmo("gun"));
        tracker.snapshot("gun", 1, 0); // Delayed snapshots cannot roll back the ammo watermark.
        assertEquals(1, tracker.reservedAmmo("gun"));
        tracker.result(2, "gun", "rejected", 0);
        assertEquals(0, tracker.reservedAmmo("gun"));
    }

    @Test void doubleBarrelConsumesTwoPhysicalRoundsWithOneVisualAndNoEcho() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "db", 2, 1, 0, 0, true, true);
        assertNotNull(tracker.poll(0));
        assertEquals(2, tracker.outstanding("db", false).size());
        assertNull(tracker.poll(0));
        assertFalse(tracker.confirm(1, "db", 0, true));
        assertFalse(tracker.confirm(1, "db", 1, false));
        tracker.result(1, "db", "complete", 2);
        tracker.snapshot("db", 1, 1);
        assertEquals(0, tracker.reservedAmmo("db"));
    }

    @Test void sceneCancellationKeepsEchoTombstonesAndLongStallsDoNotBurstSounds() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "old", 3, 3, 50, 0, true, true);
        assertNotNull(tracker.poll(0));
        tracker.cancelFuture();
        tracker.result(1, "old", "complete", 3);
        assertNull(tracker.poll(100));
        assertFalse(tracker.confirm(1, "old", 0, true));
        assertFalse(tracker.confirm(1, "old", 1, true));
        assertEquals(0, tracker.reservedAmmo("old"));
        tracker.begin(2, "new", 3, 3, 50, 200, true, true);
        assertNotNull(tracker.poll(200));
        assertNull(tracker.poll(500));
        assertFalse(tracker.confirm(2, "new", 2, true));
    }

    @Test void inventoryFedFallbackIsConfirmedOnceAndCreativeDoesNotReserveClipAmmo() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        tracker.begin(1, "inventory", 1, 1, 0, 0, false, false);
        assertNull(tracker.poll(0));
        assertTrue(tracker.confirm(1, "inventory", 0, true));
        assertFalse(tracker.confirm(1, "inventory", 0, true));
        tracker.begin(2, "creative", 3, 3, 100, 0, true, false);
        assertNotNull(tracker.poll(0));
        assertEquals(0, tracker.reservedAmmo("creative"));
        assertEquals(1, tracker.outstanding("creative", false).size());
    }

    @Test void isolatedAmmoViewModelsClosedBoltManualAndSpasFeed() {
        assertEquals(new PresentationAmmo(0, true), new PresentationAmmo(2, false).consume("closed_bolt", false));
        assertEquals(new PresentationAmmo(4, false), new PresentationAmmo(4, true).consume("manual_action", false));
        assertEquals(new PresentationAmmo(3, true), new PresentationAmmo(4, true).consume("manual_action", true));
        assertEquals(new PresentationAmmo(0, false), new PresentationAmmo(1, false).consume("open_bolt", false));
    }

    @Test void evictedOrOldConnectionEpochPredictionsCannotProduceGhostEffects() {
        ShotPresentationTracker tracker = new ShotPresentationTracker();
        for (int i = 1; i <= 600; i++) tracker.begin(i, "gun", 1, 1, 0, i, true, true);
        assertFalse(tracker.contains(1));
        assertFalse(tracker.confirm(1, "gun", 0, true));
        tracker.cancelFuture();
        assertFalse(tracker.confirm(600, "gun", 0, true));
        tracker.reset(); // Rehandshake also resets the action sequence epoch.
        tracker.begin(1, "new", 1, 1, 0, 1, true, true);
        assertNotNull(tracker.poll(1));
        assertFalse(tracker.confirm(1, "new", 0, true));
    }
}
