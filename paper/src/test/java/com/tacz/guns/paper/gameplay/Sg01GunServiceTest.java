package com.tacz.guns.paper.gameplay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SG-01 actions exercise the real service, inventory snapshots and deterministic server time. */
class Sg01GunServiceTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject gun() {
        return json("""
                {script:'bujidao:sg_01_gun_logic',ammo:'tacz:12g',ammo_amount:6,bolt:'manual_action',
                 rpm:90,draw_time:0,put_away_time:0,fire_mode:['semi'],reload:{type:'magazine'},
                 script_param:{intro:.72,intro_empty:2,loop:.675,ending:.74,intro_empty_feed:1.07,
                   loop_feed:.46,bolt_time:1.15,bolt_feed_time:.72,shoot_time:.6838,
                   shoot_feed_time:.53,shoot_includes_bolt:true}}
                """);
    }

    @Test void emptyReloadFeedsAtTheAuthoredCheckpointsAndEndsWithSixTotalRounds() {
        try (Fixture f = new Fixture(gun())) {
            f.action("reload", 0);
            assertEquals("EMPTY_RELOAD_FEEDING", f.phase());
            assertEquals(5375, f.remaining());
            assertFalse(f.packet.get("reloadInterruptible").getAsBoolean());
            f.action("draw", 1069); assertEquals(0, f.loaded());
            f.action("draw", 1070); assertTrue(f.chamber()); assertEquals(0, f.ammo());
            f.action("draw", 2459); assertEquals(1, f.loaded());
            f.action("draw", 2460); assertEquals(2, f.loaded());
            // A long server pause catches up each due feed exactly once, retaining phase boundaries.
            f.action("draw", 5374);
            assertEquals(6, f.loaded()); assertEquals(5, f.ammo()); assertEquals(94, f.reserve.get());
            assertEquals("EMPTY_RELOAD_FEEDING", f.phase()); assertEquals(1, f.remaining());
            f.action("draw", 5375);
            assertEquals("EMPTY_RELOAD_FINISHING", f.phase()); assertEquals(740, f.remaining());
            f.action("draw", 6114); assertEquals(1, f.remaining());
            f.action("draw", 6115); assertEquals("NOT_RELOADING", f.phase());
            f.action("reload", 7000);
            assertEquals(1, f.events("reload")); assertEquals(6, f.loaded()); assertEquals(94, f.reserve.get());
        }
    }

    @Test void tacticalAndExtendedReloadsCountTheChamberAgainstCapacity() {
        for (int capacity : List.of(6, 9)) {
            try (Fixture f = new Fixture(gun())) {
                f.capacity.set(capacity); f.state.addProperty("ammo", capacity - 3); f.state.addProperty("chamber", true);
                f.action("reload", 0);
                assertEquals("TACTICAL_RELOAD_FEEDING", f.phase());
                f.action("draw", 1179); assertEquals(capacity - 2, f.loaded());
                f.action("draw", 1180); assertEquals(capacity - 1, f.loaded());
                f.action("draw", 1855); assertEquals(capacity, f.loaded());
                assertEquals("TACTICAL_RELOAD_FEEDING", f.phase());
                f.action("draw", 2070); assertEquals("TACTICAL_RELOAD_FINISHING", f.phase());
                f.action("draw", 2810); assertEquals("NOT_RELOADING", f.phase());
                assertEquals(98, f.reserve.get()); assertEquals(capacity - 1, f.ammo());
            }
        }
    }

    @Test void anEmptyChamberUsesEmptyIntroEvenWhenTheMagazineStillHasShells() {
        try (Fixture f = new Fixture(gun())) {
            f.state.addProperty("ammo", 3);
            f.action("reload", 0);
            assertEquals("EMPTY_RELOAD_FEEDING", f.phase());
            f.action("draw", 1070); assertEquals(4, f.loaded()); assertTrue(f.chamber());
            f.action("draw", 4090);
            assertEquals("NOT_RELOADING", f.phase()); assertEquals(6, f.loaded()); assertEquals(97, f.reserve.get());
        }
    }

    @Test void exhaustedInventoryFinishesAtThePhaseBoundaryWithoutInventingRounds() {
        try (Fixture f = new Fixture(gun())) {
            f.reserve.set(1);
            f.action("reload", 0);
            f.action("draw", 1070); assertEquals(1, f.loaded()); assertEquals(0, f.reserve.get());
            f.action("draw", 1999); assertEquals("EMPTY_RELOAD_FEEDING", f.phase());
            f.action("draw", 2000); assertEquals("EMPTY_RELOAD_FINISHING", f.phase());
            f.reserve.set(10);
            f.action("draw", 2740); assertEquals("NOT_RELOADING", f.phase());
            f.action("draw", 7000); assertEquals(1, f.loaded()); assertEquals(10, f.reserve.get());
        }
    }

    @Test void cancellationAndShootAttemptsDoNotTruncateTheUninterruptibleReload() {
        try (Fixture f = new Fixture(gun())) {
            f.sprinting.set(true); f.action("reload", 0); assertFalse(f.sprinting.get());
            PlayerToggleSprintEvent sprint = new PlayerToggleSprintEvent(f.player, true);
            f.service.sprint(sprint); assertTrue(sprint.isCancelled());
            when(f.player.isOnGround()).thenReturn(false);
            f.action("cancel_reload", 1070); assertEquals(1, f.loaded());
            f.action("shoot", 2460); assertEquals(2, f.loaded()); assertEquals(0, f.events("shoot"));
            f.action("cancel_reload", 5374);
            assertEquals("EMPTY_RELOAD_FEEDING", f.phase()); assertEquals(6, f.loaded());
            f.action("cancel_reload", 5375); assertEquals("EMPTY_RELOAD_FINISHING", f.phase());
            f.action("cancel_reload", 6000); assertEquals(115, f.remaining());
            f.action("draw", 6115); assertEquals("NOT_RELOADING", f.phase());
            assertEquals(0, f.events("cancel_reload")); assertEquals(94, f.reserve.get());
        }
    }

    @Test void aNinetyRpmShotDoesNotAddAnotherPumpDelayAfterItsCooldown() {
        try (Fixture f = new Fixture(gun())) {
            f.state.addProperty("ammo", 5); f.state.addProperty("chamber", true);
            f.action("shoot", 0); assertEquals(5, f.loaded()); assertFalse(f.chamber());
            f.action("bolt", 665); assertEquals(0, f.events("bolt"));
            f.action("bolt", 666);
            assertEquals(1, f.events("bolt")); assertTrue(f.chamber()); assertEquals(4, f.ammo());
            assertFalse(f.packet.get("bolting").getAsBoolean());
            f.action("shoot", 666); assertEquals(2, f.events("shoot")); assertEquals(4, f.loaded());
            f.action("bolt", 1332); assertTrue(f.chamber()); assertEquals(3, f.ammo());
            assertEquals(100, f.reserve.get());
        }
    }

    @Test void integratedPumpWaitsOnlyUntilTheShotFeedAndSettlesBeforeTheNextAction() {
        JsonObject gun = gun(); gun.addProperty("rpm", 600);
        try (Fixture f = new Fixture(gun)) {
            f.state.addProperty("ammo", 5); f.state.addProperty("chamber", true);
            f.action("shoot", 0); f.action("bolt", 100);
            assertTrue(f.packet.get("bolting").getAsBoolean());
            f.action("shoot", 529); assertEquals(1, f.events("shoot")); assertFalse(f.chamber());
            f.action("shoot", 530);
            assertEquals(2, f.events("shoot")); assertEquals(4, f.ammo()); assertFalse(f.chamber());
            assertFalse(f.packet.get("bolting").getAsBoolean());
            f.action("bolt", 630); f.tick(1060);
            assertTrue(f.chamber()); assertEquals(3, f.ammo());
        }
    }

    @Test void standaloneOrNonIntegratedBoltsReleaseAtTheFeedRatherThanAnimationTail() {
        for (boolean afterShot : List.of(false, true)) {
            JsonObject gun = gun(); gun.getAsJsonObject("script_param").addProperty("shoot_includes_bolt", false);
            try (Fixture f = new Fixture(gun)) {
                f.state.addProperty("ammo", 5); f.state.addProperty("chamber", afterShot);
                if (afterShot) f.action("shoot", 0);
                long start = afterShot ? 666 : 0;
                f.action("bolt", start);
                f.action("draw", start + 719); assertFalse(f.chamber());
                f.action("draw", start + 720); assertTrue(f.chamber()); assertEquals(4, f.ammo());
                assertFalse(f.packet.get("bolting").getAsBoolean());
                f.action("draw", start + 1150); assertEquals(4, f.ammo());
            }
        }
    }

    @Test void switchingGunsDiscardsOldReloadAndShotPumpState() {
        try (Fixture f = new Fixture(gun())) {
            f.action("reload", 0); f.action("draw", 1070); assertEquals(1, f.loaded());
            f.state.addProperty("instance", "second-gun"); f.action("draw", 1100);
            f.action("draw", 7000); assertEquals(1, f.loaded()); assertEquals("NOT_RELOADING", f.phase());
            f.state.addProperty("ammo", 5); f.action("shoot", 7100);
            f.state.addProperty("instance", "third-gun"); f.action("draw", 7800);
            f.action("bolt", 7800); assertFalse(f.chamber());
            f.action("draw", 8519); assertFalse(f.chamber());
            f.action("draw", 8520); assertTrue(f.chamber()); assertEquals(4, f.ammo());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<PaperBallistics> projectiles = mockConstruction(PaperBallistics.class);
        final Player player = mock(Player.class);
        final PaperItemStore items = mock(PaperItemStore.class);
        final BridgePeer peer = mock(BridgePeer.class);
        final ItemStack held = mock(ItemStack.class);
        final AtomicLong clock = new AtomicLong(1_000_000);
        final AtomicInteger reserve = new AtomicInteger(100), capacity = new AtomicInteger(6);
        final AtomicBoolean sprinting = new AtomicBoolean();
        final List<String> events = new ArrayList<>();
        final PaperGunService service;
        JsonObject state = json("{kind:'gun',id:'bujidao:sg_01',instance:'sg-test',ammo:0,chamber:false,fireMode:'semi',attachments:{}}");
        JsonObject packet;
        long sequence;

        Fixture(JsonObject gun) {
            try {
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
                JavaPlugin plugin = mock(JavaPlugin.class);
                when(plugin.getName()).thenReturn("TACZ"); when(plugin.namespace()).thenReturn("tacz");
                PlayerInventory inventory = mock(PlayerInventory.class);
                when(player.getInventory()).thenReturn(inventory); when(inventory.getItemInMainHand()).thenReturn(held);
                when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getEntityId()).thenReturn(1);
                when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
                when(player.hasPermission("tacz.use")).thenReturn(true); when(player.isOnGround()).thenReturn(true);
                when(player.isSprinting()).thenAnswer(i -> sprinting.get());
                doAnswer(i -> { sprinting.set(i.getArgument(0)); return null; }).when(player).setSprinting(anyBoolean());
                when(peer.ready(player)).thenReturn(true);
                when(items.read(held)).thenAnswer(i -> state.deepCopy());
                when(items.effectiveGun(any())).thenAnswer(i -> gun.deepCopy());
                when(items.capacity(any())).thenAnswer(i -> capacity.get());
                when(items.countAmmo(player, "tacz:12g")).thenAnswer(i -> reserve.get());
                when(items.takeAmmo(eq(player), eq("tacz:12g"), anyInt())).thenAnswer(i -> {
                    int count = Math.min(reserve.get(), (int) i.getArgument(2)); reserve.addAndGet(-count); return count;
                });
                doAnswer(i -> { state = ((JsonObject) i.getArgument(1)).deepCopy(); return null; }).when(items).write(eq(held), any());
                doAnswer(i -> {
                    String type = i.getArgument(1); JsonObject data = i.getArgument(2);
                    if (type.equals("state")) packet = data.deepCopy();
                    if (type.equals("event")) events.add(data.get("op").getAsString());
                    return null;
                }).when(peer).broadcast(eq(player), anyString(), any());
                service = spy(new PaperGunService(plugin, mock(DefaultGunPack.class), items, peer, clock::get));
                doReturn(true).when(service).applyMovement(any(), anyDouble());
                doNothing().when(service).clearMovement(any());
                action("draw", 0);
            } catch (RuntimeException | Error failure) { projectiles.close(); bukkit.close(); throw failure; }
        }

        void action(String op, long elapsed) {
            clock.set(1_000_000 + elapsed);
            JsonObject action = new JsonObject(); action.addProperty("op", op);
            action.addProperty("instance", state.get("instance").getAsString()); action.addProperty("seq", ++sequence);
            service.handle(player, action);
        }
        void tick(long elapsed) { clock.set(1_000_000 + elapsed); service.tick(); service.sync(player); }
        int ammo() { return state.get("ammo").getAsInt(); }
        boolean chamber() { return state.get("chamber").getAsBoolean(); }
        int loaded() { return ammo() + (chamber() ? 1 : 0); }
        String phase() { return packet.get("reloadState").getAsString(); }
        long remaining() { return packet.get("reloadRemaining").getAsLong(); }
        long events(String name) { return events.stream().filter(name::equals).count(); }
        @Override public void close() { projectiles.close(); bukkit.close(); }
    }
}
