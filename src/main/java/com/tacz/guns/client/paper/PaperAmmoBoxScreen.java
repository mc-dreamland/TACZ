package com.tacz.guns.client.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.gui.components.FlatColorButton;
import com.tacz.guns.init.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.tacz.guns.client.paper.GunResolver.integer;
import static com.tacz.guns.client.paper.GunResolver.string;

/** A view of a server-issued box session. Preview stacks never enter the player's inventory. */
public final class PaperAmmoBoxScreen extends Screen {
    private static final ResourceLocation TABLE = new ResourceLocation(GunMod.MOD_ID, "textures/gui/gun_smith_table.png");
    private static final ResourceLocation SIDE = new ResourceLocation(GunMod.MOD_ID, "textures/gui/gun_smith_table_side.png");
    private static final int PANEL_WIDTH = 344;
    private static final int PANEL_HEIGHT = 230;
    private static final int ROWS = 4;
    private static final int LIGHT = 0xfff3efe0;
    private static final int MUTED = 0xffb9bcb7;
    private static final int ACCENT = 0xff00c8c8;
    private static final int MAX_TRANSFER = 4096;

    private final PaperMenuController controller;
    private final List<AmmoEntry> entries = new ArrayList<>();
    private final List<AmmoRowButton> rowButtons = new ArrayList<>();
    private final List<TooltipArea> tooltips = new ArrayList<>();
    private JsonObject boxState = new JsonObject();
    private String selectedId = "";
    private int page;
    private Quantity quantity = Quantity.STACK;
    private ItemStack boxPreview = ItemStack.EMPTY;
    private boolean lastPending;
    private float scale = 1;
    private float originX;
    private float originY;

    public PaperAmmoBoxScreen(PaperMenuController controller) {
        super(Component.literal("弹药箱"));
        this.controller = controller;
        updateMenu();
    }

    /** Refreshes the authority snapshot while retaining the user's selection and quantity mode. */
    public void updateMenu() {
        boolean initial = entries.isEmpty() && selectedId.isEmpty();
        entries.clear();
        JsonObject data = controller.data();
        boxState = data.has("state") && data.get("state").isJsonObject() ? data.getAsJsonObject("state") : new JsonObject();
        if (data.has("entries") && data.get("entries").isJsonArray()) {
            for (JsonElement value : data.getAsJsonArray("entries")) {
                if (!value.isJsonObject()) continue;
                JsonObject entry = value.getAsJsonObject();
                String id = string(entry, "id", "");
                if (!"ammo".equals(string(entry, "kind", "")) || ResourceLocation.tryParse(id) == null) continue;
                ItemStack icon = new ItemStack(ModItems.AMMO.get());
                icon.getOrCreateTag().putString("AmmoId", id);
                Component name = TimelessAPI.getClientAmmoIndex(ResourceLocation.tryParse(id)).isPresent()
                        ? icon.getHoverName() : Component.translatable(string(entry, "name", id));
                entries.add(new AmmoEntry(id, icon, name, string(entry, "description", "")));
            }
        }
        if (selected() == null) {
            String storedId = string(state(), "boxAmmoId", "");
            AmmoEntry fallback = entries.stream().filter(entry -> entry.id().equals(storedId)).findFirst()
                    .orElse(entries.isEmpty() ? null : entries.get(0));
            selectedId = fallback == null ? "" : fallback.id();
        }
        if (initial && selected() != null) page = entries.indexOf(selected()) / ROWS;
        page = Mth.clamp(page, 0, pageCount() - 1);
        boxPreview = new ItemStack(ModItems.AMMO_BOX.get());
        CompoundTag tag = boxPreview.getOrCreateTag();
        String storedId = string(state(), "boxAmmoId", "");
        tag.putString("AmmoId", storedCount() > 0 && ResourceLocation.tryParse(storedId) != null
                ? storedId : DefaultAssets.EMPTY_AMMO_ID.toString());
        tag.putInt("AmmoCount", storedCount());
        tag.putInt("PaperBoxCapacity", capacity());
        tag.putInt("Level", Mth.clamp(integer(state(), "boxLevel", 0), 0, 2));
        if (minecraft != null) init();
    }

