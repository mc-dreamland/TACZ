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

/** Bukkit plugin messaging transport. All gameplay dispatch runs on the server thread. */
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
            if (sessions.containsKey(player.getUniqueId())) begin(player, new Session());
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
        Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
        long now = System.nanoTime();
        if (now - session.windowStart > 1_000_000_000L) { session.windowStart = now; session.received = 0; }
        if (++session.received > 100) return;
        try {
            BridgeProtocol.Message message = BridgeProtocol.decode(bytes);
            JsonObject data = message.data();
            switch (message.type()) {
                case "hello" -> {
                    if (session.started == 0 || now - session.started > 10_000_000_000L && !session.ready) begin(player, session);
                }
                case "ready" -> {
                    if (session.nonce.equals(data.get("nonce").getAsString())
                            && packHash.equals(data.get("hash").getAsString()) && session.nextChunk == packChunks.size()) {
                        if (!session.ready) {
                            session.ready = true;
                            JsonObject result = new JsonObject();
                            result.addProperty("nonce", session.nonce);
                            send(player, "ready", result);
                            onReady.accept(player);
                        }
                    }
                }
                case "action", "menu_action" -> {
                    if (!session.ready) return;
                    long sequence = data.get("seq").getAsLong();
                    if (sequence <= session.lastSequence || sequence < 0) return;
                    session.lastSequence = sequence;
                    if (message.type().equals("action")) actions.accept(player, data);
                    else menus.accept(player, data);
                }
                default -> { /* Client cannot publish authoritative state or visual events. */ }
            }
        } catch (RuntimeException exception) {
            if (++session.invalid == 3) {
                JsonObject error = new JsonObject();
                error.addProperty("message", "TACZ bridge rejected malformed messages; reconnect with the matching client.");
                send(player, "error", error);
                session.ready = false;
            }
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
        send(player, "welcome", welcome);
    }

    public void tick() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.ready || session.started == 0) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            for (int i = 0; i < 2 && session.nextChunk < packChunks.size(); i++)
                send(player, "pack", packChunks.get(session.nextChunk++));
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
        player.sendPluginMessage(plugin, BridgeProtocol.CHANNEL, BridgeProtocol.encode(type, data));
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
        if (packet != null) observer.sendPluginMessage(plugin, BridgeProtocol.CHANNEL, packet);
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
        private BallisticsBatch ballistics;
        private UUID ballisticWorld;
    }
}
