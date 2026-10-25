package com.tacz.guns.paper.network;

import com.ayou.protocolsupportcustompacket.api.NeteaseCustomPacketAPI;
import com.ayou.protocolsupportcustompacket.data.PacketJSON;
import com.ayou.protocolsupportcustompacket.event.NeteaseReceiveCustomPacketEvent;
import com.tacz.guns.bridge.BridgeProtocol;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** 使用与 MoreGui 相同的网易自定义事件 API，将原 TACZ 协议封装在 Base64 载荷中。 */
public final class NeteaseBridge implements Listener {
    public static final String C2S = "TaczBridgeC2S";
    public static final String S2C = "TaczBridgeS2C";
    private static final PacketJSON PACKET = new PacketJSON("TACZ", "TACZServerSystem");
    private final JavaPlugin plugin;
    private final PaperNetwork network;
    // 网易事件可能异步到达；按玩家分别限制入口、回包日志频率，避免高频状态刷屏。
    private final Map<UUID, Long> receiveLogs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sendLogs = new ConcurrentHashMap<>();

    public NeteaseBridge(JavaPlugin plugin, PaperNetwork network) {
        this.plugin = plugin;
        this.network = network;
        network.neteaseSender(this::send);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onMessage(NeteaseReceiveCustomPacketEvent event) {
        if (C2S.equals(event.getEventName()) || "TACZ".equals(event.getModName())) {
            if (shouldLog(receiveLogs, event.getPlayer_uuid()))
                plugin.getLogger().info("[TACZ NetEase] [事件入口] 玩家 UUID=" + event.getPlayer_uuid()
                        + "，namespace=" + event.getModName() + "，system=" + event.getSystemName()
                        + "，event=" + event.getEventName() + "，异步事件=" + event.isAsynchronous());
        }
        // 三个字段均匹配才允许进入 TACZ；平台身份本身不能代替协议握手。
        if (!C2S.equals(event.getEventName()) || !"TACZ".equals(event.getModName())
                || !"TACZClientSystem".equals(event.getSystemName())) {
            if (C2S.equals(event.getEventName()) || "TACZ".equals(event.getModName()))
                plugin.getLogger().warning("[TACZ NetEase] [路由拒绝] 期望 TACZ/TACZClientSystem/" + C2S
                        + "，实际=" + event.getModName() + "/" + event.getSystemName() + "/" + event.getEventName()
                        + "，玩家 UUID=" + event.getPlayer_uuid());
            return;
        }
        UUID uuid = event.getPlayer_uuid();
        if (uuid == null || event.getData() == null) {
            plugin.getLogger().warning("[TACZ NetEase] [载荷拒绝] 缺少玩家 UUID 或事件数据，UUID=" + uuid);
            return;
        }
        Object encoded = event.getData().get("payload");
        if (!(encoded instanceof String text) || text.length() > 40_000) {
            plugin.getLogger().warning("[TACZ NetEase] [载荷拒绝] payload 必须为不超过 40000 字符的 Base64 字符串，玩家 UUID=" + uuid);
            return;
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(text); }
        catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("[TACZ NetEase] [载荷拒绝] Base64 解码失败，玩家 UUID=" + uuid);
            return;
        }
        if (bytes.length == 0 || bytes.length > BridgeProtocol.MAX_PACKET_BYTES) {
            plugin.getLogger().warning("[TACZ NetEase] [载荷拒绝] 解码后字节数=" + bytes.length
                    + "，允许范围=1.." + BridgeProtocol.MAX_PACKET_BYTES + "，玩家 UUID=" + uuid);
            return;
        }
        // 玩家身份只取转发插件提供的 UUID，不信任消息体；查找玩家和处理协议均在主线程执行。
        Runnable receive = () -> {
            if (!plugin.isEnabled()) return;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) network.receiveNetease(player, bytes);
            else plugin.getLogger().warning("[TACZ NetEase] [玩家查找失败] UUID 未对应在线 Bukkit 玩家，UUID=" + uuid);
        };
        if (Bukkit.isPrimaryThread()) receive.run();
        else Bukkit.getScheduler().runTask(plugin, receive);
    }

    private void send(Player player, byte[] bytes) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("payload", Base64.getEncoder().encodeToString(bytes));
        try {
            NeteaseCustomPacketAPI.sendPacket(player, PACKET.getData(S2C, data));
            if (shouldLog(sendLogs, player.getUniqueId()))
                plugin.getLogger().info("[TACZ NetEase] [回包] 自定义发包 API 调用已返回，玩家=" + player.getName()
                        + "，UUID=" + player.getUniqueId() + "，路由=TACZ/TACZServerSystem/" + S2C
                        + "，协议字节=" + bytes.length + "；客户端是否收到需查看客户端日志");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[TACZ NetEase] [回包失败] 自定义发包 API 异常，玩家="
                    + player.getName() + "，UUID=" + player.getUniqueId() + "，协议字节=" + bytes.length, exception);
            throw exception;
        }
    }

    private boolean shouldLog(Map<UUID, Long> times, UUID uuid) {
        if (uuid == null) return true;
        long now = System.nanoTime();
        Long previous = times.putIfAbsent(uuid, now);
        return previous == null || now - previous >= 5_000_000_000L && times.replace(uuid, previous, now);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        receiveLogs.remove(event.getPlayer().getUniqueId());
        sendLogs.remove(event.getPlayer().getUniqueId());
    }
}