    @Override
    protected void init() {
        scale = Math.min(1, Math.min(Math.max(1, width - 16) / (float) PANEL_WIDTH,
                Math.max(1, height - 16) / (float) PANEL_HEIGHT));
        originX = (width - PANEL_WIDTH * scale) / 2;
        originY = (height - PANEL_HEIGHT * scale) / 2;
        rebuildControls();
    }

    private void rebuildControls() {
        clearWidgets();
        rowButtons.clear();
        tooltips.clear();
        lastPending = controller.pending();
        int start = page * ROWS;
        for (int row = 0; row < ROWS && start + row < entries.size(); row++) {
            AmmoRowButton button = new AmmoRowButton(entries.get(start + row), 49 + row * 23);
            button.active = !lastPending;
            rowButtons.add(addRenderableWidget(button));
        }
        addRenderableWidget(new FlatColorButton(142, 144, 18, 15, Component.literal("<"), button -> {
            page--; rebuildControls();
        })).active = !lastPending && page > 0;
        addRenderableWidget(new FlatColorButton(224, 144, 18, 15, Component.literal(">"), button -> {
            page++; rebuildControls();
        })).active = !lastPending && page + 1 < pageCount();

        addRenderableWidget(new FlatColorButton(142, 165, 94, 18, Component.literal("存入"), button -> transfer(true))).active = canStore();
        tooltips.add(new TooltipArea(142, 165, 94, 18, List.of(Component.literal("将背包中的散装弹药存入此箱"),
                Component.literal("只存入所选弹药，最多 " + requestedCount(true) + " 发"))));
        addRenderableWidget(new FlatColorButton(242, 165, 94, 18, Component.literal("取出"), button -> transfer(false))).active = canTake();
        tooltips.add(new TooltipArea(242, 165, 94, 18, List.of(Component.literal("将箱内弹药取出至背包"),
                Component.literal("最多 " + requestedCount(false) + " 发；背包满时掉落在脚边"))));

        addQuantity(Quantity.ONE, 164, 40, "1 发");
        addQuantity(Quantity.STACK, 208, 66, "整组 " + stackSize());
        addQuantity(Quantity.ALL, 278, 66, "全部");
        addRenderableWidget(new FlatColorButton(0, 193, 134, 20, Component.translatable("gui.done"), button -> onClose()));
    }

    private void addQuantity(Quantity value, int x, int buttonWidth, String label) {
        FlatColorButton button = new FlatColorButton(x, 193, buttonWidth, 20, Component.literal(label), ignored -> {
            quantity = value; rebuildControls();
        });
        if (value == Quantity.ALL) tooltips.add(new TooltipArea(x, 193, buttonWidth, 20,
                List.of(Component.literal("按可用弹药与剩余容量尽量存取"), Component.literal("每次最多 " + MAX_TRANSFER + " 发，可重复操作"))));
        else if (value == Quantity.STACK) tooltips.add(new TooltipArea(x, 193, buttonWidth, 20,
                List.of(Component.literal("按所选弹药的每组数量存取"))));
        button.active = !controller.pending();
        addRenderableWidget(button);
    }

    private void transfer(boolean store) {
        if (!(store ? canStore() : canTake())) return;
        JsonObject args = new JsonObject();
        args.addProperty("id", selectedId);
        args.addProperty("count", requestedCount(store));
        controller.submit(store ? "box_store" : "box_take", args);
        rebuildControls();
    }

    private boolean compatible() {
        return storedCount() == 0 || selectedId.equals(string(state(), "boxAmmoId", ""));
    }

    private boolean canStore() {
        return !controller.pending() && selected() != null && compatible() && storedCount() < capacity();
    }

    private boolean canTake() {
        return !controller.pending() && selected() != null && compatible() && storedCount() > 0;
    }

    private int requestedCount(boolean store) {
        int wanted = switch (quantity) {
            case ONE -> 1;
            case STACK -> stackSize();
            case ALL -> store ? capacity() - storedCount() : storedCount();
        };
        return Mth.clamp(wanted, 1, MAX_TRANSFER);
    }

    private int stackSize() {
        ResourceLocation id = ResourceLocation.tryParse(selectedId);
        // Paper loose-ammo carriers have Minecraft's vanilla 64-item stack ceiling.
        return id == null ? 64 : TimelessAPI.getCommonAmmoIndex(id)
                .map(index -> Mth.clamp(index.getStackSize(), 1, 64)).orElse(64);
    }

