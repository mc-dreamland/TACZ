package com.tacz.guns.client.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.GunMod;
import com.tacz.guns.bridge.BridgeProtocol;
import com.tacz.guns.bridge.PackTransfer;
import com.tacz.guns.client.resource.ClientIndexManager;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.network.DataType;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class PaperClientBridge {
    private static final ResourceLocation CHANNEL = new ResourceLocation(BridgeProtocol.CHANNEL);
    private static final PackTransfer.Receiver TRANSFER = new PackTransfer.Receiver();
    private static final Map<Integer, JsonObject> STATES = new HashMap<>();
    private static final Map<String, JsonObject> MAPPINGS = new HashMap<>();
    private static JsonArray mappingList = new JsonArray();
    private static boolean active;
    private static boolean connected;
    private static String nonce = "";
    private static String expectedHash = "";
    private static long sequence;
    private static int ticks;

    private PaperClientBridge() {}
    public static boolean active() { return active; }
    public static JsonArray mappings() { return mappingList; }
    @Nullable public static JsonObject state(int entityId) { return STATES.get(entityId); }

    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
        connected = event.getConnection() != null && !event.getConnection().isMemoryConnection();
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { reset(); }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !connected || active) return;
        // Retry after login because a Bukkit player may not yet have registered our channel.
        if (++ticks <= 600 && ticks % 40 == 1 && nonce.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) return;
            minecraft.getConnection().send(new ServerboundCustomPayloadPacket(new ResourceLocation("minecraft", "register"),
                    new FriendlyByteBuf(Unpooled.wrappedBuffer(BridgeProtocol.CHANNEL.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
            JsonObject hello = new JsonObject();
            hello.addProperty("client", "tacz-forge-1.20.1");
            send("hello", hello);
        }
    }

    public static void receive(byte[] payload) {
        try {
            BridgeProtocol.Message message = BridgeProtocol.decode(payload);
            JsonObject data = message.data();
            switch (message.type()) {
                case "welcome" -> {
                    active = false;
                    sequence = 0;
                    TRANSFER.reset();
                    STATES.clear();
                    MAPPINGS.clear();
                    PaperClientGameplay.reset();
                    nonce = data.get("nonce").getAsString();
                    expectedHash = data.get("hash").getAsString();
                }
                case "pack" -> {
                    if (nonce.isEmpty() || !expectedHash.equals(data.get("hash").getAsString())) return;
                    JsonObject bundle = TRANSFER.accept(data);
                    if (bundle != null) {
                        installPack(bundle);
                        JsonObject ready = new JsonObject();
                        ready.addProperty("nonce", nonce);
                        ready.addProperty("hash", expectedHash);
                        send("ready", ready);
                    }
                }
                case "ready" -> {
                    if (!nonce.isEmpty() && nonce.equals(data.get("nonce").getAsString())) active = true;
                }
                case "state" -> {
                    if (!active) return;
                    int entity = data.get("entity").getAsInt();
                    STATES.put(entity, data.deepCopy());
                    if (STATES.size() > 1024) STATES.keySet().removeIf(id -> Minecraft.getInstance().level == null
                            || Minecraft.getInstance().level.getEntity(id) == null);
                    PaperClientGameplay.onState(data);
                }
                case "event" -> { if (active) PaperClientGameplay.onEvent(data); }
                case "ballistics" -> { if (active) PaperClientBallistics.receive(data); }
                case "menu" -> { if (active) PaperClientGameplay.onMenu(data); }
                case "error" -> {
                    PaperClientGameplay.onError(data);
                    if (Minecraft.getInstance().player != null)
                        Minecraft.getInstance().player.displayClientMessage(Component.literal("[TACZ] " + data.get("message").getAsString()), false);
                }
                case "goodbye" -> reset();
                default -> { }
            }
        } catch (Exception exception) {
            GunMod.LOGGER.warn("Invalid Paper bridge data; disabling bridge for this connection", exception);
            reset();
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.displayClientMessage(Component.literal("[TACZ] Paper bridge failed. Check client/server versions and reconnect."), false);
        }
    }

    private static void installPack(JsonObject bundle) {
        Map<DataType, Map<ResourceLocation, String>> cache = new EnumMap<>(DataType.class);
        for (Map.Entry<String, JsonElement> group : bundle.getAsJsonObject("data").entrySet()) {
            Map<ResourceLocation, String> entries = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : group.getValue().getAsJsonObject().entrySet())
                entries.put(new ResourceLocation(entry.getKey()), entry.getValue().getAsString());
            cache.put(DataType.valueOf(group.getKey()), entries);
        }
        mappingList = bundle.getAsJsonArray("mappings").deepCopy();
        for (JsonElement entry : mappingList) {
            JsonObject mapping = entry.getAsJsonObject();
            MAPPINGS.put(mapping.get("material").getAsString() + "/" + mapping.get("cmd").getAsInt(), mapping);
        }
        CommonAssetsManager.clearInstance();
        CommonNetworkCache.INSTANCE.fromNetwork(cache);
        ClientIndexManager.reload();
    }

    @Nullable public static JsonObject itemData(ItemStack stack) {
        if (!active || stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("CustomModelData", Tag.TAG_INT)) return null;
        JsonObject mapping = MAPPINGS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "/" + tag.getInt("CustomModelData"));
        if (mapping == null) return null;
        CompoundTag pdc = tag.getCompound("PublicBukkitValues");
        if (!pdc.contains(BridgeProtocol.ITEM_KEY, Tag.TAG_STRING)) return null;
        String json = pdc.getString(BridgeProtocol.ITEM_KEY);
        if (json.length() > 16_384) return null;
        try {
            JsonObject data = JsonParser.parseString(json).getAsJsonObject();
            if (!mapping.get("kind").equals(data.get("kind")) || !mapping.get("id").equals(data.get("id"))) return null;
            return data;
        } catch (RuntimeException ignored) { return null; }
    }

    public static long sendAction(String op, JsonObject arguments) {
        if (!active) return -1;
        JsonObject data = arguments.deepCopy();
        data.addProperty("op", op);
        data.addProperty("seq", ++sequence);
        if (!data.has("instance") && Minecraft.getInstance().player != null) {
            JsonObject item = itemData(Minecraft.getInstance().player.getMainHandItem());
            if (item != null && item.has("instance")) data.add("instance", item.get("instance"));
        }
        send("action", data);
        return sequence;
    }

    public static void sendMenuAction(JsonObject request) {
        if (!active) return;
        JsonObject data = request.deepCopy();
        data.addProperty("seq", ++sequence);
        send("menu_action", data);
    }

    private static void send(String type, JsonObject data) {
        if (Minecraft.getInstance().getConnection() != null)
            Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(CHANNEL,
                    new FriendlyByteBuf(Unpooled.wrappedBuffer(BridgeProtocol.encode(type, data)))));
    }

    public static void reset() {
        active = false;
        connected = false;
        nonce = "";
        expectedHash = "";
        ticks = 0;
        sequence = 0;
        mappingList = new JsonArray();
        MAPPINGS.clear();
        STATES.clear();
        TRANSFER.reset();
        PaperClientGameplay.reset();
    }
}
