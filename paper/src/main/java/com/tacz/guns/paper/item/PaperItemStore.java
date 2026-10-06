package com.tacz.guns.paper.item;

import com.google.gson.*;
import com.tacz.guns.bridge.BridgeItemIdentity;
import com.tacz.guns.paper.pack.DefaultGunPack;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class PaperItemStore {
    public static final NamespacedKey BRIDGE_KEY = new NamespacedKey("tacz", "bridge");
    private static final Set<String> TYPES = Set.of("scope", "muzzle", "stock", "grip", "laser", "extended_mag");
    private final DefaultGunPack pack;
    private final JavaPlugin plugin;
    public PaperItemStore(JavaPlugin plugin, DefaultGunPack pack) { this.plugin = plugin; this.pack = pack; }
    public static String string(JsonObject object, String key, String fallback) { return object.has(key) ? object.get(key).getAsString() : fallback; }
    public static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        try { return object.get(key).getAsBigDecimal().intValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid integer field: " + key, e); }
    }
    public static Material material(String kind) { return switch (kind) { case "gun" -> Material.STICK; case "ammo" -> Material.PAPER; case "attachment" -> Material.FLINT; case "box" -> Material.CHEST; default -> Material.AIR; }; }

    /** Identity comes from the PDC kind/id, checked against the active catalog and vanilla carrier. */
    public JsonObject read(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta(); String raw = meta.getPersistentDataContainer().get(BRIDGE_KEY, PersistentDataType.STRING);
        try {
            JsonObject state = BridgeItemIdentity.parse(raw, stack.getType().getKey().toString(), pack::hasItem);
            if (state == null) return null;
            String kind = string(state, "kind", ""), id = string(state, "id", "");
            validateLaserColors(state);
            if (stack.getAmount() < 1 || stack.getAmount() > stack.getMaxStackSize()) return null;
            if ((kind.equals("gun") || kind.equals("box")) && stack.getAmount() != 1) return null;
            if (kind.equals("gun")) {
                JsonObject data = pack.gun(id), attachments = state.getAsJsonObject("attachments"); if (attachments == null || attachments.size() > 6) return null;
                for (Map.Entry<String, JsonElement> entry : attachments.entrySet()) {
                    JsonObject index = pack.attachmentIndexes().get(entry.getValue().getAsString());
                    if (!TYPES.contains(entry.getKey()) || index == null || !entry.getKey().equals(string(index, "type", "")) || !pack.allowedAttachment(id, entry.getValue().getAsString()) || !allowsType(data, entry.getKey())) return null;
                }
                int ammo = integer(state, "ammo", -1); if (ammo < 0 || ammo > capacity(state)) return null;
                if (!state.has("chamber") || !state.get("chamber").getAsJsonPrimitive().isBoolean()) return null;
                if (!data.getAsJsonArray("fire_mode").contains(new JsonPrimitive(string(state, "fireMode", "")))) return null;
                double heat = AttachmentModifiers.number(state, "heat", 0); if (!Double.isFinite(heat) || heat < 0 || heat > 1_000_000) return null;
            } else if (kind.equals("box")) {
                int level = DefaultGunPack.BOX_IDS.indexOf(id), amount = integer(state, "boxAmmo", -1);
                int storedCapacity = integer(state, "boxCapacity", -1);
                if (integer(state, "boxLevel", -1) != level || storedCapacity < 1 || storedCapacity > 1_000_000 || amount < 0 || amount > storedCapacity) return null;
                String ammoId = string(state, "boxAmmoId", ""); if (!ammoId.isEmpty() && !pack.ammoIndexes().containsKey(ammoId) || amount > 0 && ammoId.isEmpty()) return null;
            }
            return state;
        } catch (RuntimeException invalid) { return null; }
    }
    public static boolean allowsType(JsonObject data, String type) { return data.has("allow_attachment_types") && data.getAsJsonArray("allow_attachment_types").contains(new JsonPrimitive(type)); }
    public static int rgb(JsonElement value) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int color = value.getAsBigDecimal().intValueExact();
            if (color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException();
            return color;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("激光颜色必须为 0–16777215 的整数", invalid); }
    }
    /** Cosmetic values are bound to the actual catalog item and installed attachment slots. */
    public void validateLaserColors(JsonObject state) {
        String kind = string(state, "kind", ""), id = string(state, "id", "");
        if (state.has("laserColor")) {
            rgb(state.get("laserColor"));
            if (!pack.laserEditable(kind, id)) throw new IllegalArgumentException("此物品不支持激光调色");
        }
        if (!state.has("attachmentColors")) return;
        if (!kind.equals("gun") || !state.get("attachmentColors").isJsonObject()) throw new IllegalArgumentException("无效的配件颜色数据");
        JsonObject colors = state.getAsJsonObject("attachmentColors"), installed = state.getAsJsonObject("attachments");
        if (colors.size() > 6 || installed == null) throw new IllegalArgumentException("无效的配件颜色数据");
        for (Map.Entry<String, JsonElement> color : colors.entrySet()) {
            rgb(color.getValue());
            if (!TYPES.contains(color.getKey()) || !installed.has(color.getKey()) || !pack.laserEditable("attachment", installed.get(color.getKey()).getAsString()))
                throw new IllegalArgumentException("此配件不支持激光调色");
        }
    }
    public void write(ItemStack stack, JsonObject state) {
        String kind = string(state, "kind", ""), id = string(state, "id", "");
        if (!pack.hasItem(kind, id) || stack.getType() != material(kind)) throw new IllegalArgumentException("Invalid TACZ item identity");
        state.remove("cmd");
        ItemMeta meta = stack.getItemMeta();
        meta.setMaxStackSize(kind.equals("gun") || kind.equals("box") ? 1 : kind.equals("ammo") ? Math.min(64, Math.max(1, integer(pack.ammoIndexes().get(id), "stack_size", 64))) : 64);
        meta.getPersistentDataContainer().set(BRIDGE_KEY, PersistentDataType.STRING, state.toString());
        JsonObject index = switch (kind) { case "gun" -> pack.gunIndexes().get(id); case "ammo" -> pack.ammoIndexes().get(id); case "attachment" -> pack.attachmentIndexes().get(id); default -> null; };
        meta.displayName(index != null && index.has("name") ? Component.translatable(index.get("name").getAsString()) : Component.text("TACZ " + id.substring(id.indexOf(':') + 1)));
        List<Component> lore = new ArrayList<>(); lore.add(Component.text(id));
        if (kind.equals("gun")) {
            lore.add(Component.text(integer(state, "ammo", 0) + "/" + capacity(state) + " · " + string(state, "fireMode", "semi")));
            String ammoId = string(pack.gun(id), "ammo", "");
            JsonObject ammoIndex = pack.ammoIndexes().get(ammoId);
            Component ammoName = ammoIndex != null && ammoIndex.has("name")
                    ? Component.translatable(ammoIndex.get("name").getAsString()).fallback(ammoId)
                    : Component.text(ammoId);
            lore.add(Component.text("所需弹药：").append(ammoName));
        }
        if (kind.equals("box")) lore.add(Component.text(string(state, "boxAmmoId", "") + " " + integer(state, "boxAmmo", 0) + "/" + integer(state, "boxCapacity", 0)));
        meta.lore(lore); stack.setItemMeta(meta);
    }
    public ItemStack create(String kind, String id, int count) {
        if (count < 1 || count > 4096 || !pack.hasItem(kind, id)) throw new IllegalArgumentException("Unknown item or invalid count");
        if ((kind.equals("gun") || kind.equals("box")) && count != 1) throw new IllegalArgumentException("Guns and ammunition boxes cannot stack");
        ItemStack stack = new ItemStack(material(kind)); JsonObject state = new JsonObject(); state.addProperty("kind", kind); state.addProperty("id", id);
        if (kind.equals("gun")) {
            state.addProperty("instance", UUID.randomUUID().toString()); state.addProperty("ammo", 0); state.addProperty("chamber", false); state.addProperty("heat", 0);
            state.addProperty("fireMode", pack.gun(id).getAsJsonArray("fire_mode").get(0).getAsString()); state.add("attachments", new JsonObject());
        } else if (kind.equals("box")) {
            int level = DefaultGunPack.BOX_IDS.indexOf(id); state.addProperty("instance", UUID.randomUUID().toString()); state.addProperty("boxAmmo", 0);
            state.addProperty("boxAmmoId", ""); state.addProperty("boxLevel", level); state.addProperty("boxCapacity", boxCapacity(level));
        }
        write(stack, state); stack.setAmount(count); return stack;
    }
    public int boxCapacity(int level) { return Math.max(1, Math.min(1_000_000, plugin.getConfig().getInt("ammo-box.capacity." + level, new int[]{192, 384, 768}[Math.max(0, Math.min(2, level))]))); }
    public int capacity(JsonObject state) {
        JsonObject gun = pack.gun(string(state, "id", "")); if (gun == null) return 0; int amount = integer(gun, "ammo_amount", 0);
        JsonObject attachments = state.has("attachments") ? state.getAsJsonObject("attachments") : new JsonObject();
        if (attachments.has("extended_mag") && gun.has("extended_mag_ammo_amount")) {
            JsonObject attachment = pack.attachment(attachments.get("extended_mag").getAsString()); int level = attachment == null ? 0 : integer(attachment, "extended_mag_level", 0);
            JsonArray capacities = gun.getAsJsonArray("extended_mag_ammo_amount"); if (level > 0 && level <= capacities.size()) amount = capacities.get(level - 1).getAsInt();
        } return Math.max(0, amount);
    }
    public JsonObject effectiveGun(JsonObject state) {
        JsonObject raw = pack.gun(string(state, "id", "")); if (raw == null) return null; List<JsonObject> modifiers = new ArrayList<>();
        if (state.has("attachments")) state.getAsJsonObject("attachments").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> { JsonObject a = pack.attachment(e.getValue().getAsString()); if (a != null) modifiers.add(a); });
        JsonObject result = AttachmentModifiers.apply(raw, string(state, "fireMode", "semi"), modifiers); result.addProperty("ammo_amount", capacity(state));
        if (state.has("attachments") && state.getAsJsonObject("attachments").has("extended_mag")
                && pack.attachmentHasTag(state.getAsJsonObject("attachments").get("extended_mag").getAsString(), "tacz:intrinsic/slug"))
            AttachmentModifiers.object(result, "bullet").addProperty("bullet_amount", 1);
        return result;
    }
    public int countAmmo(Player player, String id) {
        long total = 0; for (int slot : storageSlots()) { ItemStack stack = player.getInventory().getItem(slot); JsonObject state = read(stack); if (state == null) continue;
            if (string(state, "kind", "").equals("ammo") && id.equals(string(state, "id", ""))) total += stack.getAmount();
            if (string(state, "kind", "").equals("box") && id.equals(string(state, "boxAmmoId", ""))) total += integer(state, "boxAmmo", 0);
        } return (int) Math.min(Integer.MAX_VALUE, total);
    }
    public int takeAmmo(Player player, String id, int wanted) {
        int remaining = Math.max(0, wanted);
        for (int slot : storageSlots()) { if (remaining == 0) break; ItemStack stack = player.getInventory().getItem(slot); JsonObject state = read(stack); if (state == null) continue;
            if (string(state, "kind", "").equals("ammo") && id.equals(string(state, "id", ""))) {
                int take = Math.min(stack.getAmount(), remaining); stack.setAmount(stack.getAmount() - take); remaining -= take; player.getInventory().setItem(slot, stack.getAmount() == 0 ? null : stack);
            } else if (string(state, "kind", "").equals("box") && id.equals(string(state, "boxAmmoId", ""))) {
                int take = Math.min(integer(state, "boxAmmo", 0), remaining); state.addProperty("boxAmmo", integer(state, "boxAmmo", 0) - take); remaining -= take; write(stack, state); player.getInventory().setItem(slot, stack);
            }
        } return Math.max(0, wanted) - remaining;
    }
    public static int[] storageSlots() { int[] slots = new int[37]; for (int i = 0; i < 36; i++) slots[i] = i; slots[36] = 40; return slots; }
    public void give(Player player, ItemStack stack) {
        int remaining = stack.getAmount(), maximum = stack.getMaxStackSize();
        while (remaining > 0) { ItemStack part = stack.clone(); part.setAmount(Math.min(maximum, remaining)); remaining -= part.getAmount();
            player.getInventory().addItem(part).values().forEach(leftover -> { var drop = player.getWorld().dropItemNaturally(player.getLocation(), leftover); drop.setOwner(player.getUniqueId()); });
        }
    }
}
