package com.tacz.guns.client.paper;

import com.google.gson.JsonObject;
import com.tacz.guns.bridge.InventoryMenuSession;
import com.tacz.guns.client.gui.GunSmithTableScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import static com.tacz.guns.client.paper.GunResolver.*;

/** Owns a Paper transaction session; screens only render views and submit intent. */
public final class PaperMenuController implements GunSmithTableScreen.PaperActions {
    private final InventoryMenuSession session;
    private final ClientLevel level;
    private final Vec3 origin;
    private Screen screen;
    private Screen linkScreen;
    private int inventoryRefreshTicks;

    public PaperMenuController(JsonObject menu) {
        Minecraft mc = Minecraft.getInstance();
        session = new InventoryMenuSession(menu, System.currentTimeMillis());
        level = mc.level;
        origin = mc.player.position();
    }

    public void attach(Screen screen) { this.screen = screen; }
    public Screen screen() { return screen; }
    public boolean ownsCurrentScreen() {
        Screen visible = Minecraft.getInstance().screen;
        return visible == screen || linkScreen != null && visible == linkScreen;
    }
    public JsonObject data() { return session.data(); }
    public boolean pending() { return session.pending() || session.closed(); }

    public void craft(ResourceLocation recipe) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("recipe", recipe.toString());
        arguments.addProperty("count", 1);
        submit("craft", arguments);
    }

    public void submit(String operation, JsonObject arguments) {
        if (!valid()) { closeScreen(); return; }
        JsonObject request = session.begin(operation, arguments, System.currentTimeMillis());
        if (request != null) {
            PaperClientBridge.sendMenuAction(request);
            updateScreen();
        }
    }

    public boolean receive(JsonObject response) {
        if (!ownsCurrentScreen() || !valid() || !session.accept(response, System.currentTimeMillis())) return false;
        updateScreen();
        return true;
    }

    public boolean error(JsonObject error) {
        boolean refreshing = session.operation().startsWith("refresh_");
        if (!ownsCurrentScreen() || !session.acceptError(error)) return false;
        if (refreshing) closeScreen();
        else updateScreen();
        return true;
    }

    public void tick() {
        if (!valid()) { closeScreen(); return; }
        long now = System.currentTimeMillis();
        if (session.timedOut(now)) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal("[TACZ] 操作响应超时，请重新打开界面确认结果。"), false);
            closeScreen();
            return;
        }
        if (session.refreshDue(now)) submit("refresh_" + session.kind(), new JsonObject());
        // Inventory packets may arrive after the transaction response. Keep material counts current.
        if (++inventoryRefreshTicks >= 10) {
            inventoryRefreshTicks = 0;
            if (screen instanceof GunSmithTableScreen workbench) workbench.refreshPaperIngredients();
        }
    }

    public void close() {
        JsonObject request = session.close();
        if (request != null) PaperClientBridge.sendMenuAction(request);
    }

    public void closeScreen() {
        close();
        Minecraft mc = Minecraft.getInstance();
        if (ownsCurrentScreen()) mc.setScreen(null);
        linkScreen = null;
    }

    public void openLink(String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != screen || !valid()) return;
        linkScreen = new ConfirmLinkScreen(yes -> {
            if (!valid()) { closeScreen(); return; }
            if (yes) Util.getPlatform().openUri(url);
            linkScreen = null;
            mc.setScreen(screen);
        }, url, false);
        mc.setScreen(linkScreen);
    }

    private boolean valid() {
        Minecraft mc = Minecraft.getInstance();
        if (session.closed() || !PaperClientBridge.active() || mc.player == null || mc.level != level
                || !mc.player.isAlive() || mc.player.isSpectator() || mc.player.position().distanceToSqr(origin) > 64) return false;
        if (session.kind().equals("box")) {
            JsonObject data = session.data();
            int slot = integer(data, "slot", -1);
            if (slot < 0 || slot > 35 && slot != 40) return false;
            JsonObject actual = PaperClientBridge.itemData(mc.player.getInventory().getItem(slot));
            JsonObject expected = data.getAsJsonObject("state");
            return actual != null && string(actual, "kind", "").equals("box")
                    && string(actual, "instance", "").equals(string(expected, "instance", ""));
        }
        return true;
    }

    private void updateScreen() {
        if (screen instanceof GunSmithTableScreen workbench) workbench.updateIngredientCount();
        else if (screen instanceof PaperAmmoBoxScreen box) box.updateMenu();
    }
}
