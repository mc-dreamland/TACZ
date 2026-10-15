package com.tacz.guns.client.paper;

import com.google.gson.JsonObject;
import com.tacz.guns.client.gui.GunSmithTableScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.UUID;

import static com.tacz.guns.client.paper.GunResolver.*;

/** Routes only the active menu's replies; late transactions never reopen a dismissed screen. */
public final class PaperInventoryMenus {
    private static PaperMenuController current;
    private static PendingOpen pending;

    private record PendingOpen(String kind, String requestId, ClientLevel level, Screen previous,
                               int slot, String instance, long expiresAt) {
        boolean valid(Minecraft mc) {
            if (!PaperClientBridge.active() || mc.player == null || mc.level != level || mc.screen != previous
                    || System.currentTimeMillis() >= expiresAt || !mc.player.isAlive() || mc.player.isSpectator()) return false;
            if (!kind.equals("box")) return true;
            JsonObject item = PaperClientBridge.itemData(mc.player.getMainHandItem());
            return mc.player.getInventory().selected == slot && item != null && instance.equals(string(item, "instance", ""));
        }
    }

    private PaperInventoryMenus() { }

    public static void open(String kind) {
        Minecraft mc = Minecraft.getInstance();
        if (!Set.of("workbench", "box").contains(kind) || !PaperClientBridge.active() || mc.player == null) return;
        JsonObject item = kind.equals("box") ? PaperClientBridge.itemData(mc.player.getMainHandItem()) : null;
        if (kind.equals("box") && (item == null || !string(item, "kind", "").equals("box"))) return;
        if (mc.gameMode != null) mc.gameMode.ensureHasSentCarriedItem();
        pending = new PendingOpen(kind, UUID.randomUUID().toString(), mc.level, mc.screen,
                mc.player.getInventory().selected, item == null ? "" : string(item, "instance", ""), System.currentTimeMillis() + 10_000);
        JsonObject request = new JsonObject();
        request.addProperty("op", "open_" + kind);
        request.addProperty("requestId", pending.requestId());
        PaperClientBridge.sendMenuAction(request);
    }

    public static boolean receive(JsonObject response) {
        Minecraft mc = Minecraft.getInstance();
        String kind = string(response, "menu", "");
        if (!Set.of("workbench", "box").contains(kind)) return false;
        if (bool(response, "refresh", false)) {
            if (current == null || !current.receive(response)) discard(response);
            return false;
        }
        String requestId = string(response, "requestId", "");
        if (!requestId.isEmpty()) {
            if (pending == null || !pending.kind().equals(kind) || !pending.requestId().equals(requestId) || !pending.valid(mc)) {
                discard(response);
                return false;
            }
        }
        pending = null;
        if (!PaperClientBridge.active() || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()) { discard(response); return false; }
        if (current != null) current.close();
        current = new PaperMenuController(response);
        Screen screen = kind.equals("workbench")
                ? GunSmithTableScreen.forPaper(mc.player.getInventory(), Component.literal(string(response, "title", "TACZ")), current)
                : new PaperAmmoBoxScreen(current);
        current.attach(screen);
        mc.setScreen(screen);
        return true;
    }

    public static boolean error(JsonObject error) {
        if (pending != null && pending.requestId().equals(string(error, "requestId", ""))) { pending = null; return true; }
        return current != null && current.error(error);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (pending != null && !pending.valid(mc)) pending = null;
        // The workbench's original pack-information link temporarily opens a confirmation screen.
        if (current != null && !current.ownsCurrentScreen()) {
            current.close();
            current = null;
        } else if (current != null && mc.screen != current.screen()) {
            current.tick();
        }
    }

    public static void reset() {
        pending = null;
        if (current != null) { current.closeScreen(); current = null; }
    }

    public static void cancelPending() { pending = null; }

    private static void discard(JsonObject menu) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "close_" + string(menu, "menu", ""));
        request.addProperty("token", string(menu, "token", ""));
        PaperClientBridge.sendMenuAction(request);
    }
}
