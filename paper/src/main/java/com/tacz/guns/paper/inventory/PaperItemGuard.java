package com.tacz.guns.paper.inventory;

import com.google.gson.JsonObject;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Set;

/** Prevent carrier materials from entering vanilla transformations while preserving ordinary storage. */
public final class PaperItemGuard implements Listener {
    private static final Set<InventoryType> TRANSFORMERS = Set.of(InventoryType.ANVIL, InventoryType.SMITHING,
            InventoryType.GRINDSTONE, InventoryType.STONECUTTER, InventoryType.LOOM, InventoryType.CARTOGRAPHY,
            InventoryType.ENCHANTING, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER,
            InventoryType.BREWING, InventoryType.MERCHANT);
    private final PaperItemStore items;
    private final PaperInventoryService inventories;
    private final BridgePeer peer;
    public PaperItemGuard(JavaPlugin plugin, PaperItemStore items, PaperInventoryService inventories, BridgePeer peer) {
        this.items = items; this.inventories = inventories; this.peer = peer; Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    private boolean marked(ItemStack stack) { return items.read(stack) != null; }
    private boolean any(ItemStack[] stacks) { for (ItemStack stack : stacks) if (marked(stack)) return true; return false; }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        JsonObject state = items.read(event.getItem()); if (state == null || !PaperItemStore.string(state, "kind", "").equals("box")) return;
        // This also suppresses chest placement when the client has not completed the bridge handshake.
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            JsonObject main = items.read(event.getPlayer().getInventory().getItemInMainHand());
            if (main != null && PaperItemStore.string(main, "kind", "").equals("box")) return;
        }
        if (!peer.ready(event.getPlayer())) return;
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? 40 : event.getPlayer().getInventory().getHeldItemSlot();
        try { inventories.open(event.getPlayer(), "box", slot); } catch (IllegalArgumentException e) { event.getPlayer().sendMessage("§c[TACZ] " + e.getMessage()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) { if (marked(event.getItemInHand())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void craft(PrepareItemCraftEvent event) { if (any(event.getInventory().getMatrix())) event.getInventory().setResult(null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craftCommit(CraftItemEvent event) { if (any(event.getInventory().getMatrix())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void anvil(PrepareAnvilEvent event) { if (marked(event.getInventory().getItem(0)) || marked(event.getInventory().getItem(1))) event.setResult(null); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void smith(PrepareSmithingEvent event) { for (int i = 0; i < 3; i++) if (marked(event.getInventory().getItem(i))) event.setResult(null); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void grind(PrepareGrindstoneEvent event) { if (marked(event.getInventory().getItem(0)) || marked(event.getInventory().getItem(1))) event.setResult(null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(FurnaceBurnEvent event) { if (marked(event.getFuel())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void smelt(FurnaceSmeltEvent event) { if (marked(event.getSource())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) { if (TRANSFORMERS.contains(event.getDestination().getType()) && marked(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory(); if (!TRANSFORMERS.contains(top.getType())) return;
        boolean intoTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
        if (intoTop && marked(event.getCursor())) event.setCancelled(true);
        if (!intoTop && event.isShiftClick() && marked(event.getCurrentItem())) event.setCancelled(true);
        if (intoTop && event.getHotbarButton() >= 0 && marked(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) event.setCancelled(true);
        if (intoTop && event.getClick() == ClickType.SWAP_OFFHAND && marked(event.getWhoClicked().getInventory().getItemInOffHand())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory(); if (TRANSFORMERS.contains(top.getType()) && marked(event.getOldCursor()) && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) event.setCancelled(true);
    }
}
