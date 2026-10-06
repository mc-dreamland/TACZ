package com.tacz.guns.paper.inventory;

import com.google.gson.JsonObject;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.pack.DefaultGunPack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

import static com.tacz.guns.paper.item.PaperItemStore.string;

/** An operator-only native inventory catalog. Every displayed item is an inert template. */
public final class PaperSupplyMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int INFO = 49;
    private static final int NEXT = 53;
    private static final Set<ClickType> GRANT_CLICKS = Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);
    private final JavaPlugin plugin;
    private final DefaultGunPack pack;
    private final PaperItemStore items;
    private final Map<UUID, SupplyHolder> sessions = new HashMap<>();

    public PaperSupplyMenu(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items) {
        this.plugin = plugin;
        this.pack = pack;
        this.items = items;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Supply menu must open on the server thread");
        requireOperator(player);
        JsonObject state = items.read(player.getInventory().getItemInMainHand());
        if (state == null || !"gun".equals(string(state, "kind", ""))) throw new IllegalArgumentException("请先手持 TACZ 枪械");
        String gunId = string(state, "id", "");
        List<Supply> supplies = supplies(gunId);
        SupplyHolder holder = new SupplyHolder(player.getUniqueId(), gunId, string(state, "instance", ""), player.getInventory().getHeldItemSlot(), supplies);
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("TACZ 枪械补给", NamedTextColor.DARK_GREEN));
        render(holder);
        // Register before opening: closing an older view must not remove this new session.
        sessions.put(player.getUniqueId(), holder);
        player.openInventory(holder.inventory);
        if (player.getOpenInventory().getTopInventory() != holder.inventory) {
            sessions.remove(player.getUniqueId(), holder);
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof SupplyHolder old) closeCurrent(player, old);
        }
    }

    private List<Supply> supplies(String gunId) {
        JsonObject gun = pack.gun(gunId);
        if (gun == null) throw new IllegalArgumentException("枪械配置不存在");
        List<Supply> result = new ArrayList<>();
        String ammoId = string(gun, "ammo", "");
        if (pack.ammoIndexes().containsKey(ammoId)) result.add(new Supply("ammo", ammoId));
        pack.attachmentIndexes().keySet().stream().sorted().filter(id -> compatible(gunId, id)).forEach(id -> result.add(new Supply("attachment", id)));
        return List.copyOf(result);
    }

    private boolean compatible(String gunId, String attachmentId) {
        JsonObject gun = pack.gun(gunId), attachment = pack.attachmentIndexes().get(attachmentId);
        return gun != null && attachment != null && PaperItemStore.allowsType(gun, string(attachment, "type", "")) && pack.allowedAttachment(gunId, attachmentId);
    }

    private void render(SupplyHolder holder) {
        holder.inventory.clear();
        int start = holder.page * PAGE_SIZE;
        for (int index = start; index < Math.min(holder.supplies.size(), start + PAGE_SIZE); index++) {
            Supply supply = holder.supplies.get(index);
            ItemStack template = items.create(supply.kind, supply.id, 1);
            ItemMeta meta = template.getItemMeta();
            List<Component> lore = new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));
            lore.add(Component.text("点击领取 1 个", NamedTextColor.GREEN));
            if (supply.kind.equals("ammo")) lore.add(Component.text("Shift + 点击：领取一整组（" + template.getMaxStackSize() + "）", NamedTextColor.YELLOW));
            else lore.add(Component.text("Shift + 点击也领取 1 个配件", NamedTextColor.GRAY));
            meta.lore(lore);
            template.setItemMeta(meta);
            holder.inventory.setItem(index - start, template);
        }
        int pages = Math.max(1, (holder.supplies.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        JsonObject gunIndex = pack.gunIndexes().get(holder.gunId);
        Component gunName = gunIndex != null && gunIndex.has("name") ? Component.translatable(gunIndex.get("name").getAsString()).fallback(holder.gunId) : Component.text(holder.gunId);
        holder.inventory.setItem(INFO, button(Material.BOOK, "第 " + (holder.page + 1) + " / " + pages + " 页", List.of(gunName, Component.text("仅 OP 可领取；不消耗材料", NamedTextColor.GRAY))));
        if (holder.page > 0) holder.inventory.setItem(PREVIOUS, button(Material.ARROW, "上一页", List.of()));
        if (holder.page + 1 < pages) holder.inventory.setItem(NEXT, button(Material.ARROW, "下一页", List.of()));
    }

    private static ItemStack button(Material material, String title, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SupplyHolder holder)) return;
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        try {
            validate(player, holder, top);
            // Never reinterpret number keys, offhand swaps, double-clicks, drops or creative clone as a grant.
            if (previouslyCancelled || !GRANT_CLICKS.contains(event.getClick())) return;
            int rawSlot = event.getRawSlot();
            if (rawSlot < 0 || rawSlot >= top.getSize()) return;
            if (rawSlot >= PAGE_SIZE && rawSlot != PREVIOUS && rawSlot != NEXT || holder.pending) return;
            int pageAtClick = holder.page;
            boolean shift = event.isShiftClick();
            holder.pending = true;
            // Bukkit click processing owns the inventory until the event returns. Recheck all authority next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                holder.pending = false;
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != holder.inventory || pageAtClick != holder.page) return;
                try {
                    validate(player, holder, holder.inventory);
                    grantOrNavigate(player, holder, rawSlot, shift);
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§c[TACZ] " + e.getMessage());
                    closeCurrent(player, holder);
                }
            });
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c[TACZ] " + e.getMessage());
            holder.invalidated = true;
            // Closing a view from inside an InventoryClickEvent is unsafe; keep it tracked until next tick.
            Bukkit.getScheduler().runTask(plugin, () -> closeCurrent(player, holder));
        }
    }

    private void grantOrNavigate(Player player, SupplyHolder holder, int rawSlot, boolean shift) {
        if (rawSlot == PREVIOUS && holder.page > 0) { holder.page--; render(holder); return; }
        if (rawSlot == NEXT && (holder.page + 1) * PAGE_SIZE < holder.supplies.size()) { holder.page++; render(holder); return; }
        if (rawSlot >= PAGE_SIZE) return;
        int index = holder.page * PAGE_SIZE + rawSlot;
        if (index >= holder.supplies.size()) return;
        Supply supply = holder.supplies.get(index);
        JsonObject gun = pack.gun(holder.gunId);
        if (gun == null || (supply.kind.equals("ammo")
                ? !supply.id.equals(string(gun, "ammo", "")) || !pack.ammoIndexes().containsKey(supply.id)
                : !compatible(holder.gunId, supply.id))) throw new IllegalArgumentException("枪械配置已变化，请重新打开补给菜单");
        // Create a fresh item from authoritative catalog identity, never hand out the displayed stack.
        ItemStack granted = items.create(supply.kind, supply.id, 1);
        if (supply.kind.equals("ammo") && shift) granted.setAmount(granted.getMaxStackSize());
        items.give(player, granted);
    }

    private void validate(Player player, SupplyHolder holder, Inventory inventory) {
        requireOperator(player);
        if (holder.invalidated || !holder.owner.equals(player.getUniqueId()) || sessions.get(player.getUniqueId()) != holder || inventory != holder.inventory) throw new IllegalArgumentException("补给菜单已失效，请重新打开");
        JsonObject state = items.read(player.getInventory().getItemInMainHand());
        if (state == null || !"gun".equals(string(state, "kind", "")) || !holder.gunId.equals(string(state, "id", ""))
                || !holder.instance.equals(string(state, "instance", "")) || holder.heldSlot != player.getInventory().getHeldItemSlot()) throw new IllegalArgumentException("手持枪械已更换，请重新打开补给菜单");
    }

    private static void requireOperator(Player player) {
        if (!player.isOp()) throw new IllegalArgumentException("只有 OP 可以使用枪械补给菜单");
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) throw new IllegalArgumentException("当前状态不能领取物品");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SupplyHolder) event.setCancelled(true);
    }

    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof SupplyHolder holder) sessions.remove(event.getPlayer().getUniqueId(), holder);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }

    private void closeCurrent(Player player, SupplyHolder holder) {
        holder.invalidated = true;
        if (player.getOpenInventory().getTopInventory() == holder.inventory) player.closeInventory();
        sessions.remove(player.getUniqueId(), holder);
    }

    public void clear() {
        sessions.clear();
        // Scan views as well: a cancelled open or an invalidated session must never outlive listener removal.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof SupplyHolder holder) closeCurrent(player, holder);
        }
    }

    private record Supply(String kind, String id) {}
    private static final class SupplyHolder implements InventoryHolder {
        final UUID owner;
        final String gunId;
        final String instance;
        final int heldSlot;
        final List<Supply> supplies;
        Inventory inventory;
        int page;
        boolean pending;
        boolean invalidated;
        SupplyHolder(UUID owner, String gunId, String instance, int heldSlot, List<Supply> supplies) { this.owner = owner; this.gunId = gunId; this.instance = instance; this.heldSlot = heldSlot; this.supplies = supplies; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
