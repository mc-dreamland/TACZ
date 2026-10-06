package com.tacz.guns.paper.inventory;

import com.google.gson.*;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperInventoryServiceTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void insufficientCraftMaterialsLeaveEveryInventorySlotUnchanged() {
        try (Fixture f = new Fixture()) {
            f.slots[1] = f.stack(Material.IRON_INGOT, 10, null); f.slots[2] = f.stack(Material.GOLD_INGOT, 1, null);
            f.service.open(f.player, "workbench", -1); JsonObject request = f.request("craft"); request.addProperty("recipe", "tacz:gun/test");
            f.service.handle(f.player, request);
            assertEquals(10, f.slots[1].getAmount()); assertEquals(1, f.slots[2].getAmount()); assertTrue(f.given.isEmpty());
            verify(f.inventory, never()).setStorageContents(any()); assertFalse(f.errors.isEmpty());
        }
    }
    @Test void successfulCraftConsumesExactlyOnceAndRotatesToken() {
        try (Fixture f = new Fixture()) {
            f.slots[1] = f.stack(Material.IRON_INGOT, 10, null); f.slots[2] = f.stack(Material.GOLD_INGOT, 3, null);
            f.service.open(f.player, "workbench", -1); JsonObject request = f.request("craft"); request.addProperty("recipe", "tacz:gun/test");
            f.service.handle(f.player, request); assertEquals(6, f.slots[1].getAmount()); assertEquals(1, f.slots[2].getAmount()); assertEquals(1, f.granted("gun", "tacz:test"));
            f.service.handle(f.player, request); assertEquals(6, f.slots[1].getAmount()); assertEquals(1, f.granted("gun", "tacz:test")); assertFalse(f.errors.isEmpty());
        }
    }
    @Test void aResultRemovedFromTheCatalogCannotConsumeCraftMaterials() {
        try (Fixture f = new Fixture()) {
            f.slots[1] = f.stack(Material.IRON_INGOT, 10, null); f.slots[2] = f.stack(Material.GOLD_INGOT, 3, null);
            f.service.open(f.player, "workbench", -1); JsonObject request = f.request("craft"); request.addProperty("recipe", "tacz:gun/test");
            when(f.pack.hasItem("gun", "tacz:test")).thenReturn(false);
            f.service.handle(f.player, request);
            assertEquals(10, f.slots[1].getAmount()); assertEquals(3, f.slots[2].getAmount()); assertTrue(f.given.isEmpty());
            verify(f.inventory, never()).setStorageContents(any()); verify(f.items, never()).create(anyString(), anyString(), anyInt());
            assertFalse(f.errors.isEmpty());
        }
    }
    @Test void refitConsumesNewReturnsOldAndRefundsReducedMagazineOverflow() {
        try (Fixture f = new Fixture()) {
            f.slots[0] = f.stack(Material.STICK, 1, f.gun()); f.slots[1] = f.stack(Material.FLINT, 2, json("{kind:'attachment',id:'tacz:small_mag'}"));
            f.service.open(f.player, "refit", 0); JsonObject request = f.request("refit"); request.addProperty("attachmentSlot", 1); request.addProperty("type", "extended_mag");
            f.service.handle(f.player, request);
            assertEquals(1, f.slots[1].getAmount()); JsonObject gun = f.states.get(f.slots[0]);
            assertEquals("tacz:small_mag", gun.getAsJsonObject("attachments").get("extended_mag").getAsString()); assertEquals(30, gun.get("ammo").getAsInt());
            assertEquals(1, f.granted("attachment", "tacz:large_mag")); assertEquals(8, f.granted("ammo", "tacz:bullet"));
        }
    }
    @Test void expiredTokenAndChangedGunInstanceRejectTransactions() {
        try (Fixture f = new Fixture()) {
            f.slots[0] = f.stack(Material.STICK, 1, f.gun()); f.service.open(f.player, "refit", 0); JsonObject request = f.request("unload");
            f.clock.addAndGet(120_001); f.service.handle(f.player, request); assertEquals(38, f.states.get(f.slots[0]).get("ammo").getAsInt()); assertTrue(f.given.isEmpty());
            f.service.open(f.player, "refit", 0); request = f.request("unload"); JsonObject replacement = f.gun(); replacement.addProperty("instance", UUID.randomUUID().toString()); f.slots[0] = f.stack(Material.STICK, 1, replacement);
            f.service.handle(f.player, request); assertEquals(38, f.states.get(f.slots[0]).get("ammo").getAsInt()); assertTrue(f.given.isEmpty()); assertEquals(2, f.errors.size());
        }
    }
    @Test void boxStoreTakeAndReplayConserveAmmunition() {
        try (Fixture f = new Fixture()) {
            f.slots[0] = f.stack(Material.CHEST, 1, json("{kind:'box',id:'tacz:ammo_box',instance:'11111111-1111-1111-1111-111111111111',boxAmmo:5,boxAmmoId:'tacz:bullet',boxCapacity:100,boxLevel:0}"));
            f.slots[1] = f.stack(Material.PAPER, 60, json("{kind:'ammo',id:'tacz:bullet'}")); f.service.open(f.player, "box", 0);
            JsonObject store = f.request("box_store"); store.addProperty("id", "tacz:bullet"); store.addProperty("count", 30); f.service.handle(f.player, store);
            assertEquals(30, f.slots[1].getAmount()); assertEquals(35, f.states.get(f.slots[0]).get("boxAmmo").getAsInt());
            JsonObject take = f.request("box_take"); take.addProperty("id", "tacz:bullet"); take.addProperty("count", 20); f.service.handle(f.player, take); f.service.handle(f.player, take);
            assertEquals(20, f.granted("ammo", "tacz:bullet")); assertEquals(15, f.states.get(f.slots[0]).get("boxAmmo").getAsInt());
            assertEquals(65, f.slots[1].getAmount() + f.states.get(f.slots[0]).get("boxAmmo").getAsInt() + f.granted("ammo", "tacz:bullet"));
        }
    }
    @Test void spectatorAndDeadPlayersCannotOpenOrCommitMenus() {
        try (Fixture f = new Fixture()) {
            when(f.player.getGameMode()).thenReturn(GameMode.SPECTATOR); assertThrows(IllegalArgumentException.class, () -> f.service.open(f.player, "workbench", -1));
            when(f.player.getGameMode()).thenReturn(GameMode.SURVIVAL); f.service.open(f.player, "workbench", -1); JsonObject request = f.request("craft"); request.addProperty("recipe", "tacz:gun/test");
            when(f.player.isDead()).thenReturn(true); f.service.handle(f.player, request); verify(f.inventory, never()).setStorageContents(any()); assertFalse(f.errors.isEmpty());
        }
    }
    @Test void nativeRefitEchoesOpenRequestAndRefreshesWithPreviousToken() {
        try (Fixture f = new Fixture()) {
            f.slots[0] = f.stack(Material.STICK, 1, f.gun());
            String requestId = UUID.randomUUID().toString(); JsonObject open = json("{op:'open_refit'}"); open.addProperty("requestId", requestId);
            f.service.handle(f.player, open);
            assertEquals(requestId, f.menu.get("requestId").getAsString());
            assertEquals(f.gun().get("instance"), f.menu.get("instance")); assertFalse(f.menu.has("refresh"));
            String oldToken = f.menu.get("token").getAsString(); JsonObject unload = f.request("unload");
            f.service.handle(f.player, unload);
            assertTrue(f.menu.get("refresh").getAsBoolean()); assertEquals(oldToken, f.menu.get("previousToken").getAsString());
            assertFalse(f.menu.has("requestId")); assertNotEquals(oldToken, f.menu.get("token").getAsString());
            f.service.open(f.player, "refit", 0);
            assertFalse(f.menu.has("requestId")); assertFalse(f.menu.has("refresh"), "Command opens use the same native menu without being tagged as an action refresh");
        }
    }
    @Test void closingSavesAllLaserColorsOnceWithoutReopeningAndOldCloseCannotInvalidateNewSession() {
        try (Fixture f = new Fixture()) {
            f.enableLasers(); f.slots[0] = f.stack(Material.STICK, 1, f.laserGun()); f.service.open(f.player, "refit", 0);
            JsonObject save = f.request("laser_color"); save.addProperty("laserColor", 0xABCDEF); save.add("attachmentColors", json("{laser:65280}")); save.addProperty("close", true);
            JsonObject oldClose = f.request("close_refit"); f.service.handle(f.player, save);
            assertEquals(0xABCDEF, f.states.get(f.slots[0]).get("laserColor").getAsInt());
            assertEquals(65280, f.states.get(f.slots[0]).getAsJsonObject("attachmentColors").get("laser").getAsInt());
            assertEquals(1, f.menus.size()); assertEquals(1, f.changed.get());
            f.service.handle(f.player, save); assertEquals(1, f.changed.get(), "A close transaction consumes its token");
            f.service.open(f.player, "refit", 0); JsonObject current = f.request("unload");
            f.service.handle(f.player, oldClose); f.service.handle(f.player, current);
            assertEquals(0, f.states.get(f.slots[0]).get("ammo").getAsInt(), "A stale close cannot discard the new session");
            JsonObject close = f.request("close_refit"); int menus = f.menus.size(), changed = f.changed.get();
            f.service.handle(f.player, close); assertEquals(menus, f.menus.size()); assertEquals(changed, f.changed.get());
        }
    }
    @Test void replacementAndRemovalPreserveAttachmentColorsAndFlushDraftsAtomically() {
        try (Fixture f = new Fixture()) {
            f.enableLasers(); f.slots[0] = f.stack(Material.STICK, 1, f.laserGun());
            f.slots[1] = f.stack(Material.FLINT, 2, json("{kind:'attachment',id:'tacz:laser_new',laserColor:65535}"));
            f.service.open(f.player, "refit", 0); JsonObject install = f.request("refit");
            install.addProperty("attachmentSlot", 1); install.addProperty("type", "laser"); install.addProperty("laserColor", 0x010203); install.add("attachmentColors", json("{laser:11259375}"));
            f.service.handle(f.player, install);
            assertEquals(1, f.slots[1].getAmount()); JsonObject gun = f.states.get(f.slots[0]);
            assertEquals("tacz:laser_new", gun.getAsJsonObject("attachments").get("laser").getAsString());
            assertEquals(65535, gun.getAsJsonObject("attachmentColors").get("laser").getAsInt());
            assertEquals(0x010203, gun.get("laserColor").getAsInt());
            JsonObject old = f.given.stream().filter(s -> s.get("id").getAsString().equals("tacz:laser_old")).findFirst().orElseThrow();
            assertEquals(11259375, old.get("laserColor").getAsInt());
            JsonObject unload = f.request("unload"); unload.addProperty("type", "laser"); unload.add("attachmentColors", json("{laser:1193046}"));
            f.service.handle(f.player, unload); gun = f.states.get(f.slots[0]);
            assertFalse(gun.getAsJsonObject("attachments").has("laser")); assertFalse(gun.has("attachmentColors"));
            assertEquals(0x010203, gun.get("laserColor").getAsInt());
            JsonObject detached = f.given.stream().filter(s -> s.get("id").getAsString().equals("tacz:laser_new")).findFirst().orElseThrow();
            assertEquals(1193046, detached.get("laserColor").getAsInt());
        }
    }
    @Test void closingIsSilentAndIdempotentAfterPermissionsHeldSlotOrReadyStateChange() {
        try (Fixture f = new Fixture()) {
            f.slots[0] = f.stack(Material.STICK, 1, f.gun()); f.service.open(f.player, "refit", 0);
            JsonObject close = f.request("close_refit"), oldAction = f.request("unload");
            when(f.inventory.getHeldItemSlot()).thenReturn(1); when(f.player.hasPermission("tacz.refit")).thenReturn(false);
            when(f.peer.ready(f.player)).thenReturn(false); when(f.player.isDead()).thenReturn(true);
            f.service.handle(f.player, close); f.service.handle(f.player, close);
            assertTrue(f.errors.isEmpty()); assertEquals(1, f.menus.size()); assertEquals(0, f.changed.get());
            when(f.inventory.getHeldItemSlot()).thenReturn(0); when(f.player.hasPermission("tacz.refit")).thenReturn(true);
            when(f.peer.ready(f.player)).thenReturn(true); when(f.player.isDead()).thenReturn(false);
            f.service.handle(f.player, oldAction); assertEquals(1, f.errors.size()); assertEquals(38, f.states.get(f.slots[0]).get("ammo").getAsInt());
        }
    }
    @Test void invalidColorBatchIsRejectedBeforeAnyColorOrInventoryMutation() {
        try (Fixture f = new Fixture()) {
            f.enableLasers(); f.slots[0] = f.stack(Material.STICK, 1, f.laserGun()); f.service.open(f.player, "refit", 0);
            JsonObject before = f.states.get(f.slots[0]).deepCopy();
            for (String bad : List.of("-1", "16777216", "1.25", "'123'", "null")) {
                JsonObject request = f.request("laser_color"); request.add("laserColor", JsonParser.parseString(bad)); f.service.handle(f.player, request);
            }
            JsonObject missing = f.request("laser_color"); missing.addProperty("laserColor", 100); missing.add("attachmentColors", json("{laser:200,scope:300}")); f.service.handle(f.player, missing);
            JsonObject notEditable = f.request("laser_color"); notEditable.add("attachmentColors", json("{extended_mag:100}")); f.service.handle(f.player, notEditable);
            assertEquals(before, f.states.get(f.slots[0])); verify(f.items, never()).write(any(), any());
            assertEquals(7, f.errors.size()); assertTrue(f.given.isEmpty()); assertEquals(0, f.changed.get());
            assertEquals("laser_color", f.errorPackets.getLast().get("op").getAsString());
            assertEquals(f.menu.get("token"), f.errorPackets.getLast().get("token"));
        }
    }
    @Test void nativeRefitRejectsChangedHeldSlotRequestInstanceAndPermissionRevocation() {
        try (Fixture f = new Fixture()) {
            f.enableLasers(); f.slots[0] = f.stack(Material.STICK, 1, f.laserGun()); f.service.open(f.player, "refit", 0);
            JsonObject request = f.request("laser_color"); request.addProperty("laserColor", 100);
            when(f.inventory.getHeldItemSlot()).thenReturn(1); f.service.handle(f.player, request);
            when(f.inventory.getHeldItemSlot()).thenReturn(0); request.addProperty("instance", UUID.randomUUID().toString()); f.service.handle(f.player, request);
            request = f.request("laser_color"); request.addProperty("laserColor", 100); when(f.player.hasPermission("tacz.refit")).thenReturn(false); f.service.handle(f.player, request);
            assertEquals(3, f.errors.size()); verify(f.items, never()).write(any(), any()); assertEquals(0, f.changed.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final PaperItemStore items = mock(PaperItemStore.class);
        final DefaultGunPack pack = mock(DefaultGunPack.class);
        final BridgePeer peer = mock(BridgePeer.class);
        final ItemStack[] slots = new ItemStack[41];
        final Map<ItemStack, JsonObject> states = new IdentityHashMap<>();
        final List<JsonObject> given = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<JsonObject> errorPackets = new ArrayList<>();
        final List<JsonObject> menus = new ArrayList<>();
        final AtomicInteger changed = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000);
        JsonObject menu;
        final PaperInventoryService service;
        Fixture() {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world = mock(World.class); when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenAnswer(i -> new Location(world, 0, 64, 0));
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getInventory()).thenReturn(inventory); when(player.hasPermission(anyString())).thenReturn(true); when(peer.ready(player)).thenReturn(true);
            when(inventory.getItem(anyInt())).thenAnswer(i -> slots[i.getArgument(0)]); when(inventory.getStorageContents()).thenAnswer(i -> Arrays.copyOf(slots, 36));
            doAnswer(i -> { slots[i.getArgument(0)] = i.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
            doAnswer(i -> { ItemStack[] values = i.getArgument(0); System.arraycopy(values, 0, slots, 0, values.length); return null; }).when(inventory).setStorageContents(any());
            when(items.read(any())).thenAnswer(i -> { JsonObject state = states.get(i.getArgument(0)); return state == null ? null : state.deepCopy(); });
            doAnswer(i -> { states.put(i.getArgument(0), ((JsonObject) i.getArgument(1)).deepCopy()); return null; }).when(items).write(any(), any());
            when(items.capacity(any())).thenAnswer(i -> { JsonObject state = i.getArgument(0); JsonObject attachments = state.getAsJsonObject("attachments"); return attachments.has("extended_mag") && attachments.get("extended_mag").getAsString().equals("tacz:large_mag") ? 40 : 30; });
            when(items.create(anyString(), anyString(), anyInt())).thenAnswer(i -> { JsonObject state = new JsonObject(); state.addProperty("kind", (String) i.getArgument(0)); state.addProperty("id", (String) i.getArgument(1)); return stack(PaperItemStore.material(i.getArgument(0)), i.getArgument(2), state); });
            doAnswer(i -> { ItemStack stack = i.getArgument(1); JsonObject item = states.get(stack).deepCopy(); item.addProperty("count", stack.getAmount()); given.add(item); return null; }).when(items).give(eq(player), any());
            doAnswer(i -> { String type = i.getArgument(1); JsonObject data = i.getArgument(2); if (type.equals("menu")) { menu = data.deepCopy(); menus.add(menu); } if (type.equals("inventory_changed")) changed.incrementAndGet(); if (type.equals("error")) { errors.add(data.get("message").getAsString()); errorPackets.add(data.deepCopy()); } return null; }).when(peer).send(eq(player), anyString(), any());
            JsonObject recipe = json("{materials:[{item:{tag:'forge:ingots/iron'},count:4},{item:{tag:'forge:ingots/gold'},count:2}],result:{type:'gun',id:'tacz:test'}}");
            when(pack.recipes()).thenReturn(Map.of("tacz:gun/test", recipe)); when(pack.hasItem("gun", "tacz:test")).thenReturn(true);
            when(pack.ammoIndexes()).thenReturn(Map.of("tacz:bullet", new JsonObject())); when(pack.gun(anyString())).thenReturn(json("{ammo:'tacz:bullet',ammo_amount:30,allow_attachment_types:['extended_mag']}"));
            when(pack.attachmentIndexes()).thenReturn(Map.of("tacz:small_mag", json("{type:'extended_mag'}"), "tacz:large_mag", json("{type:'extended_mag'}"))); when(pack.allowedAttachment(anyString(), anyString())).thenReturn(true);
            service = new PaperInventoryService(null, pack, items, peer, clock::get);
        }
        JsonObject gun() { return json("{kind:'gun',id:'tacz:test',instance:'22222222-2222-2222-2222-222222222222',ammo:38,chamber:true,attachments:{extended_mag:'tacz:large_mag'}}"); }
        JsonObject laserGun() { JsonObject gun = gun(); gun.getAsJsonObject("attachments").addProperty("laser", "tacz:laser_old"); gun.addProperty("laserColor", 0xFF0000); gun.add("attachmentColors", json("{laser:255}")); return gun; }
        void enableLasers() {
            when(pack.gun(anyString())).thenReturn(json("{ammo:'tacz:bullet',ammo_amount:30,allow_attachment_types:['extended_mag','laser']}"));
            when(pack.attachmentIndexes()).thenReturn(Map.of("tacz:small_mag", json("{type:'extended_mag'}"), "tacz:large_mag", json("{type:'extended_mag'}"), "tacz:laser_old", json("{type:'laser'}"), "tacz:laser_new", json("{type:'laser'}")));
            when(pack.laserEditable("gun", "tacz:test")).thenReturn(true); when(pack.laserEditable("attachment", "tacz:laser_old")).thenReturn(true); when(pack.laserEditable("attachment", "tacz:laser_new")).thenReturn(true);
        }
        JsonObject request(String op) { JsonObject request = new JsonObject(); request.addProperty("op", op); request.add("token", menu.get("token")); request.add("slot", menu.get("slot")); if (menu.has("instance")) request.add("instance", menu.get("instance")); return request; }
        ItemStack stack(Material material, int amount, JsonObject state) {
            ItemStack stack = mock(ItemStack.class); AtomicInteger count = new AtomicInteger(amount); when(stack.getType()).thenReturn(material); when(stack.getAmount()).thenAnswer(i -> count.get());
            doAnswer(i -> { count.set(i.getArgument(0)); return null; }).when(stack).setAmount(anyInt()); when(stack.clone()).thenAnswer(i -> stack(material, count.get(), state)); if (state != null) states.put(stack, state.deepCopy()); return stack;
        }
        int granted(String kind, String id) { return given.stream().filter(s -> s.get("kind").getAsString().equals(kind) && s.get("id").getAsString().equals(id)).mapToInt(s -> s.get("count").getAsInt()).sum(); }
        @Override public void close() { bukkit.close(); }
    }
}
