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
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReloadPlanTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject state(int ammo, boolean chamber) { return json("{\"ammo\":" + ammo + ",\"chamber\":" + chamber + ",\"fireMode\":\"semi\",\"attachments\":{}}"); }
    @Test void xmagUsesCorrectEmptyAndTacticalTiming() {
        JsonObject gun = json("""
          {"script":"tacz:xmag_reload_logic","script_param":{
            "empty_xmag_2_feed":2.5,"empty_xmag_2_cooldown":2.85,
            "reload_xmag_2_feed":1.54,"reload_xmag_2_cooldown":2.29}}
          """);
        ReloadPlan empty = ReloadPlan.create(gun, state(0, false), 37, 2);
        assertEquals(2500, empty.feeds().getFirst().time()); assertEquals(37, empty.feeds().getFirst().count()); assertEquals(2850, empty.duration());
        ReloadPlan tactical = ReloadPlan.create(gun, state(10, true), 37, 2);
        assertEquals(1540, tactical.feeds().getFirst().time()); assertEquals(27, tactical.feeds().getFirst().count()); assertEquals(2290, tactical.duration());
    }
    @Test void kar98ClipFillsSeparateChamberAndMagazineButScopeLoadsIndividually() {
        JsonObject gun = json("{\"script\":\"tacz:kar98_gun_logic\",\"script_param\":{\"clip_load_feed\":2.78,\"clip_load\":3,\"intro_empty\":.75,\"loop\":.68,\"loop_feed\":.42,\"ending\":.6}}");
        JsonObject state = state(0, false);
        ReloadPlan clip = ReloadPlan.create(gun, state, 4, 0);
        assertEquals(5, clip.feeds().stream().mapToInt(ReloadPlan.Feed::count).sum());
        assertTrue(clip.feeds().getFirst().chamber()); assertFalse(clip.progressive());
        state.getAsJsonObject("attachments").addProperty("scope", "tacz:scope_standard_8x");
        ReloadPlan scoped = ReloadPlan.create(gun, state, 4, 0);
        assertTrue(scoped.progressive()); assertEquals(5, scoped.feeds().size());
        assertEquals(1170, scoped.feeds().getFirst().time()); assertEquals(600, scoped.ending());
    }
    @Test void m870LoadsChamberFirstAndPreservesOneShellPerFeed() {
        JsonObject gun = json("{\"script\":\"tacz:m870_gun_logic\",\"script_param\":{\"intro_empty\":1,\"intro_empty_feed\":.6,\"loop\":.5,\"loop_feed\":.3,\"ending\":.5}}");
        ReloadPlan plan = ReloadPlan.create(gun, state(0, false), 5, 0);
        assertTrue(plan.progressive()); assertEquals(6, plan.feeds().size()); assertEquals(600, plan.feeds().getFirst().time());
        assertTrue(plan.feeds().getFirst().chamber()); assertEquals(1300, plan.feeds().get(1).time());
        assertEquals(6, plan.feeds().stream().mapToInt(ReloadPlan.Feed::count).sum());
    }
    @Test void m1014LoadsPairsAndAnOddFinalShellWithoutOverfilling() {
        JsonObject gun = json("{\"script\":\"tacz:m1014_gun_logic\",\"script_param\":{\"intro\":.5,\"loop\":.5,\"loop_2\":.8,\"loop_feed\":.3,\"loop_feed_2\":.6,\"ending\":.5}}");
        ReloadPlan plan = ReloadPlan.create(gun, state(1, true), 6, 0);
        assertEquals(3, plan.feeds().size()); assertEquals(2, plan.feeds().getFirst().count());
        assertEquals(5, plan.feeds().stream().mapToInt(ReloadPlan.Feed::count).sum()); assertEquals(1, plan.feeds().getLast().count());
    }
    @Test void hkMk23ChoosesModeAndClampsMagazineTimingLevel() {
        JsonObject gun = json("{\"script\":\"tacz:hk_mk23_logic\",\"script_param\":{\"empty_pump_xmag_2_feed\":2.27,\"empty_pump_xmag_2_cooldown\":2.7}}");
        ReloadPlan plan = ReloadPlan.create(gun, state(0, false), 24, 3);
        assertEquals(2270, plan.feeds().getFirst().time()); assertEquals(2700, plan.duration());
    }

    @Test void magazineReloadIgnoresSprintCancellationAndLoadsWhileAirborne() {
        for (String script : List.of("", "tacz:xmag_reload_logic", "tacz:hk_mk23_logic", "tacz:db_short_gun_logic")) {
            JsonObject gun = json("{bolt:'closed_bolt',ammo:'tacz:bullet',draw_time:0,reload:{feed:{empty:1},cooldown:{empty:2}},"
                    + "script_param:{empty_feed:1,empty_cooldown:2,empty_pump_feed:1,empty_pump_cooldown:2}}");
            gun.addProperty("script", script);
            try (ServiceFixture f = new ServiceFixture(gun, 30)) {
                f.sprinting.set(true);
                f.action("reload", 0);
                assertFalse(f.sprinting.get(), script);
                assertEquals("EMPTY_RELOAD_FEEDING", f.phase());
                assertFalse(f.packet.get("reloadInterruptible").getAsBoolean());
                PlayerToggleSprintEvent sprint = new PlayerToggleSprintEvent(f.player, true);
                f.service.sprint(sprint);
                assertTrue(sprint.isCancelled(), script);
                f.action("cancel_reload", 500);
                assertEquals("EMPTY_RELOAD_FEEDING", f.phase(), script);
                assertEquals(0, f.loaded(), script);

                // An airborne player can reach the feed checkpoint without landing or releasing R.
                when(f.player.isOnGround()).thenReturn(false);
                f.action("cancel_reload", 1_000);
                assertEquals(30, f.loaded(), script);
                assertEquals(70, f.reserve.get(), script);
                assertEquals("EMPTY_RELOAD_FINISHING", f.phase(), script);
                sprint = new PlayerToggleSprintEvent(f.player, true);
                f.service.sprint(sprint);
                assertFalse(sprint.isCancelled(), script);
                f.action("draw", 2_000);
                assertEquals("NOT_RELOADING", f.phase(), script);
                assertEquals(30, f.loaded(), script);
                assertEquals(0, f.events("cancel_reload"), script);
                assertTrue(f.messages.indexOf("state:EMPTY_RELOAD_FEEDING") < f.messages.indexOf("event:reload"));
            }
        }
    }

    @Test void progressiveCancellationSettlesDueFeedsOnceAndDoesNotRestartEnding() {
        JsonObject gun = json("{script:'tacz:m870_gun_logic',bolt:'manual_action',ammo:'tacz:bullet',draw_time:0,"
                + "script_param:{intro_empty:.3,intro_empty_feed:.2,loop:.4,loop_feed:.2,ending:.3}}");
        try (ServiceFixture f = new ServiceFixture(gun, 3)) {
            f.action("reload", 0);
            assertTrue(f.packet.get("reloadInterruptible").getAsBoolean());
            // No intervening tick: cancellation must first commit the chamber and first shell due.
            f.action("cancel_reload", 550);
            assertEquals(2, f.loaded());
            assertEquals(98, f.reserve.get());
            assertEquals("EMPTY_RELOAD_FINISHING", f.phase());
            assertFalse(f.packet.get("reloadInterruptible").getAsBoolean());
            f.action("cancel_reload", 600);
            f.action("cancel_reload", 750);
            assertEquals("EMPTY_RELOAD_FINISHING", f.phase());
            assertEquals(100, f.packet.get("reloadRemaining").getAsLong());
            f.action("draw", 850);
            assertEquals("NOT_RELOADING", f.phase());
            f.action("draw", 3_000);
            assertEquals(2, f.loaded());
            assertEquals(98, f.reserve.get());
            assertEquals(1, f.events("cancel_reload"));
            assertTrue(f.messages.indexOf("state:EMPTY_RELOAD_FINISHING") < f.messages.indexOf("event:cancel_reload"));
        }
    }

    @Test void kar98EmptyClipCannotBeInterruptedLikeItsScopedSingleRoundReload() {
        JsonObject gun = json("{script:'tacz:kar98_gun_logic',bolt:'manual_action',ammo:'tacz:bullet',draw_time:0,"
                + "script_param:{clip_load_feed:1,clip_load:1.5}}");
        try (ServiceFixture f = new ServiceFixture(gun, 4)) {
            f.action("reload", 0);
            f.action("cancel_reload", 500);
            assertEquals("EMPTY_RELOAD_FEEDING", f.phase());
            assertFalse(f.packet.get("reloadInterruptible").getAsBoolean());
            f.action("draw", 1_500);
            assertEquals(5, f.loaded());
            assertEquals(95, f.reserve.get());
            assertEquals("NOT_RELOADING", f.phase());
            assertEquals(0, f.events("cancel_reload"));
        }
    }

    /** Exercise public authoritative actions with independent inventory snapshots and a monotonic clock. */
    private static final class ServiceFixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit;
        final Player player = mock(Player.class);
        final ItemStack held = mock(ItemStack.class);
        final PaperItemStore items = mock(PaperItemStore.class);
        final BridgePeer peer = mock(BridgePeer.class);
        final AtomicLong clock = new AtomicLong(1_000_000);
        final AtomicInteger reserve = new AtomicInteger(100);
        final AtomicBoolean sprinting = new AtomicBoolean();
        final List<String> messages = new ArrayList<>();
        final PaperGunService service;
        JsonObject state = json("{kind:'gun',id:'tacz:test',instance:'test-instance',ammo:0,chamber:false,fireMode:'semi',attachments:{}}");
        JsonObject packet;
        long sequence;
        ServiceFixture(JsonObject gun, int capacity) {
            bukkit = mockStatic(Bukkit.class);
            try {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
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
            when(items.read(held)).thenAnswer(i -> state.deepCopy());
            when(items.effectiveGun(any())).thenAnswer(i -> gun.deepCopy());
            when(items.capacity(any())).thenReturn(capacity);
            when(items.countAmmo(player, "tacz:bullet")).thenAnswer(i -> reserve.get());
            when(items.takeAmmo(eq(player), eq("tacz:bullet"), anyInt())).thenAnswer(i -> {
                int taken = Math.min(reserve.get(), (int) i.getArgument(2)); reserve.addAndGet(-taken); return taken;
            });
            doAnswer(i -> { state = ((JsonObject) i.getArgument(1)).deepCopy(); return null; }).when(items).write(eq(held), any());
            doAnswer(i -> {
                String type = i.getArgument(1); JsonObject data = i.getArgument(2);
                if (type.equals("state")) { packet = data.deepCopy(); messages.add("state:" + phase()); }
                else if (type.equals("event")) messages.add("event:" + data.get("op").getAsString());
                return null;
            }).when(peer).broadcast(eq(player), anyString(), any());
            service = new PaperGunService(plugin, mock(DefaultGunPack.class), items, peer, clock::get);
            action("draw", 0);
            } catch (RuntimeException | Error failure) {
                bukkit.close();
                throw failure;
            }
        }
        void action(String op, long elapsed) {
            clock.set(1_000_000 + elapsed);
            JsonObject action = json("{instance:'test-instance'}"); action.addProperty("op", op); action.addProperty("seq", ++sequence);
            service.handle(player, action);
        }
        int loaded() { return state.get("ammo").getAsInt() + (state.get("chamber").getAsBoolean() ? 1 : 0); }
        String phase() { return packet.get("reloadState").getAsString(); }
        long events(String op) { return messages.stream().filter(message -> message.equals("event:" + op)).count(); }
        @Override public void close() { bukkit.close(); }
    }
}
