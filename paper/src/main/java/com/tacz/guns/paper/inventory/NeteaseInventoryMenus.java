package com.tacz.guns.paper.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tacz.guns.paper.network.PaperNetwork;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

import static com.tacz.guns.paper.item.PaperItemStore.integer;
import static com.tacz.guns.paper.item.PaperItemStore.string;

/** Bedrock can use standard containers; mutations still use the existing token-checked service. */
public final class NeteaseInventoryMenus implements Listener {
    private final JavaPlugin plugin;
    private final PaperNetwork network;

    public NeteaseInventoryMenus(JavaPlugin plugin, PaperNetwork network) {
        this.plugin = plugin;
        this.network = network;
        network.neteaseMenus((player, data) -> open(player, data, 0));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void open(Player player, JsonObject data, int page) {
        JsonArray entries = data.getAsJsonArray("entries");
        Menu holder = new Menu(player, data, page);
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text(string(data, "title", "TACZ")));
        for (int i = 0; i < 45 && page * 45 + i < entries.size(); i++) {
            JsonObject entry = entries.get(page * 45 + i).getAsJsonObject();
            holder.inventory.setItem(i, icon(Material.PAPER, string(entry, "name", string(entry, "id", "TACZ")),
                    string(entry, "description", ""), "box".equals(string(data, "menu", ""))
                            ? "左键取出 / 右键存入；Shift 操作全部" : "点击操作"));
        }
        if (page > 0 || integer(data, "page", 0) > 0) holder.inventory.setItem(45, icon(Material.ARROW, "上一页", "", ""));
        holder.inventory.setItem(49, icon(Material.BARRIER, "关闭", "", ""));
        if ((page + 1) * 45 < entries.size() || integer(data, "page", 0) + 1 < integer(data, "pages", 1))
            holder.inventory.setItem(53, icon(Material.ARROW, "下一页", "", ""));
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu previous) previous.replacing = true;
        player.openInventory(holder.inventory);
    }

    private static ItemStack icon(Material material, String name, String description, String hint) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name));
            meta.lore(List.of(Component.text(description), Component.text(hint)));
        });
        return item;
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player) || menu.pending) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54 || event.getClick().isKeyboardClick()) return;
        boolean shift = event.isShiftClick(), right = event.isRightClick();
        menu.pending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
            if (slot == 49) { player.closeInventory(); return; }
            if (slot == 45 || slot == 53) {
                int direction = slot == 45 ? -1 : 1;
                if ("workbench".equals(string(menu.data, "menu", ""))) {
                    int page = integer(menu.data, "page", 0) + direction;
                    if (page >= 0 && page < integer(menu.data, "pages", 1)) {
                        JsonObject request = request(menu, "open_workbench"); request.addProperty("page", page);
                        network.handleNeteaseMenu(player, request);
                    }
                } else {
                    int page = menu.page + direction;
                    if (page >= 0 && page * 45 < menu.data.getAsJsonArray("entries").size()) open(player, menu.data, page);
                }
                menu.pending = false;
                return;
            }
            JsonArray entries = menu.data.getAsJsonArray("entries");
            int index = menu.page * 45 + slot;
            if (slot >= 45 || index >= entries.size()) { menu.pending = false; return; }
            JsonObject row = entries.get(index).getAsJsonObject();
            network.handleNeteaseMenu(player, action(menu.data, row, right, shift));
            menu.pending = false;
        });
    }

    static JsonObject action(JsonObject data, JsonObject row, boolean right, boolean shift) {
        String kind = string(row, "kind", ""), id = string(row, "id", "");
        String type = string(data, "menu", "");
        JsonObject request;
        if (type.equals("workbench")) {
            request = request(data, "craft"); request.addProperty("recipe", id);
        } else if (type.equals("box")) {
            request = request(data, right ? "box_store" : "box_take");
            request.addProperty("id", id); request.addProperty("count", shift ? 4096 : 64);
        } else {
            request = request(data, kind.equals("attachment") ? "refit" : "unload");
            if (kind.equals("attachment")) request.addProperty("attachmentSlot", integer(row, "slot", -1));
            if (!kind.equals("unload")) request.addProperty("type", string(row, "type", ""));
        }
        return request;
    }

    private static JsonObject request(Menu menu, String op) {
        return request(menu.data, op);
    }

    private static JsonObject request(JsonObject data, String op) {
        JsonObject result = new JsonObject(); result.addProperty("op", op);
        for (String field : List.of("token", "slot", "instance"))
            if (data.has(field)) result.add(field, data.get(field).deepCopy());
        return result;
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu) || !(event.getPlayer() instanceof Player player)) return;
        if (menu.replacing) return;
        network.handleNeteaseMenu(player, request(menu, "close_" + string(menu.data, "menu", "")));
    }

    private static final class Menu implements InventoryHolder {
        final Player owner;
        final JsonObject data;
        final int page;
        Inventory inventory;
        boolean pending;
        boolean replacing;
        Menu(Player owner, JsonObject data, int page) { this.owner = owner; this.data = data.deepCopy(); this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
