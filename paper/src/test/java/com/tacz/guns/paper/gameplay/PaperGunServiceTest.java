package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.bridge.ClientShotSchedule;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperGunServiceTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject automatic() {
        return json("{ammo:'tacz:bullet',rpm:600,bolt:'open_bolt',draw_time:0,put_away_time:0,fire_mode:['auto','semi'],"
                + "reload:{feed:{empty:1},cooldown:{empty:2}}}");
    }

    @Test void everyRequestUpToOneTickEarlyFiresOnceAtTheDeadline() {
        for (int early = 1; early <= 50; early++) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                f.action("shoot", 100 - early);
                assertEquals(List.of(0L), f.shots, "early=" + early);
                f.tick(99);
                assertEquals(1, f.shots.size());
                f.tick(100);
                f.tick(200);
                assertEquals(List.of(0L, 100L), f.shots, "early=" + early);
                assertEquals(998, f.ammo());
            }
        }
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 49); // 51 ms early cannot reserve an intent.
            f.tick(100);
            assertEquals(List.of(0L), f.shots);
        }
    }

    @Test void replayAndNewSequencesCannotReplaceOrMultiplyTheOnePendingIntent() {
        JsonObject gun = automatic();
        gun.add("charging", json("{auto:{type:'hold',increase_per_tick:1,max_charge:1,fire_threshold:.5,decrease_on_fire:1}}"));
        try (Fixture f = new Fixture(gun)) {
            f.shoot(0, .6);
            JsonObject queued = f.shoot(50, .6);
            f.service.handle(f.player, queued); // Exact replay.
            for (int i = 0; i < 25; i++) f.shoot(50, 1); // New seq/charge cannot replace the oldest intent.
            JsonObject aim = f.request("aim"); aim.addProperty("value", true);
            f.handle(aim, 75); // Advancing the anti-replay counter must not invalidate the queued shot.
            f.tick(100);
            assertEquals(List.of(0L, 100L), f.shots);
            assertEquals(.6, f.packet.get("charge").getAsDouble(), 1e-9);
            f.tick(200);
            assertEquals(2, f.shots.size());
            assertEquals(998, f.ammo());
        }
    }

    @Test void lateHandlingKeepsOnlyAnExistingPhaseWithinFiftyMilliseconds() {
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 150);
            assertEquals(50, f.remaining()); // Due 100, processed 150; next due stays 200.
            f.action("shoot", 201);
            assertEquals(99, f.remaining());
            f.action("shoot", 299);
            f.tick(300);
            assertEquals(List.of(0L, 150L, 201L, 300L), f.shots);
        }
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 75); // First shot never borrows a preceding tick.
            assertEquals(100, f.remaining());
            f.action("shoot", 226); // 51 ms late re-anchors the cadence.
            assertEquals(100, f.remaining());
        }
    }

    @Test void aLongPauseOrStalledTickDoesNotReleaseABacklog() {
        for (boolean queued : List.of(false, true)) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                if (queued) f.action("shoot", 50);
                if (queued) f.tick(1_000); else f.action("shoot", 1_000);
                f.service.sync(f.player);
                assertEquals(100, f.remaining());
                f.action("shoot", 1_000);
                f.tick(1_000);
                assertEquals(List.of(0L, 1_000L), f.shots);
                f.tick(1_100);
                assertEquals(2, f.shots.size());
            }
        }
    }

    @Test void sustainedEarlyRequestsCannotExceedTheTenSecondRpmBudget() {
        try (Fixture f = new Fixture(automatic())) {
            for (int now = 0; now <= 10_000; now += 10) {
                if (now % 20 == 0) f.action("shoot", now);
                if (now % 50 == 0) f.tick(now);
            }
            assertEquals(101, f.shots.size()); // Includes both t=0 and t=10 s.
            assertEquals(899, f.ammo());
            for (int i = 1; i < f.shots.size(); i++) assertEquals(100, f.shots.get(i) - f.shots.get(i - 1));
        }
    }

    @Test void fiftyMillisecondRoundTripWithClientTicksAndFramesRetainsAkCadence() {
        try (Fixture f = new Fixture(automatic())) {
            ClientShotSchedule client = new ClientShotSchedule();
            Queue<Long> uplink = new ArrayDeque<>();
            Queue<Long> downlink = new ArrayDeque<>();
            int sent = 0, receivedAcks = 0;
            for (int now = 0; now <= 10_000; now++) {
                if (now % 50 == 0) client.prepare(now);
                if ((now % 50 == 0 || now % 16 == 0) && client.due(now)) {
                    client.sent(now, 100);
                    uplink.add(now + 25L);
                    sent++;
                }
                if (now % 50 == 0) {
                    while (!uplink.isEmpty() && uplink.peek() <= now) {
                        uplink.remove();
                        f.action("shoot", now);
                        downlink.add(now + 25L);
                    }
                    f.tick(now);
                }
                while (!downlink.isEmpty() && downlink.peek() <= now) {
                    downlink.remove();
                    receivedAcks++; // Receiving authoritative state must not restart the local schedule.
                }
            }
            assertEquals(101, sent);
            assertEquals(100, receivedAcks);
            assertEquals(100, f.shots.size());
            assertEquals(50, f.shots.getFirst());
            assertEquals(9_950, f.shots.getLast());
            assertEquals(900, f.ammo());
        }
    }

    @Test void aNewRequestCanSettleADueSlotWithoutOverwritingItsAmmoSnapshot() {
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 50);
            f.action("shoot", 150); // Executes old due-100 intent, then reserves the next due-200 intent.
            assertEquals(998, f.ammo());
            f.tick(200);
            assertEquals(List.of(0L, 150L, 200L), f.shots);
            assertEquals(997, f.ammo());
        }
    }

    @Test void incompatibleActionIntentCancelsEvenWhenThatActionIsOnCooldown() {
        for (String op : List.of("reload", "bolt", "melee", "fire_select", "inspect", "draw")) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                f.action("shoot", 99);
                f.action(op, 99);
                f.tick(100);
                f.tick(200);
                assertEquals(List.of(0L), f.shots, op);
                if (op.equals("fire_select")) assertEquals(0, f.packet.get("shotInterval").getAsLong());
            }
        }
    }

    @Test void resetSwitchDeathAndAuthorityLossClearTheCommittedIntent() {
        for (String change : List.of("reset", "connection", "instance", "empty_hand", "dead", "permission", "ready", "spectator")) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                f.action("shoot", 50);
                switch (change) {
                    case "reset" -> f.service.reset(f.player); // The bridge menu transaction path uses this reset.
                    case "connection" -> f.service.resetConnection(f.player);
                    case "instance" -> f.state.addProperty("instance", "another-gun");
                    case "empty_hand" -> f.hasGun = false;
                    case "dead" -> when(f.player.isDead()).thenReturn(true);
                    case "permission" -> when(f.player.hasPermission("tacz.use")).thenReturn(false);
                    case "ready" -> when(f.peer.ready(f.player)).thenReturn(false);
                    case "spectator" -> when(f.player.getGameMode()).thenReturn(GameMode.SPECTATOR);
                }
                f.tick(100);
                f.hasGun = true;
                when(f.player.isDead()).thenReturn(false);
                when(f.player.hasPermission("tacz.use")).thenReturn(true);
                when(f.peer.ready(f.player)).thenReturn(true);
                when(f.player.getGameMode()).thenReturn(GameMode.SURVIVAL);
                f.tick(200);
                assertEquals(List.of(0L), f.shots, change);
            }
        }
    }

    @Test void pendingExecutionReadsCurrentAmmoHeatModeAndSprintState() {
        for (String change : List.of("ammo", "heat", "mode", "sprint")) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                f.action("shoot", 50);
                switch (change) {
                    case "ammo" -> f.state.addProperty("ammo", 0);
                    case "heat" -> f.state.addProperty("overheated", true);
                    case "mode" -> f.state.addProperty("fireMode", "semi");
                    case "sprint" -> f.sprinting.set(true);
                }
                f.tick(100);
                f.state.addProperty("ammo", 999);
                f.state.addProperty("overheated", false);
                f.state.addProperty("fireMode", "auto");
                f.sprinting.set(false);
                f.tick(400);
                assertEquals(List.of(0L), f.shots, change);
            }
        }
    }

    @Test void aRejectedSprintDoesNotDropAnAimedShotButAnAcceptedSprintDoes() {
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 50);
            JsonObject aim = f.request("aim"); aim.addProperty("value", true); f.handle(aim, 75);
            PlayerToggleSprintEvent sprint = new PlayerToggleSprintEvent(f.player, true);
            f.service.sprint(sprint);
            assertTrue(sprint.isCancelled());
            // Bukkit ignores the MONITOR listener for this cancelled event.
            f.tick(100);
            assertEquals(2, f.shots.size());
        }
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 50);
            PlayerToggleSprintEvent sprint = new PlayerToggleSprintEvent(f.player, true);
            f.service.sprint(sprint);
            assertFalse(sprint.isCancelled());
            f.service.sprintStarted(sprint);
            f.tick(100);
            assertEquals(1, f.shots.size());
        }
    }

    @Test void burstQueueNeverOverwritesUnfiredRoundsAndStartsTheNextGroupOnTime() {
        JsonObject gun = automatic();
        gun.add("burst_data", json("{count:3,bpm:900,min_interval:.3}"));
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("fireMode", "burst");
            f.action("shoot", 0);
            f.action("shoot", 50); // Cannot replace the existing group's remaining rounds.
            f.tick(66); f.tick(132);
            assertEquals(List.of(0L, 66L, 132L), f.shots);
            f.action("shoot", 299);
            f.tick(300); f.tick(366); f.tick(432);
            assertEquals(List.of(0L, 66L, 132L, 300L, 366L, 432L), f.shots);
            assertEquals(994, f.ammo());
        }
    }

    @Test void chargeMustBeValidAtReservationAndExecution() {
        JsonObject gun = automatic();
        gun.add("charging", json("{auto:{type:'hold',increase_per_tick:1,max_charge:1,fire_threshold:.5,decrease_on_fire:1}}"));
        try (Fixture f = new Fixture(gun)) {
            f.shoot(0, .6);
            f.shoot(50, 2);
            f.tick(100);
            assertEquals(1, f.shots.size());
            f.shoot(100, .6);
            f.shoot(150, .6);
            // Effective gun data is resolved again at execution, not captured with the intent.
            gun.getAsJsonObject("charging").getAsJsonObject("auto").addProperty("max_charge", .5);
            f.tick(200);
            assertEquals(List.of(0L, 100L), f.shots);
        }
    }

    @Test void heatAndAccelerationAdvanceOnlyOnSuccessfulShots() {
        JsonObject gun = automatic();
        gun.addProperty("script", "tacz:devotion_lmg_logic");
        gun.add("heat", json("{max:100,per_shot:10,min_rpm_mod:1,max_rpm_mod:1,cooling_delay:10000}"));
        try (Fixture f = new Fixture(gun)) {
            f.action("shoot", 0);
            assertEquals(100, f.packet.get("shotInterval").getAsLong());
            f.action("shoot", 50);
            f.action("shoot", 60);
            assertEquals(10, f.state.get("heat").getAsDouble());
            assertEquals(100, f.packet.get("shotInterval").getAsLong());
            f.tick(100);
            assertEquals(20, f.state.get("heat").getAsDouble());
            assertEquals(92, f.packet.get("shotInterval").getAsLong());
            f.action("shoot", 192);
            assertEquals(84, f.packet.get("shotInterval").getAsLong());
            assertEquals(30, f.state.get("heat").getAsDouble());
        }
    }

    @Test void aHeatedMinigunMayShootFasterThanTwentyTimesPerSecond() {
        JsonObject gun = automatic();
        gun.addProperty("rpm", 1200);
        gun.add("heat", json("{max:1000,per_shot:1,min_rpm_mod:1.2,max_rpm_mod:1.2}"));
        try (Fixture f = new Fixture(gun)) {
            for (int now = 0; now <= 984; now += 41) f.action("shoot", now);
            assertEquals(25, f.shots.size());
            assertEquals(41, f.packet.get("shotInterval").getAsLong());
        }
    }

    @Test void queuedShotKeepsItsOriginalActionSequenceAfterAimAndReportsOneTerminal() {
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            JsonObject queued = f.request("shoot"); long shotSeq = queued.get("seq").getAsLong();
            f.handle(queued, 50);
            assertEquals(0, f.result(shotSeq, "queued").get("fired").getAsInt());
            assertNotEquals(shotSeq, f.packet.get("lastFiredActionSeq").getAsLong());
            JsonObject aim = f.request("aim"); aim.addProperty("value", true); f.handle(aim, 75);
            f.tick(100);
            JsonObject event = f.shotEvents.getLast();
            assertEquals(shotSeq, event.get("actionSeq").getAsLong());
            assertEquals(0, event.get("shotIndex").getAsInt());
            assertTrue(event.get("visual").getAsBoolean());
            assertEquals(shotSeq, f.packet.get("lastFiredActionSeq").getAsLong());
            assertEquals(0, f.packet.get("lastFiredShotIndex").getAsInt());
            assertEquals(1, f.result(shotSeq, "complete").get("fired").getAsInt());
            f.handle(queued, 101); // Replay must neither reject nor reconfirm this accepted trigger.
            assertEquals(2, f.results.stream().filter(r -> r.get("actionSeq").getAsLong() == shotSeq).count());
        }
    }

    @Test void rejectingAnotherRequestDoesNotCloseAnAlreadyQueuedTrigger() {
        try (Fixture f = new Fixture(automatic())) {
            f.action("shoot", 0);
            f.action("shoot", 50); long queuedSeq = f.sequence;
            f.action("shoot", 60); long rejectedSeq = f.sequence;
            assertEquals(0, f.result(rejectedSeq, "rejected").get("fired").getAsInt());
            f.tick(100);
            assertEquals(queuedSeq, f.shotEvents.getLast().get("actionSeq").getAsLong());
            assertEquals(1, f.result(queuedSeq, "complete").get("fired").getAsInt());
            assertFalse(f.results.stream().anyMatch(r -> r.get("actionSeq").getAsLong() == queuedSeq
                    && r.get("status").getAsString().equals("cancelled")));
        }
    }

    @Test void burstRoundsKeepTheOriginalSequenceAndReportTheirPhysicalPrefixInOrder() {
        JsonObject gun = automatic(); gun.add("burst_data", json("{count:3,bpm:900,min_interval:.3}"));
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("fireMode", "burst");
            f.action("shoot", 0); long trigger = f.sequence;
            f.action("aim", 10);
            f.action("shoot", 20); long rejected = f.sequence;
            f.action("zoom", 30);
            f.tick(66); f.tick(132);
            assertEquals(0, f.result(rejected, "rejected").get("fired").getAsInt());
            for (int i = 0; i < 3; i++) {
                assertEquals(trigger, f.shotEvents.get(i).get("actionSeq").getAsLong());
                assertEquals(i, f.shotEvents.get(i).get("shotIndex").getAsInt());
            }
            assertEquals(3, f.result(trigger, "complete").get("fired").getAsInt());
            assertTrue(f.wire.indexOf("shoot:" + trigger + ":2") < f.wire.indexOf("complete:" + trigger));
            assertEquals(trigger, f.packet.get("lastFiredActionSeq").getAsLong());
            assertEquals(2, f.packet.get("lastFiredShotIndex").getAsInt());
        }
    }

    @Test void resetAndSwapReportCancelledUsingTheOldIdentityBeforeClearingIt() {
        for (boolean burst : List.of(false, true)) {
            JsonObject gun = automatic(); gun.add("burst_data", json("{count:3,bpm:900,min_interval:.3}"));
            try (Fixture f = new Fixture(gun)) {
                if (burst) f.state.addProperty("fireMode", "burst");
                f.action("shoot", 0);
                if (!burst) f.action("shoot", 50);
                long trigger = f.sequence;
                f.state.addProperty("instance", "new-gun");
                f.tick(60);
                JsonObject result = f.result(trigger, "cancelled");
                assertEquals("test-instance", result.get("instance").getAsString());
                assertEquals(burst ? 1 : 0, result.get("fired").getAsInt());
                f.service.reset(f.player);
                f.tick(300);
                assertEquals(1, f.results.stream().filter(r -> r.get("actionSeq").getAsLong() == trigger
                        && !r.get("status").getAsString().equals("queued")).count());
                assertEquals(-1, f.packet.get("lastFiredActionSeq").getAsLong());
                assertEquals(-1, f.packet.get("lastFiredShotIndex").getAsInt());
            }
        }
    }

    @Test void burstAmmoAndWatermarkSyncTogetherOnOddTicksWithoutDuplicatingEvenTicks() {
        JsonObject gun = automatic(); gun.add("burst_data", json("{count:3,bpm:900,min_interval:.3}"));
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("fireMode", "burst");
            f.action("shoot", 0); long trigger = f.sequence;
            clearInvocations(f.peer);
            f.tick(66); // First service tick is odd, but the second physical round must publish its watermark.
            verify(f.peer, times(1)).broadcast(eq(f.player), eq("state"), any());
            assertEquals(998, f.packet.get("ammo").getAsInt());
            assertEquals(trigger, f.packet.get("lastFiredActionSeq").getAsLong());
            assertEquals(1, f.packet.get("lastFiredShotIndex").getAsInt());
            clearInvocations(f.peer);
            f.tick(132); // A successful round on an even tick still publishes only one state.
            verify(f.peer, times(1)).broadcast(eq(f.player), eq("state"), any());
            assertEquals(997, f.packet.get("ammo").getAsInt());
            assertEquals(2, f.packet.get("lastFiredShotIndex").getAsInt());
        }
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("fireMode", "burst"); f.state.addProperty("ammo", 1);
            f.action("shoot", 0);
            clearInvocations(f.peer);
            f.tick(66); // An empty magazine ends the burst, without adding a redundant state broadcast.
            verify(f.peer, never()).broadcast(eq(f.player), eq("state"), any());
            assertEquals(1, f.shotEvents.size());
            assertEquals(0, f.packet.get("lastFiredShotIndex").getAsInt());
        }
    }

    @Test void cancelledBurstReportsOnlyRoundsThatActuallyFired() {
        JsonObject gun = automatic(); gun.add("burst_data", json("{count:3,bpm:900,min_interval:.3}"));
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("fireMode", "burst"); f.state.addProperty("ammo", 2);
            f.action("shoot", 0); long trigger = f.sequence;
            f.tick(66); f.tick(132); f.tick(300);
            assertEquals(2, f.shotEvents.size());
            assertEquals(2, f.result(trigger, "cancelled").get("fired").getAsInt());
            assertEquals(1, f.results.stream().filter(r -> r.get("actionSeq").getAsLong() == trigger).count());
            assertEquals(0, f.ammo());
        }
    }

    @Test void invalidatedQueueAndActionIntentSendCancellationWithoutFiring() {
        for (String cause : List.of("reload", "ammo", "mode", "dead")) {
            try (Fixture f = new Fixture(automatic())) {
                f.action("shoot", 0);
                f.action("shoot", 50); long queued = f.sequence;
                switch (cause) {
                    case "reload" -> f.action("reload", 75);
                    case "ammo" -> f.state.addProperty("ammo", 0);
                    case "mode" -> f.state.addProperty("fireMode", "semi");
                    case "dead" -> when(f.player.isDead()).thenReturn(true);
                }
                f.tick(100); f.tick(200);
                String status = cause.equals("ammo") ? "rejected" : "cancelled";
                assertEquals(0, f.result(queued, status).get("fired").getAsInt(), cause);
                assertEquals(1, f.shotEvents.size());
                assertEquals(2, f.results.stream().filter(r -> r.get("actionSeq").getAsLong() == queued).count());
            }
        }
    }

    @Test void doubleBarrelHasTwoPhysicalAcknowledgementsButOnlyOneVisual() {
        JsonObject gun = automatic();
        gun.addProperty("script", "tacz:db_short_gun_logic");
        gun.add("burst_data", json("{count:1,bpm:600,min_interval:.5}"));
        for (int available : List.of(1, 2)) {
            try (Fixture f = new Fixture(gun)) {
                f.state.addProperty("fireMode", "burst"); f.state.addProperty("ammo", available);
                f.action("shoot", 0); long trigger = f.sequence;
                assertEquals(available, f.shotEvents.size());
                assertTrue(f.shotEvents.getFirst().get("visual").getAsBoolean());
                if (available == 2) {
                    assertFalse(f.shotEvents.get(1).get("visual").getAsBoolean());
                    assertEquals(1, f.shotEvents.get(1).get("shotIndex").getAsInt());
                }
                assertEquals(available, f.result(trigger, "complete").get("fired").getAsInt());
                assertEquals(trigger, f.packet.get("lastFiredActionSeq").getAsLong());
                assertEquals(available - 1, f.packet.get("lastFiredShotIndex").getAsInt());
                assertTrue(f.wire.indexOf("shoot:" + trigger + ":" + (available - 1)) < f.wire.indexOf("complete:" + trigger));
                assertEquals(0, f.ammo());
            }
        }
    }

    /** Real action/tick/state/ammo logic; only Bukkit registration, attributes and projectile effects are isolated. */
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<PaperBallistics> projectiles;
        final Player player = mock(Player.class);
        final PaperItemStore items = mock(PaperItemStore.class);
        final BridgePeer peer = mock(BridgePeer.class);
        final ItemStack held = mock(ItemStack.class);
        final AtomicLong clock = new AtomicLong(1_000_000);
        final AtomicBoolean sprinting = new AtomicBoolean();
        final List<Long> shots = new ArrayList<>();
        final List<JsonObject> shotEvents = new ArrayList<>(), results = new ArrayList<>();
        final List<String> wire = new ArrayList<>();
        final PaperGunService service;
        JsonObject state = json("{kind:'gun',id:'tacz:test',instance:'test-instance',ammo:1000,chamber:false,fireMode:'auto',heat:0,attachments:{}}");
        JsonObject packet;
        boolean hasGun = true;
        long sequence;

        Fixture(JsonObject gun) {
            MockedConstruction<PaperBallistics> created = null;
            try {
                created = mockConstruction(PaperBallistics.class);
                projectiles = created;
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
                JavaPlugin plugin = mock(JavaPlugin.class);
                when(plugin.getName()).thenReturn("TACZ");
                when(plugin.namespace()).thenReturn("tacz");
                PlayerInventory inventory = mock(PlayerInventory.class);
                when(player.getInventory()).thenReturn(inventory);
                when(inventory.getItemInMainHand()).thenReturn(held);
                when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                when(player.getEntityId()).thenReturn(1);
                when(player.isOnline()).thenReturn(true);
                when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
                when(player.hasPermission("tacz.use")).thenReturn(true);
                when(player.isOnGround()).thenReturn(true);
                when(player.isSprinting()).thenAnswer(i -> sprinting.get());
                doAnswer(i -> { sprinting.set(i.getArgument(0)); return null; }).when(player).setSprinting(anyBoolean());
                when(peer.ready(player)).thenReturn(true);
                when(items.read(held)).thenAnswer(i -> hasGun ? state.deepCopy() : null);
                when(items.effectiveGun(any())).thenAnswer(i -> gun.deepCopy());
                when(items.capacity(any())).thenReturn(1000);
                when(items.countAmmo(player, "tacz:bullet")).thenReturn(1000);
                when(items.takeAmmo(eq(player), eq("tacz:bullet"), anyInt())).thenAnswer(i -> i.getArgument(2));
                doAnswer(i -> { state = ((JsonObject) i.getArgument(1)).deepCopy(); return null; }).when(items).write(eq(held), any());
                doAnswer(i -> {
                    String type = i.getArgument(1); JsonObject data = i.getArgument(2);
                    if (type.equals("state")) packet = data.deepCopy();
                    else if (type.equals("event") && data.get("op").getAsString().equals("shoot")) {
                        shots.add(clock.get() - 1_000_000); shotEvents.add(data.deepCopy());
                        wire.add("shoot:" + data.get("actionSeq").getAsLong() + ":" + data.get("shotIndex").getAsInt());
                    }
                    return null;
                }).when(peer).broadcast(eq(player), anyString(), any());
                doAnswer(i -> {
                    String type = i.getArgument(1); JsonObject data = i.getArgument(2);
                    if (type.equals("event") && data.get("op").getAsString().equals("shot_result")) {
                        results.add(data.deepCopy());
                        wire.add(data.get("status").getAsString() + ":" + data.get("actionSeq").getAsLong());
                    }
                    return null;
                }).when(peer).send(eq(player), anyString(), any());
                service = spy(new PaperGunService(plugin, mock(DefaultGunPack.class), items, peer, clock::get));
                doReturn(true).when(service).applyMovement(any(), anyDouble());
                doNothing().when(service).clearMovement(any());
                action("draw", 0);
            } catch (RuntimeException | Error failure) {
                if (created != null) created.close();
                bukkit.close();
                throw failure;
            }
        }
        JsonObject request(String op) {
            JsonObject action = new JsonObject();
            action.addProperty("instance", state.get("instance").getAsString());
            action.addProperty("op", op); action.addProperty("seq", ++sequence);
            return action;
        }
        void handle(JsonObject action, long elapsed) { clock.set(1_000_000 + elapsed); service.handle(player, action); }
        void action(String op, long elapsed) { handle(request(op), elapsed); }
        JsonObject shoot(long elapsed, double charge) {
            JsonObject action = request("shoot"); action.addProperty("charge", charge); handle(action, elapsed); return action;
        }
        void tick(long elapsed) { clock.set(1_000_000 + elapsed); service.tick(); }
        int ammo() { return state.get("ammo").getAsInt(); }
        long remaining() { return packet.get("shootRemaining").getAsLong(); }
        JsonObject result(long sequence, String status) {
            return results.stream().filter(r -> r.get("actionSeq").getAsLong() == sequence
                    && r.get("status").getAsString().equals(status)).findFirst().orElseThrow();
        }
        @Override public void close() { projectiles.close(); bukkit.close(); }
    }
}
