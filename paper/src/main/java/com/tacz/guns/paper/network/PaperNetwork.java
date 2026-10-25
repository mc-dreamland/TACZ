package com.tacz.guns.paper.network;

import com.google.gson.JsonObject;
import com.tacz.guns.bridge.BridgeProtocol;
import com.tacz.guns.bridge.BallisticsBatch;
import com.tacz.guns.bridge.PackTransfer;
import com.tacz.guns.paper.pack.DefaultGunPack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Java 插件消息与网易自定义事件共用握手和动作处理，所有游戏逻辑均在服务端主线程执行。 */
public final class PaperNetwork implements BridgePeer, PluginMessageListener, Listener {
    private final JavaPlugin plugin;
    private final DefaultGunPack pack;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private List<JsonObject> packChunks = List.of();
    private String packHash = "";
    private BiConsumer<Player, JsonObject> actions = (player, data) -> {};
    private BiConsumer<Player, JsonObject> menus = (player, data) -> {};
    private Consumer<Player> onReady = player -> {};
    private Consumer<Player> onBegin = player -> {};
    private Consumer<Player> synchronize = player -> {};
    private BiConsumer<Player, byte[]> neteaseSender;
    private BiConsumer<Player, JsonObject> neteaseMenus;

    public void neteaseSender(BiConsumer<Player, byte[]> sender) {
        this.neteaseSender = sender;
    }

    public void neteaseMenus(BiConsumer<Player, JsonObject> opener) { neteaseMenus = opener; }

