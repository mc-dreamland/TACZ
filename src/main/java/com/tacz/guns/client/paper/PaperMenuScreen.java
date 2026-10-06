package com.tacz.guns.client.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tacz.guns.client.gui.components.FlatColorButton;
import com.tacz.guns.init.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.tacz.guns.client.paper.GunResolver.*;

/** A server-issued menu: buttons send transactions and never change client inventory. */
public final class PaperMenuScreen extends Screen {
    private final JsonObject menu;
    private final List<JsonObject> entries = new ArrayList<>();
    private int page;
    private int selected = -1;
    private boolean pending;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int rows;

    public PaperMenuScreen(JsonObject menu) {
        super(Component.literal(string(menu, "title", "TACZ")));
        this.menu = menu.deepCopy();
        if (menu.has("entries") && menu.get("entries").isJsonArray()) {
            JsonArray array = menu.getAsJsonArray("entries");
            for (var value : array) if (value.isJsonObject()) entries.add(value.getAsJsonObject());
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(430, width - 24);
        panelHeight = Math.min(278, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 92) / 23);
        clearWidgets();
        int start = page * rows;
        for (int row = 0; row < rows && start + row < entries.size(); row++) {
            int index = start + row;
            JsonObject entry = entries.get(index);
            String name = entryName(entry).getString();
            if (string(entry, "kind", "").equals("installed")) name = "[已安装] " + name;
            Component label = Component.literal(font.plainSubstrByWidth(name, panelWidth / 2 - 35));
            var button = new FlatColorButton(left + 10, top + 32 + row * 23, panelWidth / 2 - 16, 21, label, ignored -> {
                selected = index;
                init();
            });
            button.active = !pending;
            addRenderableWidget(button);
        }
        int bottom = top + panelHeight - 29;
        addRenderableWidget(new FlatColorButton(left + 10, bottom, 25, 19, Component.literal("<"), ignored -> {
            if (page > 0) { page--; init(); }
            else if (integer(menu, "page", 0) > 0) serverPage(integer(menu, "page", 0) - 1);
        })).active = (page > 0 || integer(menu, "page", 0) > 0) && !pending;
        addRenderableWidget(new FlatColorButton(left + 39, bottom, 25, 19, Component.literal(">"), ignored -> {
            if ((page + 1) * rows < entries.size()) { page++; init(); }
            else if (integer(menu, "page", 0) + 1 < integer(menu, "pages", 1)) serverPage(integer(menu, "page", 0) + 1);
        })).active = ((page + 1) * rows < entries.size() || integer(menu, "page", 0) + 1 < integer(menu, "pages", 1)) && !pending;
        String kind = string(menu, "menu", "");
        int actionX = left + panelWidth / 2;
        int actionWidth = panelWidth / 2 - 10;
        if (kind.equals("box")) {
            addRenderableWidget(new FlatColorButton(actionX, bottom - 23, actionWidth / 2 - 2, 20, Component.literal("存入 64"), ignored -> submit("box_store"))).active = selected >= 0 && !pending;
            addRenderableWidget(new FlatColorButton(actionX + actionWidth / 2 + 2, bottom - 23, actionWidth / 2 - 2, 20, Component.literal("取出 64"), ignored -> submit("box_take"))).active = selected >= 0 && !pending;
        } else {
            String entryKind = selected >= 0 ? string(entries.get(selected), "kind", "") : "";
            String action = kind.equals("workbench") ? "制作" : entryKind.equals("installed") ? "卸下配件" : entryKind.equals("unload") ? "卸下弹药" : "安装配件";
            addRenderableWidget(new FlatColorButton(actionX, bottom - 23, actionWidth, 20, Component.literal(action), ignored -> submit(kind.equals("workbench") ? "craft" : "refit"))).active = selected >= 0 && !pending;
            if (kind.equals("refit")) {
                addRenderableWidget(new FlatColorButton(left + 70, bottom, 75, 19, Component.literal("卸下弹药"), ignored -> submit("unload_ammo"))).active = !pending;
            }
        }
        addRenderableWidget(new FlatColorButton(actionX, bottom, actionWidth, 19, Component.translatable("gui.done"), ignored -> onClose()));
    }