    private JsonObject state() { return boxState; }

    private int storedCount() { return Math.max(0, integer(state(), "boxAmmo", 0)); }
    private int capacity() { return Math.max(0, integer(state(), "boxCapacity", 0)); }
    private int pageCount() { return Math.max(1, (entries.size() + ROWS - 1) / ROWS); }
    private AmmoEntry selected() { return entries.stream().filter(entry -> entry.id().equals(selectedId)).findFirst().orElse(null); }

    @Override
    public void tick() {
        controller.tick();
        if (minecraft != null && minecraft.screen == this && lastPending != controller.pending()) rebuildControls();
    }

    @Override
    public void onClose() {
        controller.close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int x = (int) localX(mouseX), y = (int) localY(mouseY);
        graphics.pose().pushPose();
        graphics.pose().translate(originX, originY, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.blit(SIDE, 0, 0, 0, 0, 134, 187);
        graphics.blit(TABLE, 136, 27, 0, 0, 208, 160);
        graphics.fill(136, 0, 344, 22, 0xaf222222);
        graphics.fill(142, 47, 243, 160, 0xff333333);
        graphics.fill(249, 47, 338, 160, 0xff333333);
        graphics.drawString(font, title, 8, 6, LIGHT, false);
        graphics.drawString(font, "弹药管理", 143, 7, LIGHT, false);
        graphics.drawString(font, "选择弹药", 144, 33, 0xff282828, false);
        graphics.drawString(font, "数量", 137, 199, LIGHT, false);
        renderBox(graphics);
        renderSelection(graphics);
        graphics.drawCenteredString(font, (page + 1) + " / " + pageCount(), 192, 148, LIGHT);
        if (entries.isEmpty()) {
            drawLines(graphics, Component.literal("背包中没有可存入的弹药"), 150, 67, 82, 4, MUTED);
        }
        super.render(graphics, x, y, partialTick);
        int quantityX = quantity == Quantity.ONE ? 164 : quantity == Quantity.STACK ? 208 : 278;
        graphics.fill(quantityX, 211, quantityX + (quantity == Quantity.ONE ? 40 : 66), 213, ACCENT);
        renderStatus(graphics);
        graphics.pose().popPose();
        // Tooltips use window coordinates so vanilla edge-clamping still works on a scaled panel.
        if (inside(x, y, 8, 20, 118, 92)) graphics.renderTooltip(font, boxPreview, mouseX, mouseY);
        else if (inside(x, y, 8, 122, 118, 58)) {
            graphics.renderComponentTooltip(font, List.of(Component.literal("箱内 " + storedCount() + " / " + capacity() + " 发"),
                    Component.literal(storedCount() > 0 ? "取空后可存入其它类型弹药" : "空箱可存入任意一种弹药")), mouseX, mouseY);
        } else {
            AmmoEntry hovered = rowButtons.stream().filter(button -> inside(x, y, button.getX(), button.getY(), button.getWidth(), button.getHeight()))
                    .map(button -> button.entry).findFirst().orElse(null);
            if (hovered == null && inside(x, y, 250, 49, 86, 80)) hovered = selected();
            if (hovered != null) graphics.renderTooltip(font, hovered.icon(), mouseX, mouseY);
            else tooltips.stream().filter(area -> inside(x, y, area.x(), area.y(), area.width(), area.height())).findFirst()
                    .ifPresent(area -> graphics.renderComponentTooltip(font, area.lines(), mouseX, mouseY));
        }
    }

    private void renderBox(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(35, 31, 0);
        graphics.pose().scale(4, 4, 1);
        graphics.renderItem(boxPreview, 0, 0);
        graphics.pose().popPose();
        graphics.drawCenteredString(font, font.plainSubstrByWidth(boxPreview.getHoverName().getString(), 118), 67, 103, LIGHT);
        graphics.drawString(font, "容量", 10, 125, 0xff282828, false);
        String capacity = storedCount() + " / " + capacity();
        graphics.drawString(font, capacity, 124 - font.width(capacity), 125, 0xff282828, false);
        graphics.fill(9, 140, 125, 149, 0xff343434);
        int progress = capacity() <= 0 ? 0 : (int) (112L * Math.min(storedCount(), capacity()) / capacity());
        graphics.fill(11, 142, 11 + progress, 147, ACCENT);
        graphics.drawString(font, storedCount() > 0 ? "当前弹药" : "空箱 · 可选择弹药", 10, 156, 0xff343434, false);
        if (storedCount() > 0) {
            String id = string(state(), "boxAmmoId", "");
            Component name = entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
                    .map(AmmoEntry::name).orElse(Component.literal(id));
            graphics.drawString(font, font.plainSubstrByWidth(name.getString(), 114), 10, 169, 0xff282828, false);
        }
    }

    private void renderSelection(GuiGraphics graphics) {
        AmmoEntry entry = selected();
        if (entry == null) {
            drawLines(graphics, Component.literal("放入弹药后重新打开弹药箱"), 255, 65, 78, 5, MUTED);
            return;
        }
        drawLines(graphics, entry.name(), 254, 51, 80, 2, LIGHT);
        graphics.pose().pushPose();
        graphics.pose().translate(274, 78, 0);
        graphics.pose().scale(2.25f, 2.25f, 1);
        graphics.renderItem(entry.icon(), 0, 0);
        graphics.pose().popPose();
        graphics.drawString(font, "每组 " + stackSize() + " 发", 254, 119, MUTED, false);
        drawLines(graphics, Component.literal(entry.description()), 254, 133, 80, 2, MUTED);
    }

    private void renderStatus(GuiGraphics graphics) {
        String text;
        int color = MUTED;
        if (controller.pending()) { text = "等待服务器确认…"; color = ACCENT; }
        else if (selected() == null) text = "背包中的散装弹药会显示在右侧列表";
        else if (!compatible()) { text = "此箱已有另一种弹药，请先选择原弹药并取空"; color = 0xffffc16b; }
        else if (storedCount() >= capacity() && capacity() > 0) text = "弹药箱已满，可选择数量后取出";
        else if (storedCount() == 0) text = "空箱：选择弹药与数量后存入";
        else text = "选择数量后存入或取出弹药";
        graphics.drawCenteredString(font, text, PANEL_WIDTH / 2, 220, color);
    }

    private void drawLines(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int lines, int color) {
        int line = 0;
        for (var part : font.split(text, maxWidth)) {
            if (line >= lines) break;
            graphics.drawString(font, part, x, y + line++ * 11, color, false);
        }
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private double localX(double value) { return (value - originX) / scale; }
    private double localY(double value) { return (value - originY) / scale; }
    @Override public boolean mouseClicked(double x, double y, int button) { return super.mouseClicked(localX(x), localY(y), button); }
    @Override public boolean mouseReleased(double x, double y, int button) { return super.mouseReleased(localX(x), localY(y), button); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(localX(x), localY(y), button, dx / scale, dy / scale);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (!controller.pending() && delta != 0 && inside(localX(x), localY(y), 142, 47, 101, 113)) {
            int next = Mth.clamp(page + (delta < 0 ? 1 : -1), 0, pageCount() - 1);
            if (next != page) { page = next; rebuildControls(); }
            return true;
        }
        return super.mouseScrolled(localX(x), localY(y), delta);
    }

    private enum Quantity { ONE, STACK, ALL }
    private record AmmoEntry(String id, ItemStack icon, Component name, String description) { }
    private record TooltipArea(int x, int y, int width, int height, List<Component> lines) { }

    private final class AmmoRowButton extends FlatColorButton {
        private final AmmoEntry entry;

        private AmmoRowButton(AmmoEntry entry, int y) {
            super(142, y, 101, 21, Component.empty(), button -> {
                selectedId = entry.id();
                rebuildControls();
            });
            this.entry = entry;
            // The visible text is custom-positioned beside the native item icon.
            setMessage(entry.name());
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Component message = getMessage();
            setMessage(Component.empty());
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            setMessage(message);
            graphics.renderItem(entry.icon(), getX() + 3, getY() + 2);
            graphics.drawString(font, font.plainSubstrByWidth(message.getString(), 75), getX() + 23, getY() + 7,
                    active ? LIGHT : MUTED, false);
            if (entry.id().equals(selectedId)) graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(), ACCENT);
        }
    }
}