    public void handleNeteaseMenu(Player player, JsonObject request) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("TACZ menu off the server thread");
        if (ready(player)) menus.accept(player, request);
    }

    /** 网易入口只接受自定义包插件提供的玩家身份，不能使用客户端消息体指定玩家。 */
    public void receiveNetease(Player player, byte[] bytes) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("TACZ packet off the server thread");
        if (neteaseSender == null || !player.isOnline()) {
            plugin.getLogger().warning("[TACZ NetEase] [入口拒绝] 玩家=" + player.getName()
                    + "，UUID=" + player.getUniqueId() + "，发送器已注册=" + (neteaseSender != null)
                    + "，玩家在线=" + player.isOnline());
            return;
        }
        receive(player, bytes, true);
    }

    public PaperNetwork(JavaPlugin plugin, DefaultGunPack pack) throws IOException {
        this.plugin = plugin;
        this.pack = pack;
        rebuildPack();
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void handlers(BiConsumer<Player, JsonObject> actions, BiConsumer<Player, JsonObject> menus,
                         Consumer<Player> onReady, Consumer<Player> synchronize, Consumer<Player> onBegin) {
        this.actions = actions;
        this.menus = menus;
        this.onReady = onReady;
        this.synchronize = synchronize;
        this.onBegin = onBegin;
    }

    public void reload() throws IOException {
        rebuildPack();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Session previous = sessions.get(player.getUniqueId());
            if (previous != null) {
                // 重载保留传输通道，但必须重新校验新目录并完成 ready 确认。
                Session next = new Session();
                next.netease = previous.netease;
                next.clientToken = previous.clientToken;
                begin(player, next);
            }
        }
    }

    private void rebuildPack() throws IOException {
        JsonObject bundle = new JsonObject();
        bundle.add("data", pack.networkData());
        packChunks = PackTransfer.encode(bundle);
        packHash = packChunks.getFirst().get("hash").getAsString();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!BridgeProtocol.CHANNEL.equals(channel) || !player.isOnline()) return;
        receive(player, bytes, false);
    }

    private void receive(Player player, byte[] bytes, boolean netease) {
        Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
        // 已开始的会话绑定收包通道，防止另一个通道覆盖既有会话；冲突必须可诊断。
        if (session.started != 0 && session.netease != netease) {
            traceDiagnostic(player, session, "channel", "[通道拒绝] 已有通道=" + (session.netease ? "网易" : "Java")
                    + "，本次通道=" + (netease ? "网易" : "Java") + "，已有会话就绪=" + session.ready);
            return;
        }
        long now = System.nanoTime();
        if (now - session.windowStart > 1_000_000_000L) { session.windowStart = now; session.received = 0; }
        if (++session.received > 100) {
            if (netease) traceDiagnostic(player, session, "rate", "[限流] 每秒接收超过 100 个协议包，本次包已忽略");
            return;
        }
        try {
            BridgeProtocol.Message message = BridgeProtocol.decode(bytes);
            JsonObject data = message.data();
            if (netease && (message.type().equals("hello") || message.type().equals("ready")))
                plugin.getLogger().info("[TACZ NetEase] [收包] 玩家=" + player.getName() + "，UUID=" + player.getUniqueId()
                        + "，C2S=" + message.type() + "，当前就绪=" + session.ready
                        + "，目录已提交=" + session.nextChunk + "/" + packChunks.size());
            switch (message.type()) {
                case "hello" -> {
                    session.netease = netease;
                    String clientToken = netease ? data.get("clientToken").getAsString() : "";
                    if (clientToken.length() > 80 || netease && clientToken.isEmpty()) {
                        if (netease) traceDiagnostic(player, session, "token", "[握手拒绝] clientToken 长度必须为 1..80，实际=" + clientToken.length());
                        return;
                    }
                    boolean newClient = netease && !clientToken.equals(session.clientToken);
                    session.clientToken = clientToken;
                    if (newClient || session.started == 0 || now - session.started > 10_000_000_000L && !session.ready) begin(player, session);
                    else if (netease) traceDiagnostic(player, session, "hello", "[握手等待] hello 沿用当前会话，已就绪="
                            + session.ready + "，目录已提交=" + session.nextChunk + "/" + packChunks.size());
                }
                case "ready" -> {
                    // ready 不能由平台识别直接放行：会话、nonce、目录哈希和发送进度必须全部匹配。
                    if (netease) plugin.getLogger().info("[TACZ NetEase] [就绪校验] 玩家=" + player.getName()
                            + "，UUID=" + player.getUniqueId() + "，会话已启动=" + (session.started != 0)
                            + "，通道匹配=" + (session.netease == netease)
                            + "，nonce匹配=" + session.nonce.equals(data.get("nonce").getAsString())
                            + "，hash匹配=" + packHash.equals(data.get("hash").getAsString())
                            + "，目录已提交=" + session.nextChunk + "/" + packChunks.size());
                    if (session.started != 0 && session.netease == netease
                            && session.nonce.equals(data.get("nonce").getAsString())
                            && packHash.equals(data.get("hash").getAsString()) && session.nextChunk == packChunks.size()) {
                        if (!session.ready) {
                            session.ready = true;
                            JsonObject result = new JsonObject();
                            result.addProperty("nonce", session.nonce);
                            send(player, "ready", result);
                            onReady.accept(player);
                            plugin.getLogger().info("TACZ bridge ready: " + player.getName() + (session.netease ? " (NetEase)" : " (Java)"));
                            if (netease) plugin.getLogger().info("[TACZ NetEase] [桥接就绪] 玩家=" + player.getName()
                                    + "，UUID=" + player.getUniqueId() + "，握手校验通过，允许发枪及处理枪械请求");
                        } else if (session.netease) {
                            JsonObject result = new JsonObject();
                            result.addProperty("nonce", session.nonce);
                            send(player, "ready", result);
                        }
                    } else if (netease) plugin.getLogger().warning("[TACZ NetEase] [就绪拒绝] 玩家=" + player.getName()
                            + "，请检查上一条就绪校验中的会话、nonce、hash 和目录进度");
                }
                case "action", "menu_action" -> {
                    if (!session.ready) {
                        traceAction(player, session, "[动作拒绝] 会话尚未就绪");
                        return;
                    }
                    long sequence = data.get("seq").getAsLong();
                    if (sequence <= session.lastSequence || sequence < 0) {
                        traceAction(player, session, "[动作拒绝] 序号过期或非法，seq=" + sequence + "，上次=" + session.lastSequence);
                        return;
                    }
                    traceAction(player, session, "[动作分发] type=" + message.type() + "，op=" + data.get("op") + "，seq=" + sequence);
                    session.lastSequence = sequence;
                    if (message.type().equals("action")) actions.accept(player, data);
                    else menus.accept(player, data);
                }
                default -> { /* Client cannot publish authoritative state or visual events. */ }
            }
        } catch (RuntimeException exception) {
            if (netease && session.invalid < 3) plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[TACZ NetEase] [处理失败] 协议解码或处理异常，玩家=" + player.getName()
                            + "，UUID=" + player.getUniqueId() + "，累计异常=" + (session.invalid + 1), exception);
            if (++session.invalid == 3) {
                JsonObject error = new JsonObject();
                error.addProperty("message", "TACZ bridge rejected malformed messages; reconnect with the matching client.");
                send(player, "error", error);
                session.ready = false;
            }
        }
    }

    private void traceAction(Player player, Session session, String message) {
        long now = System.nanoTime();
        if (session.netease && (session.lastActionLog == 0 || now - session.lastActionLog >= 5_000_000_000L)) {
            session.lastActionLog = now;
            plugin.getLogger().info("[TACZ NetEase] 玩家=" + player.getName() + "，UUID=" + player.getUniqueId() + "，" + message);
        }
    }

    // 各类拒绝原因独立限频，避免动作高频日志掩盖通道或握手诊断。
    private void traceDiagnostic(Player player, Session session, String key, String message) {
        long now = System.nanoTime();
        Long previous = session.diagnosticLogs.get(key);
        if (previous == null || now - previous >= 5_000_000_000L) {
            session.diagnosticLogs.put(key, now);
            plugin.getLogger().info("[TACZ NetEase] 玩家=" + player.getName() + "，UUID=" + player.getUniqueId() + "，" + message);
        }
    }

    private void begin(Player player, Session session) {
        onBegin.accept(player);
        session.started = System.nanoTime();
        session.nonce = UUID.randomUUID().toString();
        session.nextChunk = 0;
        session.lastSequence = -1;
        session.invalid = 0;
        session.ready = false;
        session.ballistics = null;
        session.ballisticWorld = null;
        sessions.put(player.getUniqueId(), session);
        JsonObject welcome = new JsonObject();
        welcome.addProperty("nonce", session.nonce);
        welcome.addProperty("hash", packHash);
        welcome.addProperty("server", "Paper 1.21.11");
        if (session.netease) welcome.addProperty("playerUuid", player.getUniqueId().toString());
        send(player, "welcome", welcome);
        if (session.netease) plugin.getLogger().info("[TACZ NetEase] [握手] welcome 发包调用已返回，玩家=" + player.getName()
                + "，UUID=" + player.getUniqueId() + "，目录分片=" + packChunks.size() + "，SHA-256=" + packHash);
    }

    public void tick() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.ready || session.started == 0) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            int before = session.nextChunk;
            // 每 tick 最多提交两片；计数表示服务端已调用发送接口，客户端仍须自行校验完整目录。
            for (int i = 0; i < 2 && session.nextChunk < packChunks.size(); i++)
                send(player, "pack", packChunks.get(session.nextChunk++));
            if (session.netease && before < packChunks.size() && session.nextChunk == packChunks.size())
                plugin.getLogger().info("[TACZ NetEase] [目录] 全部分片发包调用已返回，玩家=" + player.getName()
                        + "，UUID=" + player.getUniqueId() + "，分片=" + session.nextChunk + "，等待客户端 ready");
        }
    }

    @Override public boolean ready(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.ready;
    }

    @Override public void send(Player player, String type, JsonObject data) {
        if (type.equals("inventory_changed")) {
            synchronize.accept(player);
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (type.equals("menu") && session != null && session.netease && neteaseMenus != null) {
            neteaseMenus.accept(player, data);
            return;
        }
        sendPacket(player, BridgeProtocol.encode(type, data));
    }

    private void sendPacket(Player player, byte[] bytes) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.netease) neteaseSender.accept(player, bytes);
        else player.sendPluginMessage(plugin, BridgeProtocol.CHANNEL, bytes);
    }

    @Override public void broadcast(Player source, String type, JsonObject data) {
        for (Player observer : source.getWorld().getPlayers()) {
            if (ready(observer) && observer.canSee(source)
                    && observer.getLocation().distanceSquared(source.getLocation()) <= 128 * 128)
                send(observer, type, data);
        }
    }

    @Override public void ballistics(Player observer, JsonObject record) {
        Session session = sessions.get(observer.getUniqueId());
        if (session == null || !session.ready || !observer.isOnline()) return;
        UUID world = observer.getWorld().getUID();
        if (session.ballistics == null || !world.equals(session.ballisticWorld)) {
            session.ballisticWorld = world;
            String dimension = ((CraftWorld) observer.getWorld()).getHandle().dimension().identifier().toString();
            session.ballistics = new BallisticsBatch(dimension);
        }
        if (!session.ballistics.offer(record)) {
            flushBallistics(observer, session);
            session.ballistics.offer(record);
        }
    }

    /** Called after gameplay ticks so a spawn and its same-tick impact retain their order in one batch. */
    public void flushBallistics() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            Player observer = Bukkit.getPlayer(entry.getKey());
            if (session.ballistics == null) continue;
            if (!session.ready || observer == null || !observer.isOnline() || !observer.getWorld().getUID().equals(session.ballisticWorld)) {
                session.ballistics = null;
                session.ballisticWorld = null;
                continue;
            }
            flushBallistics(observer, session);
        }
    }

    private void flushBallistics(Player observer, Session session) {
        byte[] packet = session.ballistics.drain();
        if (packet != null) sendPacket(observer, packet);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }

    public void close() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ready(player)) send(player, "goodbye", new JsonObject());
        }
        sessions.clear();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
    }

    private static final class Session {
        private long windowStart = System.nanoTime();
        private int received;
        private int invalid;
        private long started;
        private String nonce = "";
        private int nextChunk;
        private long lastSequence = -1;
        private boolean ready;
        private boolean netease;
        private String clientToken = "";
        private long lastActionLog;
        private final Map<String, Long> diagnosticLogs = new HashMap<>();
        private BallisticsBatch ballistics;
        private UUID ballisticWorld;
    }
}
