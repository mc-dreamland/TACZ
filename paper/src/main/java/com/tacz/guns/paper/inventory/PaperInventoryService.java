package com.tacz.guns.paper.inventory;

import com.google.gson.*;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.LongSupplier;
import static com.tacz.guns.paper.item.PaperItemStore.*;
import static com.tacz.guns.paper.inventory.MenuEntries.entry;

/** All mutations execute on the server thread and use catalog data, never client-supplied item state. */
public final class PaperInventoryService {
    private final JavaPlugin plugin;
    private final DefaultGunPack pack;
    private final PaperItemStore items;
    private final BridgePeer peer;
    private final LongSupplier clock;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private record Session(String menu, String token, int slot, String instance, long expires, Location origin, int page) {}
    private static final Map<String, Material> FORGE_TAGS = Map.ofEntries(
            Map.entry("forge:ingots/iron", Material.IRON_INGOT), Map.entry("forge:ingots/gold", Material.GOLD_INGOT),
            Map.entry("forge:ingots/copper", Material.COPPER_INGOT), Map.entry("forge:ingots/netherite", Material.NETHERITE_INGOT),
            Map.entry("forge:gems/diamond", Material.DIAMOND), Map.entry("forge:gems/lapis", Material.LAPIS_LAZULI),
            Map.entry("forge:gems/quartz", Material.QUARTZ), Map.entry("forge:gems/amethyst", Material.AMETHYST_SHARD),
            Map.entry("forge:dusts/glowstone", Material.GLOWSTONE_DUST), Map.entry("forge:dusts/redstone", Material.REDSTONE),
            Map.entry("forge:gunpowder", Material.GUNPOWDER), Map.entry("forge:leather", Material.LEATHER),
            Map.entry("forge:nuggets/iron", Material.IRON_NUGGET), Map.entry("forge:ores/netherite_scrap", Material.ANCIENT_DEBRIS),
            Map.entry("forge:rods/blaze", Material.BLAZE_ROD));
    public PaperInventoryService(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items, BridgePeer peer) { this(plugin, pack, items, peer, System::currentTimeMillis); }
    public PaperInventoryService(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items, BridgePeer peer, LongSupplier clock) { this.plugin = plugin; this.pack = pack; this.items = items; this.peer = peer; this.clock = clock; }
    public void close(Player player) { sessions.remove(player.getUniqueId()); }
    public void clear() { sessions.clear(); }
    public void handle(Player player, JsonObject request) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory transaction off the server thread");
        try {
            String op = string(request, "op", "");
            if (op.equals("close_refit")) {
                Session current = sessions.get(player.getUniqueId()); JsonElement token = request.get("token");
                // A late response may be discarded after the player moved or opened another menu.
                // Closing carries no mutation and must never invalidate a newer token or emit an error.
                if (current != null && current.menu.equals("refit") && token != null && token.isJsonPrimitive()
                        && token.getAsJsonPrimitive().isString() && current.token.equals(token.getAsString())) sessions.remove(player.getUniqueId());
                return;
            }
            require(peer.ready(player), "TACZ 客户端桥接尚未就绪");
            require(!player.isDead() && player.getGameMode() != GameMode.SPECTATOR, "当前状态不能操作物品");
            switch (op) {
                case "open_workbench" -> open(player, "workbench", -1, integer(request, "page", 0));
                case "open_refit" -> open(player, "refit", player.getInventory().getHeldItemSlot(), 0, requestId(request), null);
                case "open_box" -> open(player, "box", player.getInventory().getHeldItemSlot());
                case "craft" -> { Session session = session(player, request, "workbench"); craft(player, request); finish(player, session); }
                case "refit" -> { Session session = session(player, request, "refit"); refit(player, session, request); finish(player, session); }
                case "unload" -> { Session session = session(player, request, "refit"); unload(player, session, request); finish(player, session); }
                case "laser_color" -> {
                    Session session = session(player, request, "refit");
                    boolean close = false;
                    if (request.has("close")) { require(request.get("close").isJsonPrimitive() && request.getAsJsonPrimitive("close").isBoolean(), "无效的关闭选项"); close = request.get("close").getAsBoolean(); }
                    ItemStack stack = player.getInventory().getItem(session.slot); JsonObject state = items.read(stack);
                    applyColors(state, request); items.write(stack, state); player.getInventory().setItem(session.slot, stack);
                    if (close) { sessions.remove(player.getUniqueId()); peer.send(player, "inventory_changed", new JsonObject()); }
                    else finish(player, session);
                }
                case "box_store", "box_fill" -> { Session session = session(player, request, "box"); box(player, session, request, true); finish(player, session); }
                case "box_take" -> { Session session = session(player, request, "box"); box(player, session, request, false); finish(player, session); }
                default -> throw new IllegalArgumentException("未知库存操作");
            }
        } catch (IllegalArgumentException e) {
            JsonObject error = new JsonObject(); error.addProperty("message", e.getMessage());
            for (String field : List.of("op", "requestId", "token")) {
                JsonElement value = request.get(field);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().length() <= 128) error.add(field, value.deepCopy());
            }
            peer.send(player, "error", error); player.sendMessage("§c[TACZ] " + e.getMessage());
        }
    }
    private Session session(Player player, JsonObject request, String menu) {
        checkPermission(player, menu); Session session = sessions.get(player.getUniqueId());
        require(session != null && session.menu.equals(menu) && session.token.equals(string(request, "token", "")) && session.expires >= clock.getAsLong(), "菜单已失效，请重新打开");
        require(player.getWorld().equals(session.origin.getWorld()) && player.getLocation().distanceSquared(session.origin) <= 64, "距离工作位置过远，请重新打开菜单");
        if (session.slot >= 0) {
            require(integer(request, "slot", session.slot) == session.slot, "物品槽位不匹配");
            JsonObject state = items.read(player.getInventory().getItem(session.slot));
            require(state != null && session.instance.equals(string(state, "instance", "")), "物品已移动或更换，请重新打开菜单");
            if (menu.equals("refit")) {
                require(player.getInventory().getHeldItemSlot() == session.slot, "请保持手持正在改装的枪械");
                require(session.instance.equals(string(request, "instance", "")) && string(state, "kind", "").equals("gun"), "枪械实例不匹配");
            }
        }
        return session;
    }
    private void checkPermission(Player player, String menu) { require(player.hasPermission("tacz." + (menu.equals("box") ? "ammobox" : menu)), "没有操作权限"); }
    private void finish(Player player, Session session) {
        // Consume the token before outbound hooks: an exception while sending must not resurrect a completed transaction.
        sessions.remove(player.getUniqueId()); peer.send(player, "inventory_changed", new JsonObject()); open(player, session.menu, session.slot, session.page, null, session.token);
    }
    public void open(Player player, String menu, int slot) { open(player, menu, slot, 0); }
    private void open(Player player, String menu, int slot, int page) {
        open(player, menu, slot, page, null, null);
    }
    private void open(Player player, String menu, int slot, int page, String requestId, String previousToken) {
        require(peer.ready(player), "TACZ 客户端桥接尚未就绪"); checkPermission(player, menu);
        require(!player.isDead() && player.getGameMode() != GameMode.SPECTATOR, "当前状态不能操作物品");
        require(Set.of("workbench", "refit", "box").contains(menu), "未知菜单");
        JsonObject state = null; String instance = "";
        if (!menu.equals("workbench")) {
            require(validSlot(slot), "无效的物品槽位"); state = items.read(player.getInventory().getItem(slot));
            require(state != null && string(state, "kind", "").equals(menu.equals("refit") ? "gun" : "box"), menu.equals("refit") ? "请手持枪械" : "请手持弹药箱"); instance = string(state, "instance", "");
            if (menu.equals("refit")) require(player.getInventory().getHeldItemSlot() == slot, "请手持正在改装的枪械");
        }
        require(page >= 0 && page < 10000, "无效页码");
        String token = UUID.randomUUID().toString();
        JsonObject payload = new JsonObject(); payload.addProperty("menu", menu); payload.addProperty("token", token); payload.addProperty("slot", slot);
        payload.addProperty("title", switch (menu) { case "workbench" -> "TACZ 工作台"; case "refit" -> "TACZ 枪械改装"; default -> "TACZ 弹药箱"; });
        if (state != null) payload.add("state", state.deepCopy());
        if (menu.equals("refit")) {
            payload.addProperty("instance", instance);
            if (requestId != null) payload.addProperty("requestId", requestId);
            if (previousToken != null) { payload.addProperty("refresh", true); payload.addProperty("previousToken", previousToken); }
        }
        JsonArray entries = new JsonArray();
        if (menu.equals("workbench")) {
            pack.recipes().forEach((id, recipe) -> {
                if (!recipe.has("materials") || !recipe.has("result")) return;
                JsonObject result = recipe.getAsJsonObject("result"); String kind = string(result, "type", ""), resultId = string(result, "id", "");
                if (!pack.hasItem(kind, resultId)) return;
                entries.add(MenuEntries.recipe(id, recipe));
            });
        } else if (menu.equals("refit")) {
            String gunId = string(state, "id", ""); JsonObject gun = pack.gun(gunId);
            for (int inventorySlot : storageSlots()) {
                JsonObject attachment = items.read(player.getInventory().getItem(inventorySlot)); if (attachment == null || !string(attachment, "kind", "").equals("attachment")) continue;
                String id = string(attachment, "id", ""), type = string(pack.attachmentIndexes().get(id), "type", "");
                if (!allowsType(gun, type) || !pack.allowedAttachment(gunId, id)) continue;
                JsonObject row = entry(id, id, "attachment", type); row.addProperty("slot", inventorySlot); row.addProperty("type", type); entries.add(row);
            }
            for (Map.Entry<String, JsonElement> attachment : state.getAsJsonObject("attachments").entrySet()) { JsonObject row = entry(attachment.getValue().getAsString(), attachment.getValue().getAsString(), "installed", "卸下 " + attachment.getKey()); row.addProperty("type", attachment.getKey()); entries.add(row); }
            entries.add(entry("unload_ammo", "卸下枪内弹药", "unload", "退还弹匣与膛内弹药"));
        } else {
            String storedId = string(state, "boxAmmoId", "");
            for (String id : pack.ammoIndexes().keySet()) if (looseAmmo(player, id) > 0 || storedId.equals(id)) {
                JsonObject row = entry(id, id, "ammo", "背包: " + looseAmmo(player, id) + (storedId.equals(id) ? " 箱内: " + integer(state, "boxAmmo", 0) : "")); entries.add(row);
            }
        }
        if (menu.equals("workbench")) {
            int pages = Math.max(1, (entries.size() + 39) / 40); require(page < pages, "页码超出范围");
            JsonArray slice = new JsonArray(); for (int i = page * 40; i < Math.min(entries.size(), (page + 1) * 40); i++) slice.add(entries.get(i));
            payload.add("entries", slice); payload.addProperty("page", page); payload.addProperty("pages", pages);
        } else payload.add("entries", entries);
        sessions.put(player.getUniqueId(), new Session(menu, token, slot, instance, clock.getAsLong() + 120_000, player.getLocation().clone(), page));
        peer.send(player, "menu", payload);
    }
    private void craft(Player player, JsonObject request) {
        String recipeId = string(request, "recipe", string(request, "entry", "")); JsonObject recipe = pack.recipes().get(recipeId); require(recipe != null && recipe.has("materials") && recipe.has("result"), "配方不存在");
        int count = integer(request, "count", 1); require(count >= 1 && count <= 64, "合成次数必须为 1–64");
        JsonObject output = recipe.getAsJsonObject("result"); String kind = string(output, "type", ""), id = string(output, "id", "");
        long outputCount = (long) integer(output, "count", 1) * count; require(outputCount > 0 && outputCount <= 4096 && pack.hasItem(kind, id), "配方结果无效");
        ItemStack[] plan = player.getInventory().getStorageContents(); for (int i = 0; i < plan.length; i++) if (plan[i] != null) plan[i] = plan[i].clone();
        for (JsonElement element : recipe.getAsJsonArray("materials")) {
            JsonObject requirement = element.getAsJsonObject(); long needed = (long) integer(requirement, "count", 1) * count; require(needed > 0 && needed <= Integer.MAX_VALUE, "配方材料数量无效");
            for (int i = 0; i < plan.length && needed > 0; i++) {
                ItemStack stack = plan[i]; if (stack == null || stack.getType() == Material.AIR || stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(BRIDGE_KEY)) continue;
                if (!matches(stack.getType(), requirement.get("item"))) continue;
                int take = (int) Math.min(needed, stack.getAmount()); stack.setAmount(stack.getAmount() - take); needed -= take; if (stack.getAmount() == 0) plan[i] = null;
            }
            require(needed == 0, "合成材料不足：" + requirement.get("item"));
        }
        // Construct before committing so a bad catalog result cannot consume ingredients.
        List<ItemStack> results = new ArrayList<>();
        if (kind.equals("gun") || kind.equals("box")) for (int i = 0; i < outputCount; i++) results.add(items.create(kind, id, 1));
        else results.add(items.create(kind, id, (int) outputCount));
        player.getInventory().setStorageContents(plan); results.forEach(stack -> items.give(player, stack));
    }
    public static boolean matches(Material material, JsonElement ingredient) {
        if (ingredient.isJsonArray()) { for (JsonElement alternative : ingredient.getAsJsonArray()) if (matches(material, alternative)) return true; return false; }
        JsonObject object = ingredient.getAsJsonObject();
        if (object.has("item")) return material.getKey().toString().equals(object.get("item").getAsString());
        if (!object.has("tag")) return false; String tag = object.get("tag").getAsString();
        Material mapped = FORGE_TAGS.get(tag); if (mapped != null) return material == mapped;
        if (tag.equals("forge:glass")) return material == Material.GLASS || material.name().endsWith("_STAINED_GLASS");
        NamespacedKey key = NamespacedKey.fromString(tag); Tag<Material> bukkitTag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class); return bukkitTag != null && bukkitTag.isTagged(material);
    }
    private void refit(Player player, Session session, JsonObject request) {
        int sourceSlot = integer(request, "attachmentSlot", -1); require(validSlot(sourceSlot) && sourceSlot != session.slot, "配件槽位无效");
        ItemStack gunStack = player.getInventory().getItem(session.slot), attachmentStack = player.getInventory().getItem(sourceSlot);
        JsonObject state = items.read(gunStack), attachment = items.read(attachmentStack); require(state != null && attachment != null && string(attachment, "kind", "").equals("attachment"), "配件不存在");
        String id = string(attachment, "id", ""), type = string(pack.attachmentIndexes().get(id), "type", "");
        require(type.equals(string(request, "type", type)) && allowsType(pack.gun(string(state, "id", "")), type) && pack.allowedAttachment(string(state, "id", ""), id), "此枪械不支持该配件");
        JsonObject installed = state.getAsJsonObject("attachments"); String oldId = string(installed, type, ""); require(!oldId.equals(id), "已经安装同一配件");
        applyColors(state, request);
        ItemStack old = oldId.isEmpty() ? null : detachedAttachment(oldId, state, type);
        replaceAttachmentColor(state, type, attachment.has("laserColor") ? attachment.get("laserColor") : null);
        installed.addProperty(type, id); int excess = Math.max(0, integer(state, "ammo", 0) - items.capacity(state)); state.addProperty("ammo", integer(state, "ammo", 0) - excess);
        attachmentStack.setAmount(attachmentStack.getAmount() - 1); player.getInventory().setItem(sourceSlot, attachmentStack.getAmount() == 0 ? null : attachmentStack); items.write(gunStack, state); player.getInventory().setItem(session.slot, gunStack);
        if (old != null) items.give(player, old); refund(player, state, excess);
    }
    private void unload(Player player, Session session, JsonObject request) {
        ItemStack stack = player.getInventory().getItem(session.slot); JsonObject state = items.read(stack); require(state != null, "枪械不存在"); int refund;
        String type = string(request, "type", ""); applyColors(state, request);
        ItemStack attachment = null;
        if (type.isEmpty()) { refund = integer(state, "ammo", 0) + (state.get("chamber").getAsBoolean() ? 1 : 0); state.addProperty("ammo", 0); state.addProperty("chamber", false); }
        else {
            JsonObject installed = state.getAsJsonObject("attachments"); require(installed.has(type), "该槽未安装配件"); attachment = detachedAttachment(installed.remove(type).getAsString(), state, type);
            replaceAttachmentColor(state, type, null);
            refund = Math.max(0, integer(state, "ammo", 0) - items.capacity(state)); state.addProperty("ammo", integer(state, "ammo", 0) - refund);
        }
        items.write(stack, state); player.getInventory().setItem(session.slot, stack); if (attachment != null) items.give(player, attachment); refund(player, state, refund);
    }
    private void applyColors(JsonObject state, JsonObject request) {
        require(state != null && string(state, "kind", "").equals("gun"), "枪械不存在");
        // Validate every requested color before changing even the detached in-memory snapshot.
        Integer gunColor = null; JsonObject additions = new JsonObject();
        if (request.has("laserColor")) {
            gunColor = rgb(request.get("laserColor"));
            require(pack.laserEditable("gun", string(state, "id", "")), "此枪械不支持激光调色");
        }
        if (request.has("attachmentColors")) {
            require(request.get("attachmentColors").isJsonObject(), "无效的配件颜色数据");
            JsonObject requested = request.getAsJsonObject("attachmentColors"), installed = state.getAsJsonObject("attachments");
            require(requested.size() <= 6, "无效的配件颜色数据");
            for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
                int color = rgb(entry.getValue()); String type = entry.getKey();
                require(installed.has(type) && pack.laserEditable("attachment", installed.get(type).getAsString()), "该槽未安装可调色配件");
                additions.addProperty(type, color);
            }
        }
        if (gunColor != null) state.addProperty("laserColor", gunColor);
        additions.entrySet().forEach(entry -> replaceAttachmentColor(state, entry.getKey(), entry.getValue()));
    }
    private static void replaceAttachmentColor(JsonObject state, String type, JsonElement color) {
        JsonObject colors = state.has("attachmentColors") ? state.getAsJsonObject("attachmentColors") : new JsonObject();
        if (color == null) colors.remove(type); else colors.addProperty(type, rgb(color));
        if (colors.isEmpty()) state.remove("attachmentColors"); else state.add("attachmentColors", colors);
    }
    private ItemStack detachedAttachment(String id, JsonObject state, String type) {
        ItemStack stack = items.create("attachment", id, 1);
        if (state.has("attachmentColors") && state.getAsJsonObject("attachmentColors").has(type)) {
            JsonObject attachment = items.read(stack); require(attachment != null, "无法创建配件");
            attachment.addProperty("laserColor", rgb(state.getAsJsonObject("attachmentColors").get(type))); items.write(stack, attachment);
        }
        return stack;
    }
    private void refund(Player player, JsonObject state, int amount) { if (amount > 0) items.give(player, items.create("ammo", string(pack.gun(string(state, "id", "")), "ammo", ""), amount)); }
    private int looseAmmo(Player player, String id) { int total = 0; for (int slot : storageSlots()) { ItemStack stack = player.getInventory().getItem(slot); JsonObject state = items.read(stack); if (state != null && string(state, "kind", "").equals("ammo") && string(state, "id", "").equals(id)) total += stack.getAmount(); } return total; }
    private void box(Player player, Session session, JsonObject request, boolean store) {
        ItemStack stack = player.getInventory().getItem(session.slot); JsonObject state = items.read(stack); require(state != null, "弹药箱不存在");
        int wanted = integer(request, "count", 64); require(wanted >= 1 && wanted <= 4096, "每次存取数量必须为 1–4096");
        String storedId = string(state, "boxAmmoId", ""), id = string(request, "id", string(request, "entry", storedId)); require(pack.ammoIndexes().containsKey(id), "弹药类型不存在");
        int amount = integer(state, "boxAmmo", 0); require(amount == 0 || storedId.equals(id), "弹药箱只能存放一种弹药");
        if (store) {
            int transfer = Math.min(wanted, Math.min(integer(state, "boxCapacity", 0) - amount, looseAmmo(player, id))); require(transfer > 0, "背包没有这种弹药或弹药箱已满"); int remaining = transfer;
            for (int source : storageSlots()) { if (remaining == 0) break; ItemStack ammo = player.getInventory().getItem(source); JsonObject data = items.read(ammo);
                if (data == null || !string(data, "kind", "").equals("ammo") || !id.equals(string(data, "id", ""))) continue;
                int take = Math.min(ammo.getAmount(), remaining); ammo.setAmount(ammo.getAmount() - take); remaining -= take; player.getInventory().setItem(source, ammo.getAmount() == 0 ? null : ammo);
            }
            state.addProperty("boxAmmoId", id); state.addProperty("boxAmmo", amount + transfer);
        } else {
            int transfer = Math.min(amount, wanted); require(transfer > 0 && storedId.equals(id), "弹药箱内没有这种弹药"); ItemStack ammo = items.create("ammo", id, transfer);
            state.addProperty("boxAmmo", amount - transfer); items.write(stack, state); player.getInventory().setItem(session.slot, stack); items.give(player, ammo); return;
        }
        items.write(stack, state); player.getInventory().setItem(session.slot, stack);
    }
    private static boolean validSlot(int slot) { return slot >= 0 && slot < 36 || slot == 40; }
    private static String requestId(JsonObject request) {
        if (!request.has("requestId")) return null;
        require(request.get("requestId").isJsonPrimitive() && request.getAsJsonPrimitive("requestId").isString(), "无效的菜单请求标识");
        String id = request.get("requestId").getAsString();
        require(id.length() == 36, "无效的菜单请求标识"); UUID.fromString(id); return id;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
