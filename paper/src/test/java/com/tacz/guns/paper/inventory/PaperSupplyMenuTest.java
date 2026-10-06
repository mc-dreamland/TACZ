package com.tacz.guns.paper.inventory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.pack.DefaultGunPack;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperSupplyMenuTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    @Test void nonOperatorCannotOpenEvenWithWildcardPermission() {
        try (Fixture f = new Fixture(2)) {
            when(f.player.isOp()).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> f.service.open(f.player));
            verify(f.player, never()).openInventory(any(Inventory.class)); assertTrue(f.given.isEmpty());
        }
    }

    @Test void catalogFiltersBothTagsAndSlotTypesAndPaginatesAllNinetyNineAttachments() {
        try (Fixture f = new Fixture(99)) {
            f.service.open(f.player);
            Set<String> shown = new HashSet<>();
            for (int page = 0; page < 3; page++) {
                for (int slot = 0; slot < 45; slot++) {
                    ItemStack template = f.contents.get(f.top())[slot]; if (template != null) shown.add(f.identities.get(template));
                }
                if (page < 2) { f.click(53, ClickType.LEFT); f.tick(); }
            }
            assertEquals(100, shown.size()); assertTrue(shown.contains("ammo/tacz:bullet"));
            assertFalse(shown.contains("attachment/tacz:tag_denied")); assertFalse(shown.contains("attachment/tacz:slot_denied"));
            assertTrue(f.given.isEmpty(), "Navigation and rendering cannot grant supplies");
        }
    }

    @Test void normalClickGivesOneAndShiftAmmoUsesItsActualStackSizeWithFreshItems() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player); ItemStack template = f.contents.get(f.top())[0];
            InventoryClickEvent normal = f.click(0, ClickType.LEFT); assertTrue(normal.isCancelled()); assertTrue(f.given.isEmpty(), "Grant must wait for the next tick"); f.tick();
            assertEquals(1, f.given.getFirst().getAmount()); assertNotSame(template, f.given.getFirst());
            f.click(0, ClickType.SHIFT_RIGHT); f.tick(); assertEquals(36, f.given.get(1).getAmount()); assertEquals(1, template.getAmount());
            f.click(1, ClickType.SHIFT_LEFT); f.tick(); assertEquals(1, f.given.get(2).getAmount()); assertEquals("attachment/tacz:scope_000", f.identities.get(f.given.get(2)));
        }
    }

    @Test void bottomShiftNumberKeysOffhandDoubleClickCloneDropAndDragNeverGrantOrMoveTemplates() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player);
            for (ClickType click : List.of(ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK, ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.UNKNOWN)) assertTrue(f.click(0, click).isCancelled());
            assertTrue(f.click(54, ClickType.SHIFT_LEFT).isCancelled()); assertTrue(f.click(54, ClickType.LEFT).isCancelled());
            InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getView()).thenReturn(f.view); f.service.drag(drag); verify(drag).setCancelled(true);
            f.tick(); assertTrue(f.given.isEmpty()); assertEquals(1, f.contents.get(f.top())[0].getAmount());
        }
    }

    @Test void losingOperatorBeforeClickClosesInvalidViewAndDoesNotGrant() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player); Inventory menu = f.top(); when(f.player.isOp()).thenReturn(false);
            assertTrue(f.click(0, ClickType.LEFT).isCancelled()); assertSame(menu, f.top(), "Close must be deferred outside click event"); f.tick();
            assertNotSame(menu, f.top()); assertTrue(f.given.isEmpty()); verify(f.player).closeInventory();
        }
    }

    @Test void queuedGrantRechecksOperatorAndGunInstance() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player); Inventory first = f.top(); f.click(0, ClickType.LEFT); when(f.player.isOp()).thenReturn(false); f.tick();
            assertTrue(f.given.isEmpty()); assertNotSame(first, f.top());
            when(f.player.isOp()).thenReturn(true); f.service.open(f.player); Inventory second = f.top(); f.click(0, ClickType.LEFT); f.heldState.addProperty("instance", UUID.randomUUID().toString()); f.tick();
            assertTrue(f.given.isEmpty()); assertNotSame(second, f.top());
        }
    }

    @Test void closingOrClearingBeforeNextTickCancelsQueuedGrantsAndClosesInvalidMenus() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player); f.click(0, ClickType.SHIFT_LEFT); f.closeView(); f.tick(); assertTrue(f.given.isEmpty());
            f.service.open(f.player); Inventory second = f.top(); f.click(0, ClickType.SHIFT_LEFT); f.service.clear(); assertNotSame(second, f.top()); f.tick(); assertTrue(f.given.isEmpty());
            f.service.open(f.player); Inventory third = f.top(); when(f.player.isOp()).thenReturn(false); f.click(0, ClickType.LEFT);
            f.service.clear(); assertNotSame(third, f.top(), "Disable/reload cleanup must close even an invalidated session awaiting its close task"); f.tick(); assertTrue(f.given.isEmpty());
        }
    }

    @Test void previouslyCancelledClickAndForeignHolderDoNotProduceItems() {
        try (Fixture f = new Fixture(2)) {
            f.service.open(f.player); InventoryClickEvent denied = f.event(0, ClickType.LEFT); denied.setCancelled(true); f.service.click(denied); f.tick(); assertTrue(f.given.isEmpty());
            f.closeView(); InventoryClickEvent ordinary = f.event(0, ClickType.LEFT); f.service.click(ordinary); assertFalse(ordinary.isCancelled());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedConstruction<ItemStack> buttons = mockConstruction(ItemStack.class, (stack, context) -> { ItemMeta meta = mock(ItemMeta.class); when(stack.getItemMeta()).thenReturn(meta); });
        final Player player = mock(Player.class);
        final PlayerInventory playerInventory = mock(PlayerInventory.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final DefaultGunPack pack = mock(DefaultGunPack.class);
        final PaperItemStore items = mock(PaperItemStore.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Map<Inventory, ItemStack[]> contents = new IdentityHashMap<>();
        final Map<ItemStack, String> identities = new IdentityHashMap<>();
        final List<ItemStack> given = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();
        final ItemStack held = mock(ItemStack.class);
        final JsonObject heldState = json("{kind:'gun',id:'tacz:test',instance:'22222222-2222-2222-2222-222222222222'}");
        InventoryView view = emptyView();
        final PaperSupplyMenu service;

        Fixture(int attachmentCount) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            PluginManager manager = mock(PluginManager.class); bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(Component.class))).thenAnswer(invocation -> {
                Inventory inventory = mock(Inventory.class); ItemStack[] slots = new ItemStack[54]; contents.put(inventory, slots);
                when(inventory.getHolder()).thenReturn(invocation.getArgument(0)); when(inventory.getSize()).thenReturn(54);
                when(inventory.getItem(anyInt())).thenAnswer(i -> slots[i.getArgument(0)]);
                doAnswer(i -> { slots[i.getArgument(0)] = i.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
                doAnswer(i -> { Arrays.fill(slots, null); return null; }).when(inventory).clear(); return inventory;
            });
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOp()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
            when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); when(player.getInventory()).thenReturn(playerInventory);
            when(playerInventory.getItemInMainHand()).thenReturn(held); when(playerInventory.getHeldItemSlot()).thenReturn(0);
            when(player.getOpenInventory()).thenAnswer(i -> view);
            when(player.openInventory(any(Inventory.class))).thenAnswer(i -> { view = mock(InventoryView.class); when(view.getTopInventory()).thenReturn(i.getArgument(0)); return view; });
            doAnswer(i -> { view = emptyView(); return null; }).when(player).closeInventory();
            when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(i -> { scheduled.add(i.getArgument(1)); return mock(BukkitTask.class); });
            when(items.read(any())).thenAnswer(i -> i.getArgument(0) == held ? heldState.deepCopy() : null);
            when(items.create(anyString(), anyString(), anyInt())).thenAnswer(i -> supply(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
            doAnswer(i -> { given.add(i.getArgument(1)); return null; }).when(items).give(eq(player), any());
            when(pack.gun("tacz:test")).thenReturn(json("{ammo:'tacz:bullet',allow_attachment_types:['scope']}"));
            when(pack.gunIndexes()).thenReturn(Map.of("tacz:test", json("{name:'test.gun'}")));
            when(pack.ammoIndexes()).thenReturn(Map.of("tacz:bullet", json("{stack_size:36}")));
            Map<String, JsonObject> attachments = new TreeMap<>(); for (int n = 0; n < attachmentCount; n++) attachments.put("tacz:scope_" + String.format(Locale.ROOT, "%03d", n), json("{type:'scope'}"));
            attachments.put("tacz:tag_denied", json("{type:'scope'}")); attachments.put("tacz:slot_denied", json("{type:'stock'}"));
            when(pack.attachmentIndexes()).thenReturn(attachments); when(pack.allowedAttachment(eq("tacz:test"), anyString())).thenAnswer(i -> !i.<String>getArgument(1).equals("tacz:tag_denied"));
            service = new PaperSupplyMenu(plugin, pack, items);
        }

        private ItemStack supply(String kind, String id, int amount) {
            ItemStack stack = mock(ItemStack.class); ItemMeta meta = mock(ItemMeta.class); AtomicInteger size = new AtomicInteger(amount);
            when(stack.getItemMeta()).thenReturn(meta); when(stack.getAmount()).thenAnswer(i -> size.get()); when(stack.getMaxStackSize()).thenReturn(kind.equals("ammo") ? 36 : 64);
            doAnswer(i -> { size.set(i.getArgument(0)); return null; }).when(stack).setAmount(anyInt()); identities.put(stack, kind + "/" + id); return stack;
        }
        Inventory top() { return view.getTopInventory(); }
        InventoryClickEvent event(int rawSlot, ClickType type) {
            InventoryClickEvent event = mock(InventoryClickEvent.class); AtomicBoolean cancelled = new AtomicBoolean();
            when(event.getView()).thenReturn(view); when(event.getWhoClicked()).thenReturn(player); when(event.getRawSlot()).thenReturn(rawSlot); when(event.getClick()).thenReturn(type);
            when(event.isShiftClick()).thenReturn(type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT); when(event.isCancelled()).thenAnswer(i -> cancelled.get());
            doAnswer(i -> { cancelled.set(i.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean()); return event;
        }
        InventoryClickEvent click(int rawSlot, ClickType type) { InventoryClickEvent event = event(rawSlot, type); service.click(event); return event; }
        void tick() { List<Runnable> tasks = new ArrayList<>(scheduled); scheduled.clear(); tasks.forEach(Runnable::run); }
        void closeView() { Inventory previous = top(); InventoryCloseEvent event = mock(InventoryCloseEvent.class); when(event.getInventory()).thenReturn(previous); when(event.getPlayer()).thenReturn(player); service.close(event); view = emptyView(); }
        static InventoryView emptyView() { InventoryView view = mock(InventoryView.class); Inventory inventory = mock(Inventory.class); when(view.getTopInventory()).thenReturn(inventory); return view; }
        @Override public void close() { buttons.close(); bukkit.close(); }
    }
}
