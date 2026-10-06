package com.tacz.guns.paper.command;

import com.google.gson.JsonObject;
import com.tacz.guns.paper.inventory.PaperInventoryService;
import com.tacz.guns.paper.inventory.PaperSupplyMenu;
import com.tacz.guns.paper.item.PaperItemStore;
import com.tacz.guns.paper.network.BridgePeer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class TaczCommand implements CommandExecutor, TabCompleter {
    private static final List<String> COMMANDS = List.of("help", "status", "list", "give", "supplies", "workbench", "refit", "ammobox", "reload");
    private static final List<String> KINDS = List.of("gun", "ammo", "attachment", "box");
    private final JavaPlugin plugin;
    private final DefaultGunPack pack;
    private final PaperItemStore items;
    private final PaperInventoryService inventories;
    private final PaperSupplyMenu supplies;
    private final BridgePeer peer;
    private final Runnable reload;
    public TaczCommand(JavaPlugin plugin, DefaultGunPack pack, PaperItemStore items, PaperInventoryService inventories, PaperSupplyMenu supplies, BridgePeer peer, Runnable reload) {
        this.plugin = plugin; this.pack = pack; this.items = items; this.inventories = inventories; this.supplies = supplies; this.peer = peer; this.reload = reload;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            require(COMMANDS.contains(sub), "未知子命令，请使用 /tacz help");
            require(canUse(sender, sub), sub.equals("supplies") ? "该命令仅限 OP 玩家使用" : "没有权限：tacz." + sub);
            switch (sub) {
                case "help" -> {
                    sender.sendMessage("§6TACZ Paper 命令");
                    for (String available : COMMANDS) if (canUse(sender, available)) sender.sendMessage("§e/tacz " + switch (available) {
                        case "list" -> "list <gun|ammo|attachment|box> [页码]";
                        case "give" -> "give <玩家> <gun|ammo|attachment|box> <ID> [数量]";
                        case "ammobox" -> "ammobox（打开手持弹药箱）";
                        case "supplies" -> "supplies（领取手持枪械适配的弹药和配件，仅 OP）";
                        default -> available;
                    });
                    if (sender.hasPermission("tacz.give")) sender.sendMessage("§e/tacz ammobox <玩家> [等级 0–2]");
                }
                case "status" -> {
                    sender.sendMessage("§6TACZ Paper " + plugin.getPluginMeta().getVersion() + " · " + Bukkit.getMinecraftVersion());
                    sender.sendMessage("枪械 " + pack.gunIndexes().size() + "，弹药 " + pack.ammoIndexes().size() + "，配件 " + pack.attachmentIndexes().size() + "，配方 " + pack.recipes().size());
                    if (sender instanceof Player player) sender.sendMessage("客户端桥接：" + (peer.ready(player) ? "已就绪" : "未连接"));
                }
                case "list" -> {
                    require(args.length >= 2 && args.length <= 3, "用法：/tacz list <gun|ammo|attachment|box> [页码]"); List<String> ids = catalog(args[1]);
                    int page = args.length > 2 ? positive(args[2], 10000) : 1, pages = Math.max(1, (ids.size() + 19) / 20); require(page <= pages, "页码超出范围，最大 " + pages);
                    sender.sendMessage("§6" + args[1] + " " + page + "/" + pages); ids.subList((page - 1) * 20, Math.min(page * 20, ids.size())).forEach(sender::sendMessage);
                }
                case "give" -> {
                    require(args.length >= 4 && args.length <= 5, "用法：/tacz give <玩家> <类型> <ID> [数量]");
                    Player target = target(args[1]); String kind = args[2].toLowerCase(Locale.ROOT), id = namespaced(args[3]);
                    require(catalog(kind).contains(id), "未知 " + kind + " ID：" + id); int count = args.length == 5 ? positive(args[4], 4096) : 1;
                    require(!(kind.equals("gun") || kind.equals("box")) || count <= 64, "每次最多给予 64 把枪或弹药箱");
                    if (kind.equals("gun") || kind.equals("box")) for (int i = 0; i < count; i++) items.give(target, items.create(kind, id, 1));
                    else items.give(target, items.create(kind, id, count));
                    peer.send(target, "inventory_changed", new JsonObject()); sender.sendMessage("§a已给予 " + target.getName() + " " + id + " ×" + count);
                }
                case "supplies" -> {
                    require(args.length == 1, "用法：/tacz supplies"); supplies.open(player(sender));
                }
                case "workbench", "refit" -> {
                    require(args.length == 1, "用法：/tacz " + sub); Player player = player(sender); inventories.open(player, sub, sub.equals("workbench") ? -1 : player.getInventory().getHeldItemSlot());
                }
                case "ammobox" -> {
                    if (args.length == 1) { Player player = player(sender); inventories.open(player, "box", player.getInventory().getHeldItemSlot()); }
                    else {
                        require(sender.hasPermission("tacz.give"), "没有权限：tacz.give"); require(args.length <= 3, "用法：/tacz ammobox <玩家> [等级 0–2]");
                        Player target = target(args[1]); int level;
                        try { level = args.length == 3 ? Integer.parseInt(args[2]) : 0; } catch (NumberFormatException e) { throw new IllegalArgumentException("等级必须是 0–2"); }
                        require(level >= 0 && level <= 2, "等级必须是 0–2"); items.give(target, items.create("box", DefaultGunPack.BOX_IDS.get(level), 1)); peer.send(target, "inventory_changed", new JsonObject()); sender.sendMessage("§a已给予弹药箱");
                    }
                }
                case "reload" -> { require(args.length == 1, "用法：/tacz reload"); reload.run(); inventories.clear(); sender.sendMessage("§aTACZ 目录配置已重新加载，正在向客户端同步"); }
            }
        } catch (IllegalArgumentException e) { sender.sendMessage("§c[TACZ] " + e.getMessage()); }
        catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "TACZ command failed", e); sender.sendMessage("§c操作失败，请查看服务端日志。"); }
        return true;
    }
    private Player target(String name) { Player target = Bukkit.getPlayerExact(name); require(target != null, "玩家不在线：" + name); require(peer.ready(target), "目标玩家 TACZ 客户端桥接尚未就绪"); return target; }
    private static Player player(CommandSender sender) { require(sender instanceof Player, "该命令只能由玩家执行"); return (Player) sender; }
    private static boolean canUse(CommandSender sender, String sub) {
        return sub.equals("supplies") ? sender instanceof Player && sender.isOp() : sub.equals("help") || sender.hasPermission("tacz." + sub);
    }
    private List<String> catalog(String kind) { return switch (kind.toLowerCase(Locale.ROOT)) {
        case "gun" -> new ArrayList<>(pack.gunIndexes().keySet()); case "ammo" -> new ArrayList<>(pack.ammoIndexes().keySet());
        case "attachment" -> new ArrayList<>(pack.attachmentIndexes().keySet()); case "box" -> DefaultGunPack.BOX_IDS;
        default -> throw new IllegalArgumentException("类型必须为 gun、ammo、attachment 或 box");
    }; }
    private static String namespaced(String id) { return id.contains(":") ? id : "tacz:" + id; }
    private static int positive(String value, int maximum) { try { int parsed = Integer.parseInt(value); require(parsed > 0 && parsed <= maximum, "数量/页码必须为 1–" + maximum); return parsed; } catch (NumberFormatException e) { throw new IllegalArgumentException("请输入整数"); } }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of(); List<String> choices = new ArrayList<>(); String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) for (String name : COMMANDS) { if (canUse(sender, name)) choices.add(name); }
        else if (canUse(sender, sub)) {
            if (sub.equals("list") && args.length == 2 || sub.equals("give") && args.length == 3) choices.addAll(KINDS);
            else if ((sub.equals("give") || sub.equals("ammobox") && sender.hasPermission("tacz.give")) && args.length == 2) Bukkit.getOnlinePlayers().forEach(player -> choices.add(player.getName()));
            else if (sub.equals("give") && args.length == 4) { try { choices.addAll(catalog(args[2])); } catch (IllegalArgumentException ignored) {} }
            else if (sub.equals("ammobox") && args.length == 3 && sender.hasPermission("tacz.give")) choices.addAll(List.of("0", "1", "2"));
            else if (sub.equals("give") && args.length == 5) choices.addAll(List.of("1", "16", "64"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT); return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