    private void serverPage(int requested) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "open_workbench");
        request.addProperty("page", requested);
        PaperClientBridge.sendMenuAction(request);
        pending = true;
        init();
    }

    private void submit(String operation) {
        if (pending || minecraft == null) return;
        JsonObject entry = selected >= 0 && selected < entries.size() ? entries.get(selected) : new JsonObject();
        JsonObject request = new JsonObject();
        request.addProperty("token", string(menu, "token", ""));
        request.addProperty("slot", integer(menu, "slot", -1));
        if (operation.equals("craft")) {
            request.addProperty("op", "craft");
            request.addProperty("recipe", string(entry, "id", ""));
            request.addProperty("count", 1);
        } else if (operation.equals("refit")) {
            if (string(entry, "kind", "").equals("unload")) {
                request.addProperty("op", "unload");
            } else {
                boolean installed = string(entry, "kind", "").equals("installed");
                request.addProperty("op", installed ? "unload" : "refit");
                request.addProperty("type", string(entry, "type", ""));
                if (!installed) request.addProperty("attachmentSlot", integer(entry, "slot", -1));
            }
        } else if (operation.equals("unload_ammo")) {
            request.addProperty("op", "unload");
        } else {
            request.addProperty("op", operation);
            request.addProperty("id", string(entry, "id", ""));
            request.addProperty("count", 64);
        }
        PaperClientBridge.sendMenuAction(request);
        pending = true;
        init();
    }

    public void clearPending() {
        pending = false;
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xec151a20);
        graphics.fill(left, top, left + panelWidth, top + 24, 0xff252e38);
        graphics.fill(left + panelWidth / 2 - 2, top + 31, left + panelWidth / 2 - 1, top + panelHeight - 34, 0xff475361);
        graphics.drawString(font, title, left + 10, top + 8, 0xffdddddd);
        int detailX = left + panelWidth / 2 + 10;
        int textWidth = panelWidth / 2 - 25;
        if (selected >= 0 && selected < entries.size()) {
            JsonObject entry = entries.get(selected);
            graphics.drawString(font, Component.literal(font.plainSubstrByWidth(entryName(entry).getString(), textWidth)), detailX, top + 34, 0xffedc889);
            ItemStack icon = icon(entry);
            if (!icon.isEmpty()) graphics.renderItem(icon, detailX, top + 51);
            if (string(menu, "menu", "").equals("workbench")) {
                graphics.drawString(font, Component.literal("产出 ×" + Math.max(1, integer(entry, "count", 1))), detailX + 24, top + 55, 0xffaab4be);
            }
            int y = top + 77;
            for (var line : font.split(Component.literal(string(entry, "description", "")), textWidth)) {
                if (y > top + panelHeight - 80) break;
                graphics.drawString(font, line, detailX, y, 0xffaab4be);
                y += 11;
            }
        } else {
            graphics.drawString(font, Component.literal(entries.isEmpty() ? "没有可用项目" : "选择左侧项目"), detailX, top + 38, 0xffaab4be);
        }
        if (string(menu, "menu", "").equals("box") && menu.has("state") && menu.get("state").isJsonObject()) {
            JsonObject state = menu.getAsJsonObject("state");
            String capacity = "容量 " + integer(state, "boxAmmo", 0) + " / " + integer(state, "boxCapacity", 0);
            graphics.drawString(font, Component.literal(capacity), detailX, top + panelHeight - 65, 0xffedc889);
        }
        if (pending) graphics.drawString(font, Component.literal("等待服务器确认…"), detailX, top + panelHeight - 78, 0xffedc889);
        String pageText = (page + 1) + "/" + Math.max(1, (entries.size() + rows - 1) / rows);
        if (integer(menu, "pages", 1) > 1) pageText += "  目录 " + (integer(menu, "page", 0) + 1) + "/" + integer(menu, "pages", 1);
        graphics.drawString(font, pageText, left + 10, top + panelHeight - 44, 0xffaab4be);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private ItemStack icon(JsonObject entry) {
        String kind = string(entry, "kind", "");
        String id = string(entry, "itemId", string(menu, "menu", "").equals("workbench")
                ? string(entry, "name", string(entry, "id", "tacz:empty")) : string(entry, "id", "tacz:empty"));
        ItemStack stack = switch (kind) {
            case "gun" -> new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
            case "attachment", "installed" -> new ItemStack(ModItems.ATTACHMENT.get());
            case "ammo" -> new ItemStack(ModItems.AMMO.get());
            case "box", "ammo_box" -> new ItemStack(ModItems.AMMO_BOX.get());
            default -> ItemStack.EMPTY;
        };
        if (!stack.isEmpty()) {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putString(kind.equals("gun") ? "GunId" : kind.equals("attachment") || kind.equals("installed") ? "AttachmentId" : "AmmoId", id);
            if (kind.equals("box") || kind.equals("ammo_box")) {
                tag.putString("AmmoId", "tacz:empty");
                tag.putInt("Level", id.contains("diamond") ? 2 : id.contains("gold") ? 1 : 0);
            }
        }
        return stack;
    }

    private Component entryName(JsonObject entry) {
        ItemStack stack = icon(entry);
        return stack.isEmpty() ? Component.literal(string(entry, "name", string(entry, "id", ""))) : stack.getHoverName();
    }

    @Override public boolean isPauseScreen() { return false; }
}
