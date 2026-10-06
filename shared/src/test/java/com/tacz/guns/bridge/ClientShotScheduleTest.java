package com.tacz.guns.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientShotScheduleTest {
    @Test void preparesDuringLastTickAndBothPumpsCanOnlySendItOnce() {
        ClientShotSchedule schedule = new ClientShotSchedule();
        assertTrue(schedule.prepare(0));
        schedule.sent(0, 100);
        assertFalse(schedule.prepare(50));
        assertTrue(schedule.prepare(51));
        assertFalse(schedule.prepare(99));
        assertFalse(schedule.due(99));
        assertTrue(schedule.due(100));
        schedule.sent(100, 100);
        assertFalse(schedule.due(100));
        assertThrows(IllegalStateException.class, () -> schedule.sent(100, 100));
    }

    @Test void ak47CadenceDoesNotAccumulateFiftyMillisecondAcknowledgementLatency() {
        Simulation simulation = simulate(100, 16, 50, 10_000);
        assertEquals(101, simulation.sent().size());
        assertEquals(100, simulation.acknowledged());
        for (int i = 0; i < simulation.sent().size(); i++) assertEquals(i * 100L, simulation.sent().get(i).longValue());
        assertEquals(simulation.sent(), simulate(100, 16, 200, 10_000).sent());
    }

    @Test void fractionalTickIntervalsKeepPhaseInsteadOfAccumulatingFrameRounding() {
        for (int interval : new int[]{60, 75, 85}) {
            for (int frame : new int[]{7, 16, 33}) {
                List<Long> sent = simulate(interval, frame, 50, 10_000).sent();
                for (int i = 0; i < sent.size(); i++) {
                    long lateness = sent.get(i) - (long) i * interval;
                    assertTrue(lateness >= 0 && lateness < frame,
                            "interval=" + interval + ", frame=" + frame + ", shot=" + i + ", lateness=" + lateness);
                }
                assertTrue(10_000 - sent.get(sent.size() - 1) < interval + frame);
            }
        }
    }

    @Test void aLongStallSendsOnlyTheCommittedShotAndRestartsWithoutCatchUp() {
        ClientShotSchedule schedule = new ClientShotSchedule();
        assertTrue(schedule.prepare(0));
        schedule.sent(0, 75);
        assertTrue(schedule.prepare(50));
        assertTrue(schedule.due(800));
        schedule.sent(800, 75);
        assertFalse(schedule.due(800));
        assertFalse(schedule.prepare(800));
        assertEquals(75, schedule.remaining(800));
        assertTrue(schedule.prepare(850));
        assertFalse(schedule.due(874));
        assertTrue(schedule.due(875));
    }

    @Test void cancellationDropsTheInputButCannotBypassTheLastSentShotCooldown() {
        ClientShotSchedule schedule = new ClientShotSchedule();
        assertTrue(schedule.prepare(0));
        schedule.sent(0, 100);
        assertTrue(schedule.prepare(60));
        schedule.cancel();
        assertFalse(schedule.pending());
        assertFalse(schedule.due(100));
        assertEquals(20, schedule.remaining(80));
        assertTrue(schedule.prepare(80));
        schedule.reset();
        assertFalse(schedule.pending());
        assertEquals(0, schedule.remaining(80));
        assertTrue(schedule.prepare(80));
        assertTrue(schedule.due(80));
    }

    /** Models input at 20 Hz, a render pump, and independent delayed state acknowledgements. */
    private static Simulation simulate(int interval, int frame, int rtt, int duration) {
        ClientShotSchedule schedule = new ClientShotSchedule();
        List<Long> sent = new ArrayList<>();
        ArrayDeque<Long> acknowledgements = new ArrayDeque<>();
        int acknowledged = 0;
        for (long now = 0; now <= duration; now++) {
            // Receipt updates authority/visual state, never the local firing clock.
            while (!acknowledgements.isEmpty() && acknowledgements.peek() <= now) {
                acknowledgements.remove();
                acknowledged++;
            }
            if (now % 50 == 0) schedule.prepare(now);
            if ((now % 50 == 0 || now % frame == 0) && schedule.due(now)) {
                sent.add(now);
                acknowledgements.add(now + rtt);
                schedule.sent(now, interval);
            }
        }
        return new Simulation(sent, acknowledged);
    }

    private record Simulation(List<Long> sent, int acknowledged) { }
}
