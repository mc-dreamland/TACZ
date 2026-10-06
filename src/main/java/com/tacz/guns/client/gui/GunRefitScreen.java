package com.tacz.guns.client.gui;

import com.google.gson.JsonObject;
import com.tacz.guns.api.item.ItemBehavior;

import com.tacz.guns.mixin.client.ScreenAccessor;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.animation.screen.RefitTransform;
import com.tacz.guns.client.gui.components.FlatColorButton;
import com.tacz.guns.client.gui.components.refit.*;
import com.tacz.guns.client.paper.GunResolver;
import com.tacz.guns.client.paper.PaperClientBridge;
import com.tacz.guns.client.paper.PaperClientGameplay;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ClientMessageLaserColor;
import com.tacz.guns.network.message.ClientMessageRefitGun;
import com.tacz.guns.network.message.ClientMessageUnloadAttachment;
import com.tacz.guns.sound.SoundManager;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.util.LaserColorUtil;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

import static com.tacz.guns.client.paper.GunResolver.integer;
import static com.tacz.guns.client.paper.GunResolver.string;
public class GunRefitScreen extends Screen {
    public static final ResourceLocation SLOT_TEXTURE = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "textures/gui/refit_slot.png");
    public static final ResourceLocation TURN_PAGE_TEXTURE = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "textures/gui/refit_turn_page.png");
    public static final ResourceLocation UNLOAD_TEXTURE = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "textures/gui/refit_unload.png");
    public static final ResourceLocation ICONS_TEXTURE = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "textures/gui/refit_slot_icons.png");

    public static final int ICON_UV_SIZE = 32;
    public static final int SLOT_SIZE = 18;
    private static final int INVENTORY_ATTACHMENT_SLOT_COUNT = 8;
    private static boolean HIDE_GUN_PROPERTY_DIAGRAMS = true;

    private int currentPage = 0;
    private JsonObject paperMenu;
    private ClientLevel paperLevel;
    private String paperInstance = "";
    private int paperSlot = -1;
    private boolean paperPending;
    private boolean paperTokenValid = true;
    private boolean paperClosed;
    private long paperPendingSince;
    private String paperPendingOperation = "";
    private ItemStack paperSoundItem = ItemStack.EMPTY;
    private String paperSoundName = "";
    private final EnumMap<AttachmentType, Integer> paperColorDraft = new EnumMap<>(AttachmentType.class);
    private static final long PAPER_REQUEST_TIMEOUT = 5_000;

    public GunRefitScreen() {
        super(Component.literal("Gun Refit Screen"));
        RefitTransform.init();
    }

    public GunRefitScreen(JsonObject menu) {
        this();
        paperMenu = menu.deepCopy();
        paperLevel = Minecraft.getInstance().level;
        paperSlot = integer(menu, "slot", -1);
        paperInstance = string(paperState(menu), "instance", "");
        applyPaperState(menu);
    }

    public boolean isPaperRefit() { return paperMenu != null; }

    public boolean acceptsPaperMenu(JsonObject menu) {
        if (!isPaperRefit() || paperClosed || integer(menu, "slot", -1) != paperSlot
                || !paperInstance.equals(string(paperState(menu), "instance", "")) || !paperHeldMatches()) return false;
        return !GunResolver.bool(menu, "refresh", false)
                || string(paperMenu, "token", "").equals(string(menu, "previousToken", ""));
    }

    public boolean acceptsPaperError(JsonObject error) {
        if (!isPaperRefit() || paperClosed || !paperPending) return false;
        String token = string(error, "token", "");
        String operation = string(error, "op", "");
        return !token.isEmpty() && token.equals(string(paperMenu, "token", ""))
                && (operation.isEmpty() || operation.equals(paperPendingOperation));
    }

    /** Refresh the existing view without resetting the selected attachment camera. */
    public void updatePaperMenu(JsonObject menu) {
        if (!isPaperRefit() || paperClosed) return;
        if (!acceptsPaperMenu(menu)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (paperPending && player != null && !paperSoundItem.isEmpty() && !paperSoundName.isEmpty()) {
            SoundPlayManager.playerRefitSound(paperSoundItem, player, paperSoundName);
        }
        paperMenu = menu.deepCopy();
        paperPending = false;
        paperTokenValid = true;
        paperPendingOperation = "";
        paperSoundItem = ItemStack.EMPTY;
        paperSoundName = "";
        paperColorDraft.clear();
        applyPaperState(menu);
        init();
    }

    /** Error/timeout recovery refreshes a possibly consumed token once, without a retry loop. */
    public void clearPaperPending() {
        if (!isPaperRefit() || paperClosed) return;
        boolean wasRefresh = paperPendingOperation.equals("open_refit");
        paperPending = false;
        paperTokenValid = false;
        paperPendingOperation = "";
        paperSoundItem = ItemStack.EMPTY;
        paperSoundName = "";
        paperColorDraft.clear();
        if (!paperHeldMatches()) { closePaperQuietly(); return; }
        if (!wasRefresh) refreshPaperMenu();
        else init();
    }

    /** Called only while assembling a temporary render stack, never an inventory stack. */
    public void applyPaperPreview(String instance, CompoundTag renderTag) {
        if (!isPaperRefit() || paperClosed || !paperInstance.equals(instance)) return;
        for (var entry : paperColorDraft.entrySet()) {
            if (entry.getKey() == AttachmentType.NONE) renderTag.putInt("LaserColor", entry.getValue());
            else {
                String key = "Attachment" + entry.getKey().name();
                ItemStack attachment = com.tacz.guns.util.ItemNbtUtils.loadItemStack(renderTag.getCompoundOrEmpty(key));
                if (!attachment.isEmpty()) {
                    com.tacz.guns.util.ItemNbtUtils.updateTag(attachment, tag -> tag.putInt("LaserColor", entry.getValue()));
                    renderTag.put(key, com.tacz.guns.util.ItemNbtUtils.saveItemStack(attachment));
                }
            }
        }
    }

    public static int getSlotTextureXOffset(ItemStack gunItem, AttachmentType attachmentType) {
        IGun iGun = IGun.getIGunOrNull(gunItem);
        if (iGun == null) {
            return -1;
        }
        if (!iGun.allowAttachmentType(gunItem, attachmentType)) {
            return ICON_UV_SIZE * 6;
        }
        switch (attachmentType) {
            case GRIP -> {
                return 0;
            }
            case LASER -> {
                return ICON_UV_SIZE;
            }
            case MUZZLE -> {
                return ICON_UV_SIZE * 2;
            }
            case SCOPE -> {
                return ICON_UV_SIZE * 3;
            }
            case STOCK -> {
                return ICON_UV_SIZE * 4;
            }
            case EXTENDED_MAG -> {
                return ICON_UV_SIZE * 5;
            }
        }
        return -1;
    }

    public static int getSlotsTextureWidth() {
        return ICON_UV_SIZE * 7;
    }

    @Override
    public void init() {
        this.clearWidgets();
        // 添加配件槽位
        this.addAttachmentTypeButtons();
        // 添加可选配件列表
        this.addInventoryAttachmentButtons();
        // 添加属性图隐藏按钮
        if (HIDE_GUN_PROPERTY_DIAGRAMS) {
            this.addRenderableWidget(new FlatColorButton(11, 11, 288, 16,
                    Component.translatable("gui.tacz.gun_refit.property_diagrams.show"), b -> switchHideButton()));
        } else {
            this.addRenderableWidget(new FlatColorButton(14, 14, 12, 12, Component.literal("S"), b -> {
                if (isPaperRefit() && paperPending) return;
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null || player.isSpectator()) return;
                if (IGun.mainHandHoldGun(player)) {
                    IClientPlayerGunOperator.fromLocalPlayer(player).fireSelect();
                    this.init();
                }
            }).setTooltips(Component.translatable("gui.tacz.gun_refit.property_diagrams.fire_mode.switch")));
            int buttonYOffset = GunPropertyDiagrams.getHidePropertyButtonYOffset();
            this.addRenderableWidget(new FlatColorButton(11, buttonYOffset, 288, 12,
                    Component.translatable("gui.tacz.gun_refit.property_diagrams.hide"), b -> switchHideButton()));
        }
        if (isPaperRefit()) addRenderableWidget(new FlatColorButton(11, height - 26, 80, 16,
                Component.literal("卸下弹药"), ignored -> submitPaper("unload", new JsonObject(), ItemStack.EMPTY, "")));
        if (isPaperRefit() && paperPending) this.children().forEach(child -> {
            if (child instanceof AbstractWidget widget) widget.active = false;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float pPartialTick) {
        super.render(graphics, mouseX, mouseY, pPartialTick);

        if (!HIDE_GUN_PROPERTY_DIAGRAMS) {
            GunPropertyDiagrams.draw(graphics, font, 11, 11);
        }

        // 26.2 tooltip API: widgets push tooltip contents through their existing consumer hooks.
        ((ScreenAccessor) this).tacz$getRenderables().stream().filter(w -> w instanceof IComponentTooltip).forEach(w -> {
            IComponentTooltip tooltipWidget = (IComponentTooltip) w;
            tooltipWidget.renderTooltip(lines -> graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY));
        });
        ((ScreenAccessor) this).tacz$getRenderables().stream().filter(w -> w instanceof IStackTooltip).forEach(w -> {
            IStackTooltip tooltipWidget = (IStackTooltip) w;
            tooltipWidget.renderTooltip(stack -> {
                if (!stack.isEmpty()) {
                    graphics.setTooltipForNextFrame(font, Screen.getTooltipFromItem(Minecraft.getInstance(), stack), stack.getTooltipImage(), mouseX, mouseY);
                }
            });
        });
        if (isPaperRefit() && paperPending) graphics.drawCenteredString(font, Component.literal("等待服务器确认…"), width / 2, height - 16, 0xEEEEEE);
    }

    /**
     * 改装界面<b>不要</b>全屏模糊 —— 与上游 1.21.1 行为一致。
     *
     * <p>上游 {@code GunRefitScreen} 里有一个空实现的
     * <pre>
     * &#64;Override protected void renderBlurredBackground(float partialTick) { }
     * </pre>
     * 移植时漏掉了，于是走 vanilla 默认实现，改装界面糊上一层背景模糊。
     *
     * <p>26.2 的对应方法改名为 {@code renderBlurredBackground(GuiGraphics)}，
     * 调用链（字节码确认）：
     * <pre>
     * Screen#extractBackground
     *   -> Screen#extractBlurredBackground
     *        -> if (options.getMenuBackgroundBlurriness() != 0)
     *               graphics.blurBeforeThisStratum();
     * </pre>
     * 覆写为空即可精确复刻上游「不模糊」的效果。
     *
     * <p>这里必须留空而不是不覆写：玩家一边看着枪模型一边装配件，
     * 背景模糊会把枪身也一起糊掉（模糊是整个 stratum 之前的全屏后处理），
     * 严重影响观察配件外观 —— 这正是上游特意关掉它的原因。
     */
    @Override
    protected void renderBlurredBackground(GuiGraphics graphics) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void addInventoryAttachmentButtons() {
        LocalPlayer player = this.minecraft.player;
        if (RefitTransform.getCurrentTransformType() == AttachmentType.NONE || player == null) {
            return;
        }
        if (isPaperRefit()) { addPaperInventoryAttachmentButtons(); return; }
        int startX = this.width - 30;
        int startY = 50;
        int pageStart = currentPage * INVENTORY_ATTACHMENT_SLOT_COUNT;
        int count = 0;
        int currentY = startY;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack inventoryItem = inventory.getItem(i);
            IAttachment attachment = IAttachment.getIAttachmentOrNull(inventoryItem);
            IGun iGun = IGun.getIGunOrNull(player.getMainHandItem());
            if (attachment != null && iGun != null && attachment.getType(inventoryItem) == RefitTransform.getCurrentTransformType()) {
                if (!iGun.allowAttachment(player.getMainHandItem(), inventoryItem)) {
                    continue;
                }
                count++;
                if (count <= pageStart) {
                    continue;
                }
                if (count > pageStart + INVENTORY_ATTACHMENT_SLOT_COUNT) {
                    continue;
                }
                InventoryAttachmentSlot button = new InventoryAttachmentSlot(startX, currentY, i, inventory, b -> {
                    int slotIndex = ((InventoryAttachmentSlot) b).getSlotIndex();
                    SoundPlayManager.playerRefitSound(inventory.getItem(slotIndex), player, SoundManager.INSTALL_SOUND);
                    ClientMessageRefitGun message = new ClientMessageRefitGun(slotIndex, inventory.getSelectedSlot(), RefitTransform.getCurrentTransformType());
                    ClientPacketDistributor.sendToServer(message);
                });
                this.addRenderableWidget(button);
                currentY = currentY + SLOT_SIZE;
            }
        }
        int totalPage = (count - 1) / INVENTORY_ATTACHMENT_SLOT_COUNT;
        RefitTurnPageButton turnPageButtonUp = new RefitTurnPageButton(startX, startY - 10, true, b -> {
            if (currentPage > 0) {
                currentPage--;
                init();
            }
        });
        RefitTurnPageButton turnPageButtonDown = new RefitTurnPageButton(startX, startY + SLOT_SIZE * INVENTORY_ATTACHMENT_SLOT_COUNT + 2, false, b -> {
            if (currentPage < totalPage) {
                currentPage++;
                init();
            }
        });
        if (currentPage < totalPage) {
            this.addRenderableWidget(turnPageButtonDown);
        }
        if (currentPage > 0) {
            this.addRenderableWidget(turnPageButtonUp);
        }
    }

    private void addPaperInventoryAttachmentButtons() {
        AttachmentType type = RefitTransform.getCurrentTransformType();
        List<JsonObject> candidates = new ArrayList<>();
        if (paperMenu.has("entries") && paperMenu.get("entries").isJsonArray()) {
            for (var value : paperMenu.getAsJsonArray("entries")) {
                if (!value.isJsonObject()) continue;
                JsonObject entry = value.getAsJsonObject();
                int slot = integer(entry, "slot", -1);
                if (string(entry, "kind", "").equals("attachment")
                        && type.name().equalsIgnoreCase(string(entry, "type", ""))
                        && (slot >= 0 && slot < 36 || slot == 40)
                        && ResourceLocation.tryParse(string(entry, "id", "")) != null) candidates.add(entry);
            }
        }
        int totalPage = Math.max(0, (candidates.size() - 1) / INVENTORY_ATTACHMENT_SLOT_COUNT);
        currentPage = Math.min(currentPage, totalPage);
        int startX = width - 30;
        int startY = 50;
        int pageStart = currentPage * INVENTORY_ATTACHMENT_SLOT_COUNT;
        for (int i = pageStart; i < Math.min(candidates.size(), pageStart + INVENTORY_ATTACHMENT_SLOT_COUNT); i++) {
            JsonObject entry = candidates.get(i);
            int slot = integer(entry, "slot", -1);
            ItemStack icon = new ItemStack(ModItems.ATTACHMENT.get());
            ((com.tacz.guns.api.item.IAttachment) icon.getItem()).setAttachmentId(icon, ResourceLocation.parse(string(entry, "id", "")));
            InventoryAttachmentSlot button = new InventoryAttachmentSlot(startX, startY + (i - pageStart) * SLOT_SIZE, slot, icon, ignored -> {
                JsonObject request = new JsonObject();
                request.addProperty("attachmentSlot", slot);
                request.addProperty("type", type.name().toLowerCase(Locale.ROOT));
                submitPaper("refit", request, icon, SoundManager.INSTALL_SOUND);
            });
            addRenderableWidget(button);
        }
        if (currentPage > 0) addRenderableWidget(new RefitTurnPageButton(startX, startY - 10, true, ignored -> { currentPage--; init(); }));
        if (currentPage < totalPage) addRenderableWidget(new RefitTurnPageButton(startX, startY + SLOT_SIZE * INVENTORY_ATTACHMENT_SLOT_COUNT + 2, false,
                ignored -> { currentPage++; init(); }));
    }

    private HSVSliderGroup laserSliders(Inventory inventory, AttachmentType type) {
        if (!isPaperRefit()) return new HSVSliderGroup(width - 140, height - 64, 120, 16, inventory, inventory.getSelectedSlot(), type);
        ItemStack gun = inventory.getItem(paperSlot);
        IGun iGun = IGun.getIGunOrNull(gun);
        ItemStack target = type == AttachmentType.NONE || iGun == null ? gun : iGun.getAttachment(gun, type);
        int color = paperColorDraft.getOrDefault(type, LaserColorUtil.getLaserColor(target));
        return new HSVSliderGroup(width - 140, height - 64, 120, 16, color, rgb -> {
            if (!paperPending && !paperClosed) paperColorDraft.put(type, rgb & 0xFFFFFF);
        });
    }

    private void submitPaper(String operation, JsonObject arguments, ItemStack soundItem, String soundName) {
        if (paperPending || paperClosed) return;
        if (!paperHeldMatches()) { closePaperQuietly(); return; }
        if (!paperTokenValid) { refreshPaperMenu(); return; }
        JsonObject request = paperRequest(operation);
        for (var entry : arguments.entrySet()) request.add(entry.getKey(), entry.getValue());
        appendPaperColors(request);
        paperSoundItem = soundItem.copy();
        paperSoundName = soundName;
        startPaperPending(operation);
        PaperClientBridge.sendMenuAction(request);
        init();
    }

    private JsonObject paperRequest(String operation) {
        JsonObject request = new JsonObject();
        request.addProperty("op", operation);
        request.addProperty("token", string(paperMenu, "token", ""));
        request.addProperty("slot", paperSlot);
        request.addProperty("instance", paperInstance);
        return request;
    }

    private void appendPaperColors(JsonObject request) {
        JsonObject attachmentColors = new JsonObject();
        for (var entry : paperColorDraft.entrySet()) {
            if (entry.getKey() == AttachmentType.NONE) request.addProperty("laserColor", entry.getValue());
            else attachmentColors.addProperty(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        if (attachmentColors.size() > 0) request.add("attachmentColors", attachmentColors);
    }

    private void startPaperPending(String operation) {
        paperPending = true;
        paperPendingOperation = operation;
        paperPendingSince = System.currentTimeMillis();
    }

    private void refreshPaperMenu() {
        if (!paperHeldMatches() || paperClosed) { closePaperQuietly(); return; }
        startPaperPending("open_refit");
        PaperClientGameplay.reopenRefitMenu(this);
        init();
    }

    private boolean paperHeldMatches() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!PaperClientBridge.active() || player == null || !player.isAlive() || player.isSpectator()
                || Minecraft.getInstance().level != paperLevel || paperSlot < 0 || paperSlot > 8
                || player.getInventory().getSelectedSlot() != paperSlot || paperInstance.isEmpty()) return false;
        JsonObject item = PaperClientBridge.itemData(player.getMainHandItem());
        return item != null && string(item, "kind", "").equals("gun") && paperInstance.equals(string(item, "instance", ""));
    }

    private static JsonObject paperState(JsonObject menu) {
        return menu.has("state") && menu.get("state").isJsonObject() ? menu.getAsJsonObject("state") : new JsonObject();
    }

    private void applyPaperState(JsonObject menu) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        JsonObject state = paperState(menu).deepCopy();
        state.addProperty("entity", player.getId());
        GunResolver.onState(state);
        AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());
    }

    private void closePaperQuietly() {
        paperClosed = true;
        paperColorDraft.clear();
        super.onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (!isPaperRefit() || paperClosed) return;
        if (!paperHeldMatches()) { closePaperQuietly(); return; }
        long timeout = paperPendingOperation.equals("open_refit") ? 10_000 : PAPER_REQUEST_TIMEOUT;
        if (paperPending && System.currentTimeMillis() - paperPendingSince >= timeout) {
            if (paperPendingOperation.equals("open_refit")) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) player.displayClientMessage(Component.literal("[TACZ] 改装菜单刷新超时，请重新按 Z 打开。"), false);
                closePaperQuietly();
            } else clearPaperPending();
        }
    }

    @Override
    public void removed() {
        if (isPaperRefit()) { paperClosed = true; paperColorDraft.clear(); }
        super.removed();
    }

    private void addAttachmentTypeButtons() {
        LocalPlayer player = this.minecraft.player;
        if (player == null) {
            return;
        }
        IGun iGun = IGun.getIGunOrNull(player.getMainHandItem());
        if (iGun == null) {
            return;
        }
        int startX = this.width - 30;
        int startY = 10;
        Inventory inventory = player.getInventory();
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) {
                if (RefitTransform.getCurrentTransformType() == AttachmentType.NONE) {
                    TimelessAPI.getGunDisplay(player.getMainHandItem())
                            .map(GunDisplayInstance::getLaserConfig)
                            .ifPresent(laserConfig -> {
                                if (laserConfig.canEdit()) {
                                    // 添加镭射颜色选择器
                                    HSVSliderGroup hsvSliderGroup = laserSliders(inventory, AttachmentType.NONE);
                                    this.addRenderableWidget(hsvSliderGroup.getHueSlider());
                                    this.addRenderableWidget(hsvSliderGroup.getSaturationSlider());
                                }
                            });
                }
                continue;
            }
            GunAttachmentSlot button = new GunAttachmentSlot(startX, startY, type, inventory.getSelectedSlot(), inventory, b -> {
                AttachmentType buttonType = ((GunAttachmentSlot) b).getType();
                // 如果这个槽位不允许安装配件，则默认退回概览，不选中槽位。
                if (!((GunAttachmentSlot) b).isAllow()) {
                    if (RefitTransform.changeRefitScreenView(AttachmentType.NONE)) {
                        if (isPaperRefit()) currentPage = 0;
                        this.init();
                    }
                    return;
                }
                // 点击的是当前选中的槽位，则退回概览
                if (RefitTransform.getCurrentTransformType() == buttonType && buttonType != AttachmentType.NONE) {
                    if (RefitTransform.changeRefitScreenView(AttachmentType.NONE)) {
                        if (isPaperRefit()) currentPage = 0;
                        this.init();
                    }
                    return;
                }
                // 切换选中的槽位。
                if (RefitTransform.changeRefitScreenView(buttonType)) {
                    if (isPaperRefit()) currentPage = 0;
                    this.init();
                }
            });
            if (RefitTransform.getCurrentTransformType() == type) {
                button.setSelected(true);
                // 添加拆卸配件按钮
                RefitUnloadButton unloadButton = new RefitUnloadButton(startX + 5, startY + SLOT_SIZE + 2, b -> {
                    ItemStack attachmentItem = button.getAttachmentItem();
                    if (!attachmentItem.isEmpty()) {
                        if (isPaperRefit()) {
                            JsonObject request = new JsonObject();
                            request.addProperty("type", type.name().toLowerCase(Locale.ROOT));
                            submitPaper("unload", request, attachmentItem, SoundManager.UNINSTALL_SOUND);
                            return;
                        }
                        int freeSlot = inventory.getFreeSlot();
                        if (freeSlot != -1) {
                            SoundPlayManager.playerRefitSound(attachmentItem, player, SoundManager.UNINSTALL_SOUND);
                            ClientMessageUnloadAttachment message = new ClientMessageUnloadAttachment(inventory.getSelectedSlot(), RefitTransform.getCurrentTransformType());
                            ClientPacketDistributor.sendToServer(message);
                        } else {
                            player.displayClientMessage(Component.translatable("gui.tacz.gun_refit.unload.no_space"), false);
                        }
                    }
                });
                if (!button.getAttachmentItem().isEmpty()) {
                    this.addRenderableWidget(unloadButton);

                    if (ItemBehavior.of(button.getAttachmentItem()) instanceof IAttachment iAttachment) {
                        TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(button.getAttachmentItem()))
                                .map(ClientAttachmentIndex::getLaserConfig)
                                .ifPresent(laserConfig -> {
                                    if (laserConfig.canEdit()) {
                                        // 添加镭射颜色选择器
                                        HSVSliderGroup hsvSliderGroup = laserSliders(inventory, type);
                                        this.addRenderableWidget(hsvSliderGroup.getHueSlider());
                                        this.addRenderableWidget(hsvSliderGroup.getSaturationSlider());
                                    }
                                });
                    }
                }
            }
            this.addRenderableWidget(button);
            startX = startX - SLOT_SIZE;
        }
    }

    @Override
    public void onClose() {
        if (isPaperRefit()) {
            if (paperClosed) return;
            if (!paperHeldMatches()) { closePaperQuietly(); return; }
            if (paperPending) { closePaperQuietly(); return; }
            if (paperTokenValid) {
                JsonObject request = paperRequest(paperColorDraft.isEmpty() ? "close_refit" : "laser_color");
                appendPaperColors(request);
                request.addProperty("close", true);
                PaperClientBridge.sendMenuAction(request);
            }
            closePaperQuietly();
            return;
        }
        // 关闭界面时，一次性上传所有的染色数据
        LocalPlayer player = this.minecraft.player;
        if (player != null) {
            ItemStack gun = player.getMainHandItem();
            if (ItemBehavior.of(player.getMainHandItem()) instanceof IGun) {
                ClientMessageLaserColor message = new ClientMessageLaserColor(gun, player.getInventory().getSelectedSlot());
                ClientPacketDistributor.sendToServer(message);
            }
        }
        super.onClose();
    }

    public static void refresh() {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof GunRefitScreen screen) {
            screen.init();
        }
    }

    private void switchHideButton() {
        HIDE_GUN_PROPERTY_DIAGRAMS = !HIDE_GUN_PROPERTY_DIAGRAMS;
        this.init();
    }
}
