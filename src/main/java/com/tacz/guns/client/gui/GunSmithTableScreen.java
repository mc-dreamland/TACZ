package com.tacz.guns.client.gui;

import com.tacz.guns.api.item.ItemBehavior;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.gui.components.FlatColorButton;
import com.tacz.guns.client.gui.components.GunPackList;
import com.tacz.guns.client.gui.components.smith.ResultButton;
import com.tacz.guns.client.gui.components.smith.TypeButton;
import com.tacz.guns.client.paper.PaperClientBridge;
import com.tacz.guns.client.paper.PaperWorkbenchRecipes;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.PackInfo;
import com.tacz.guns.config.client.RenderConfig;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.crafting.GunSmithTableIngredient;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.init.ModRecipe;
import com.tacz.guns.inventory.GunSmithTableMenu;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ClientMessageCraft;
import com.tacz.guns.resource.filter.RecipeFilter;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import com.tacz.guns.util.RenderDistance;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.*;

public class GunSmithTableScreen extends AbstractContainerScreen<GunSmithTableMenu> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(GunMod.MOD_ID, "textures/gui/gun_smith_table.png");
    private static final ResourceLocation SIDE = new ResourceLocation(GunMod.MOD_ID, "textures/gui/gun_smith_table_side.png");

    private final LinkedHashMap<ResourceLocation, TabConfig> recipeKeys = Maps.newLinkedHashMap();
    private final Map<ResourceLocation, List<ResourceLocation>> recipes = Maps.newLinkedHashMap();
    private final @Nullable PaperActions paperActions;
    private final Map<ResourceLocation, GunSmithTableRecipe> paperRecipes;
    private @Nullable ImageButton craftButton;

    /** Transport/lifecycle callbacks; this screen never mutates a Paper inventory or menu. */
    public interface PaperActions {
        boolean pending();
        void craft(ResourceLocation recipeId);
        void close();
        void tick();
        void openLink(String url);
    }

    private int typePage;
    private ResourceLocation selectedType = null;
    private List<ResourceLocation> selectedRecipeList = new ArrayList<>();

    private int indexPage;
    private int ingredientPage;
    private @Nullable GunSmithTableRecipe selectedRecipe;
    private @Nullable Int2IntArrayMap playerIngredientCount;

    private int scale = 70;
    private boolean filterEnabled = false;
    private GunPackList filterList;
    private boolean autoByHandFilterApplied = false;

    public GunSmithTableScreen(GunSmithTableMenu menu, Inventory inventory, Component title) {
        this(menu, inventory, title, null);
    }

    public static GunSmithTableScreen forPaper(Inventory inventory, Component title, PaperActions actions) {
        return new GunSmithTableScreen(new GunSmithTableMenu(0, inventory, DefaultAssets.DEFAULT_BLOCK_ID), inventory, title, Objects.requireNonNull(actions));
    }

    private GunSmithTableScreen(GunSmithTableMenu menu, Inventory inventory, Component title, @Nullable PaperActions actions) {
        super(menu, inventory, title);
        this.paperActions = actions;
        this.paperRecipes = actions == null ? Map.of() : PaperWorkbenchRecipes.load(PaperClientBridge.workbenchRecipes());
        this.imageWidth = 344;
        this.imageHeight = 186;
        this.classifyRecipes();
        this.typePage = 0;
        this.indexPage = 0;
        this.selectedRecipe = this.getSelectedRecipe(selectedRecipeList != null && !this.selectedRecipeList.isEmpty() ? this.selectedRecipeList.get(0) : null);
        this.getPlayerIngredientCount(this.selectedRecipe);
    }

    public boolean isPaperWorkbench() { return paperActions != null; }

    public static void drawModCenteredString(GuiGraphics gui, Font font, Component component, int pX, int pY, int color) {
        FormattedCharSequence text = component.getVisualOrderText();
        gui.drawString(font, text, pX - font.width(text) / 2, pY, color, false);
    }

    private void classifyRecipes() {
        this.recipes.clear();
        this.recipeKeys.clear();
        ResourceLocation blockId = menu.getBlockId();
        if (blockId == null) {
            return;
        }
        Map<ResourceLocation, List<ResourceLocation>> recipes = Maps.newLinkedHashMap();
        Map<ResourceLocation, TabConfig> recipeKeys = Maps.newLinkedHashMap();

        List<TabConfig> tabs = isPaperWorkbench() ? TabConfig.DEFAULT_TABS : TimelessAPI.getCommonBlockIndex(blockId)
                .map(blockIndex -> DefaultAssets.DEFAULT_BLOCK_ID.equals(blockId) && !SyncConfig.ENABLE_TABLE_FILTER.get()
                        ? TabConfig.DEFAULT_TABS : blockIndex.getData().getTabs()).orElse(List.of());
        for (TabConfig tab : tabs) {
            recipes.put(tab.id(), Lists.newArrayList());
            recipeKeys.put(tab.id(), tab);
        }

        List<Pair<ResourceLocation, ResourceLocation>> recipeIds = Lists.newArrayList();

        if (Minecraft.getInstance().level != null) {
            Collection<GunSmithTableRecipe> recipeList = isPaperWorkbench() ? paperRecipes.values()
                    : Minecraft.getInstance().level.getRecipeManager().getAllRecipesFor(ModRecipe.GUN_SMITH_TABLE_CRAFTING.get());
            Set<String> namespaces = filterList != null ? filterList.namespaceList() : null;
            for (GunSmithTableRecipe recipe : recipeList) {
                ResourceLocation id = recipe.getId();
                if (namespaces != null && !namespaces.contains(id.getNamespace())) {
                    continue;
                }
                if (!isSuitableForMainHand(recipe)) {
                    continue;
                }
                if (!isNameMatch(recipe)) {
                    continue;
                }

                ResourceLocation groupName = recipe.getResult().getGroup();
                if (isPaperWorkbench() && !recipeKeys.containsKey(groupName)) groupName = TabConfig.TAB_MISC;
                if (recipeKeys.containsKey(groupName)) {
                    recipeIds.add(Pair.of(groupName, id));
                }
            }
        }

        List<Pair<ResourceLocation, ResourceLocation>> filteredRecipes = isPaperWorkbench() ? recipeIds : TimelessAPI.getCommonBlockIndex(menu.getBlockId()).map(blockIndex -> {
            if (menu.getBlockId().equals(DefaultAssets.DEFAULT_BLOCK_ID) && !SyncConfig.ENABLE_TABLE_FILTER.get()) {
                return null;
            }
            RecipeFilter filter = blockIndex.getFilter();
            if (filter != null) {
                return filter.filter(recipeIds, Pair::value);
            }
            return null;
        }).orElse(recipeIds);
        filteredRecipes.forEach(entry -> {
            ResourceLocation groupName = entry.key();
            if (recipeKeys.containsKey(groupName)) {
                recipes.computeIfAbsent(groupName, g -> Lists.newArrayList()).add(entry.value());
            }
        });

        for (var entry : recipes.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                this.recipes.put(entry.getKey(), entry.getValue());
                this.recipeKeys.put(entry.getKey(), recipeKeys.get(entry.getKey()));
            }
        }

        if (!this.recipeKeys.containsKey(selectedType)) {
            selectedType = null;
            selectedRecipeList = null;
            indexPage = 0;
        }

        if (!this.recipeKeys.keySet().isEmpty()) {
            if (selectedType == null) {
                selectedType = this.recipeKeys.keySet().iterator().next();
            }
        }

        if (selectedType != null) {
            selectedRecipeList = this.recipes.get(selectedType);
        }
        indexPage = Math.max(0, Math.min(indexPage, selectedRecipeList == null ? 0 : (selectedRecipeList.size() - 1) / 6));
        typePage = Math.max(0, Math.min(typePage, (this.recipes.size() - 1) / 7));
    }

    private boolean isNameMatch(GunSmithTableRecipe recipe) {
        if (filterList != null && StringUtils.isNotBlank(filterList.getSearchText())) {
            String searchText = filterList.getSearchText().toLowerCase();
            Component name = recipe.getResult().getResult().getHoverName();
            return name.getString().toLowerCase().contains(searchText);
        }
        return true;
    }

    private boolean isSuitableForMainHand(GunSmithTableRecipe recipe) {
        if (filterList != null && filterList.isByHandSelected()) {
            ItemStack result = recipe.getResult().getResult();

            Minecraft minecraft = Minecraft.getInstance();
            ItemStack stack = minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
            if (ItemBehavior.of(stack) instanceof IGun igun) {
                if (ItemBehavior.of(result) instanceof IAmmo iAmmo) {
                    return iAmmo.isAmmoOfGun(stack, result);
                }
                if (ItemBehavior.of(result) instanceof IAttachment) {
                    return igun.allowAttachment(stack, result);
                }
                return false;
            }
            if (ItemBehavior.of(stack) instanceof IAttachment) {
                if (ItemBehavior.of(result) instanceof IGun iGun) {
                    return iGun.allowAttachment(result, stack);
                }
                return false;
            }
            if (ItemBehavior.of(stack) instanceof IAmmo iAmmo) {
                if (ItemBehavior.of(result) instanceof IGun) {
                    return iAmmo.isAmmoOfGun(result, stack);
                }
                return false;
            }
        }
        return true;
    }

    private boolean shouldFilterByMainHand() {
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        Object item = ItemBehavior.of(stack);
        return item instanceof IGun || item instanceof IAttachment || item instanceof IAmmo;
    }

    private void updateSelectedRecipeAfterFiltering() {
        if (selectedRecipeList == null || selectedRecipeList.isEmpty()) {
            this.selectedRecipe = null;
            this.playerIngredientCount = null;
            this.ingredientPage = 0;
            return;
        }
        boolean selectedRecipeExists = this.selectedRecipe != null && selectedRecipeList.contains(this.selectedRecipe.getId());
        if (!selectedRecipeExists) {
            this.selectedRecipe = this.getSelectedRecipe(selectedRecipeList.get(0));
            this.ingredientPage = 0;
        }
        this.getPlayerIngredientCount(this.selectedRecipe);
    }

    public void setIndexPage(int indexPage) {
        this.indexPage = indexPage;
    }

    @Nullable
    private GunSmithTableRecipe getSelectedRecipe(ResourceLocation recipeId) {
        if (recipeId == null) return null;
        if (isPaperWorkbench()) return paperRecipes.get(recipeId);
        if (Minecraft.getInstance().level != null) {
            RecipeManager recipeManager = Minecraft.getInstance().level.getRecipeManager();
            Recipe<?> recipe = recipeManager.byKey(recipeId).orElse(null);
            if (recipe instanceof GunSmithTableRecipe) {
                return (GunSmithTableRecipe) recipe;
            }
        }
        return null;
    }

    private void getPlayerIngredientCount(GunSmithTableRecipe recipe) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || recipe == null) {
            if (isPaperWorkbench()) playerIngredientCount = null;
            return;
        }
        if (isPaperWorkbench()) {
            playerIngredientCount = PaperWorkbenchRecipes.available(recipe, player.getInventory());
            return;
        }
        List<GunSmithTableIngredient> ingredients = recipe.getInputs();
        int size = ingredients.size();
        this.playerIngredientCount = new Int2IntArrayMap(size);
        for (int i = 0; i < size; i++) {
            GunSmithTableIngredient ingredient = ingredients.get(i);
            Inventory inventory = player.getInventory();
            int count = 0;
            for (ItemStack stack : inventory.items) {
                if (!stack.isEmpty() && ingredient.getIngredient().test(stack)) {
                    count = count + stack.getCount();
                }
            }
            playerIngredientCount.put(i, count);
        }
    }

    public void updateIngredientCount() {
        if (this.selectedRecipe != null) {
            this.getPlayerIngredientCount(selectedRecipe);
        }
        this.init();
    }

    /** Inventory synchronization can arrive after the transaction ACK; do not rebuild search widgets. */
    public void refreshPaperIngredients() {
        if (!isPaperWorkbench()) return;
        getPlayerIngredientCount(selectedRecipe);
        updatePaperCraftButton();
    }

    @Override
    public void init() {
        super.init();
        if (this.filterList == null) {
            this.filterList = new GunPackList(this.minecraft, 134, this.imageHeight, topPos, topPos+imageHeight+1, 15, recipes, this);
        }
        if (!this.autoByHandFilterApplied && RenderConfig.AUTO_SELECT_GUN_SMITH_TABLE_FILTER.get()) {
            this.filterList.setByHandSelected(this.shouldFilterByMainHand());
            this.autoByHandFilterApplied = true;
        }
        this.filterList.updateSize(134, this.imageHeight, topPos, topPos+imageHeight+1);
        this.filterList.setLeftPos(leftPos);

        this.classifyRecipes();
        this.updateSelectedRecipeAfterFiltering();
        this.clearWidgets();

        this.addTypePageButtons();
        this.addTypeButtons();
        this.addIndexPageButtons();
        this.addIndexButtons();
        this.addRenderableWidget(new FlatColorButton(leftPos - 10, topPos, 9, 9, Component.literal("F"), b -> {
            this.filterEnabled = !this.filterEnabled;
            this.init();
        }).setTooltips("gui.tacz.gun_smith_table.filter"));
        if(this.filterEnabled) {
            this.addRenderableWidget(this.filterList);
        } else {
            this.addScaleButtons();
            this.addUrlButton();
        }
        this.addCraftButton();
    }

    private void addCraftButton() {
        craftButton = this.addRenderableWidget(new ImageButton(leftPos + 289, topPos + 162, 48, 18, 138, 164, 18, TEXTURE, b -> {
            if (isPaperWorkbench()) {
                refreshPaperIngredients();
                if (!paperActions.pending() && hasIngredients() && selectedRecipe != null) {
                    paperActions.craft(selectedRecipe.getId());
                    updatePaperCraftButton();
                }
                return;
            }
            if (this.selectedRecipe != null && playerIngredientCount != null) {
                // 检查是否能合成，不能就不发包
                List<GunSmithTableIngredient> inputs = selectedRecipe.getInputs();
                int size = inputs.size();
                for (int i = 0; i < size; i++) {
                    if (i >= playerIngredientCount.size()) {
                        return;
                    }
                    int hasCount = playerIngredientCount.get(i);
                    int needCount = inputs.get(i).getCount();
                    boolean isCreative = Minecraft.getInstance().player != null && Minecraft.getInstance().player.isCreative();
                    // 拥有数量小于需求数量，不发包
                    if (hasCount < needCount && !isCreative) {
                        return;
                    }
                }
                NetworkHandler.CHANNEL.sendToServer(new ClientMessageCraft(this.selectedRecipe.getId(), this.menu.containerId));
            }
        }));
        updatePaperCraftButton();
    }

    private boolean hasIngredients() {
        if (selectedRecipe == null || playerIngredientCount == null) return false;
        List<GunSmithTableIngredient> inputs = selectedRecipe.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            // Unknown newer items or unavailable tags have no client icon/matcher. The Paper
            // server is still able to decide; never disable their transaction on a guessed count.
            if (inputs.get(i).getIngredient().getItems().length == 0) continue;
            if (playerIngredientCount.get(i) < inputs.get(i).getCount()) return false;
        }
        return true;
    }

    private void updatePaperCraftButton() {
        if (!isPaperWorkbench() || craftButton == null) return;
        craftButton.active = !paperActions.pending() && hasIngredients();
        craftButton.setTooltip(paperActions.pending() ? Tooltip.create(Component.literal("等待服务器确认…"))
                : selectedRecipe != null && !hasIngredients() ? Tooltip.create(Component.literal("合成材料不足")) : null);
    }

    private void addUrlButton() {
        this.addRenderableWidget(new ImageButton(leftPos + 112, topPos + 164, 18, 18, 149, 211, 18, TEXTURE, b -> {
            if (this.selectedRecipe != null) {
                ItemStack output = selectedRecipe.getOutput();
                Item item = output.getItem();
                ResourceLocation id;
                if (item instanceof IGun iGun) {
                    id = iGun.getGunId(output);
                } else if (item instanceof IAttachment iAttachment) {
                    id = iAttachment.getAttachmentId(output);
                } else if (item instanceof IAmmo iAmmo) {
                    id = iAmmo.getAmmoId(output);
                } else {
                    return;
                }

                PackInfo packInfo = ClientAssetsManager.INSTANCE.getPackInfo(id);
                if (packInfo == null) {
                    return;
                }
                String url = packInfo.getUrl();
                if (StringUtils.isNotBlank(url) && minecraft != null) {
                    if (isPaperWorkbench()) { paperActions.openLink(url); return; }
                    minecraft.setScreen(new ConfirmLinkScreen(yes -> {
                        if (yes) {
                            Util.getPlatform().openUri(url);
                        }
                        minecraft.setScreen(this);
                    }, url, false));
                }
            }
        }));
    }

    private void addIndexButtons() {
        if (selectedRecipeList == null || selectedRecipeList.isEmpty()) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            int finalIndex = i + indexPage * 6;
            if (finalIndex >= selectedRecipeList.size()) {
                break;
            }
            int yOffset = topPos + 66 + 17 * i;
            ResourceLocation recipeId = selectedRecipeList.get(finalIndex);
            GunSmithTableRecipe recipe = getSelectedRecipe(recipeId);
            if (recipe == null) {
                continue;
            }
            ResultButton button = addRenderableWidget(new ResultButton(leftPos + 144, yOffset, recipe.getOutput(), b -> {
                this.selectedRecipe = recipe;
                this.ingredientPage = 0;
                this.getPlayerIngredientCount(this.selectedRecipe);
                this.init();
            }));
            if (this.selectedRecipe != null && recipe.getId().equals(this.selectedRecipe.getId())) {
                button.setSelected(true);
            }
        }
    }

    private void addTypeButtons() {
        var list = Arrays.asList(recipeKeys.values().toArray(new TabConfig[0]));
        for (int i = 0; i < 7; i++) {
            int typeIndex = typePage * 7 + i;
            if (typeIndex >= recipes.size()) {
                return;
            }
            TabConfig tabConfig = list.get(typeIndex);
            ResourceLocation type = tabConfig.id();
            int xOffset = leftPos + 157 + 24 * i;

            ItemStack icon = tabConfig.icon();

            TypeButton typeButton = new TypeButton(xOffset, topPos + 2, icon, b -> {
                this.selectedType = type;
                this.selectedRecipeList = recipes.get(type);
                this.indexPage = 0;
                this.ingredientPage = 0;
                this.selectedRecipe = getSelectedRecipe(this.selectedRecipeList.isEmpty() ? null : this.selectedRecipeList.get(0));
                this.getPlayerIngredientCount(this.selectedRecipe);
                this.init();
            });
            typeButton.setTooltip(Tooltip.create(tabConfig.getName(), tabConfig.getName()));
            if (this.selectedType.equals(type)) {
                typeButton.setSelected(true);
            }
            this.addRenderableWidget(typeButton);
        }
    }

    private void addIndexPageButtons() {
        this.addRenderableWidget(new ImageButton(leftPos + 143, topPos + 56, 96, 6, 40, 166, 6, TEXTURE, b -> {
            if (this.indexPage > 0) {
                this.indexPage--;
                this.init();
            }
        }));
        this.addRenderableWidget(new ImageButton(leftPos + 143, topPos + 171, 96, 6, 40, 186, 6, TEXTURE, b -> {
            if (selectedRecipeList != null && !selectedRecipeList.isEmpty()) {
                int maxIndexPage = (selectedRecipeList.size() - 1) / 6;
                if (this.indexPage < maxIndexPage) {
                    this.indexPage++;
                    this.init();
                }
            }
        }));
    }

    private void addTypePageButtons() {
        this.addRenderableWidget(new ImageButton(leftPos + 136, topPos + 4, 18, 20, 0, 162, 20, TEXTURE, b -> {
            if (this.typePage > 0) {
                this.typePage--;
                this.init();
            }
        }));
        this.addRenderableWidget(new ImageButton(leftPos + 327, topPos + 4, 18, 20, 20, 162, 20, TEXTURE, b -> {
            int maxIndexPage = (recipes.size() - 1) / 7;
            if (this.typePage < maxIndexPage) {
                this.typePage++;
                this.init();
            }
        }));
    }

    private void addScaleButtons() {
        this.addRenderableWidget(new ImageButton(leftPos + 5, topPos + 5, 10, 10, 188, 173, 10, TEXTURE, b -> {
            this.scale = Math.min(this.scale + 20, 200);
        }));
        this.addRenderableWidget(new ImageButton(leftPos + 17, topPos + 5, 10, 10, 200, 173, 10, TEXTURE, b -> {
            this.scale = Math.max(this.scale - 20, 10);
        }));
        this.addRenderableWidget(new ImageButton(leftPos + 29, topPos + 5, 10, 10, 212, 173, 10, TEXTURE, b -> {
            this.scale = 70;
        }));
    }

    @Override
    public boolean mouseScrolled(double pMouseX, double pMouseY, double pDelta) {
        if (isPaperWorkbench() && selectedRecipe != null && pMouseX >= leftPos + 250 && pMouseX < leftPos + 344
                && pMouseY >= topPos + 50 && pMouseY < topPos + 161) {
            int last = Math.max(0, (selectedRecipe.getInputs().size() - 1) / 8);
            ingredientPage = Math.max(0, Math.min(last, ingredientPage + (pDelta > 0 ? -1 : pDelta < 0 ? 1 : 0)));
            return true;
        }
        if (selectedRecipeList != null && !selectedRecipeList.isEmpty()
                && pMouseX > leftPos + 143 && pMouseX < leftPos + 143 + 94 && pMouseY > topPos + 66 && pMouseY < topPos + 66 + 85) {
            if (pDelta > 0) {
                this.indexPage = Math.max(0, this.indexPage - 1);
            } else {
                int maxIndexPage = (selectedRecipeList.size() - 1) / 6;
                this.indexPage = Math.min(maxIndexPage, this.indexPage + 1);
            }
            this.init();
            return true;
        }
        return super.mouseScrolled(pMouseX, pMouseY, pDelta);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawModCenteredString(graphics, font, Component.translatable("gui.tacz.gun_smith_table.preview"), leftPos + 108, topPos + 5, 0x555555);
        if (selectedType != null) {
            var config = recipeKeys.get(selectedType);
            if (config != null) {
                graphics.drawString(font, config.getName(), leftPos + 150, topPos + 32, 0x555555, false);
            }
        }
        graphics.drawString(font, Component.translatable("gui.tacz.gun_smith_table.ingredient"), leftPos + 254, topPos + 50, 0x555555, false);
        drawModCenteredString(graphics, font, Component.translatable("gui.tacz.gun_smith_table.craft"), leftPos + 312, topPos + 167, 0xFFFFFF);
        if (!this.filterEnabled && this.selectedRecipe != null) {
            this.renderLeftModel(this.selectedRecipe);
            this.renderPackInfo(graphics, this.selectedRecipe);
            graphics.drawString(font, Component.translatable("gui.tacz.gun_smith_table.count", this.selectedRecipe.getResult().getResult().getCount()), leftPos + 254, topPos + 140, 0x555555, false);
        }
        if (selectedRecipeList != null && !selectedRecipeList.isEmpty()) {
            renderIngredient(graphics, mouseX, mouseY);
        }

        this.renderables.stream().filter(w -> w instanceof ResultButton)
                .forEach(w -> ((ResultButton) w).renderTooltips(stack -> graphics.renderTooltip(font, stack, mouseX, mouseY)));
    }

    private void renderPackInfo(GuiGraphics gui, GunSmithTableRecipe recipe) {
        ItemStack output = recipe.getOutput();
        Item item = output.getItem();
        ResourceLocation id;
        if (item instanceof IGun iGun) {
            id = iGun.getGunId(output);
        } else if (item instanceof IAttachment iAttachment) {
            id = iAttachment.getAttachmentId(output);
        } else if (item instanceof IAmmo iAmmo) {
            id = iAmmo.getAmmoId(output);
        } else {
            return;
        }

        PackInfo packInfo = ClientAssetsManager.INSTANCE.getPackInfo(id);
        PoseStack poseStack = gui.pose();
        if (packInfo != null) {
            poseStack.pushPose();
            poseStack.scale(0.75f, 0.75f, 1);
            Component nameText = Component.translatable(packInfo.getName());
            gui.drawString(font, nameText, (int) ((leftPos + 6) / 0.75f), (int) ((topPos + 122) / 0.75f), ChatFormatting.DARK_GRAY.getColor(), false);
            poseStack.popPose();

            poseStack.pushPose();
            poseStack.scale(0.5f, 0.5f, 1);

            int offsetX = (leftPos + 6) * 2;
            int offsetY = (topPos + 123) * 2;
            int nameWidth = font.width(nameText);
            Component ver = Component.literal("v" + packInfo.getVersion()).withStyle(ChatFormatting.UNDERLINE);
            gui.drawString(font, ver, (int) (offsetX + nameWidth * 0.75f / 0.5f + 5), offsetY, ChatFormatting.DARK_GRAY.getColor(), false);
            offsetY += 14;

            String descKey = packInfo.getDescription();
            if (StringUtils.isNoneBlank(descKey)) {
                Component desc = Component.translatable(descKey);
                List<FormattedCharSequence> split = font.split(desc, 245);
                for (FormattedCharSequence charSequence : split) {
                    gui.drawString(font, charSequence, offsetX, offsetY, ChatFormatting.DARK_GRAY.getColor(), false);
                    offsetY += font.lineHeight;
                }
                offsetY += 3;
            }

            gui.drawString(font, Component.translatable("gui.tacz.gun_smith_table.license")
                            .append(Component.literal(packInfo.getLicense()).withStyle(ChatFormatting.DARK_GRAY)),
                    offsetX, offsetY, ChatFormatting.DARK_GRAY.getColor(), false);
            offsetY += 12;

            List<String> authors = packInfo.getAuthors();
            if (!authors.isEmpty()) {
                gui.drawString(font, Component.translatable("gui.tacz.gun_smith_table.authors")
                                .append(Component.literal(StringUtils.join(authors, ", ")).withStyle(ChatFormatting.DARK_GRAY)),
                        offsetX, offsetY, ChatFormatting.DARK_GRAY.getColor(), false);
                offsetY += 12;
            }

            gui.drawString(font, Component.translatable("gui.tacz.gun_smith_table.date")
                            .append(Component.literal(packInfo.getDate()).withStyle(ChatFormatting.DARK_GRAY)),
                    offsetX, offsetY, ChatFormatting.DARK_GRAY.getColor(), false);

            poseStack.popPose();
        } else {
            ResourceLocation recipeId = recipe.getId();
            gui.drawString(font, Component.translatable("gui.tacz.gun_smith_table.error").withStyle(ChatFormatting.DARK_RED), leftPos + 6, topPos + 122, 0xAF0000, false);
            gui.drawString(font, Component.translatable("gui.tacz.gun_smith_table.error.id", recipeId.toString()).withStyle(ChatFormatting.DARK_RED), leftPos + 6, topPos + 134, 0xFFFFFF, false);
            PackInfo errorPackInfo = ClientAssetsManager.INSTANCE.getPackInfo(id);
            if (errorPackInfo != null) {
                gui.drawString(font, Component.translatable(errorPackInfo.getName()).withStyle(ChatFormatting.DARK_RED), leftPos + 6, topPos + 146, 0xAF0000, false);
            }
        }
    }

    private void renderIngredient(GuiGraphics gui, int mouseX, int mouseY) {
        if (this.selectedRecipe == null) {
            return;
        }
        List<GunSmithTableIngredient> inputs = this.selectedRecipe.getInputs();
        int pages = isPaperWorkbench() ? Math.max(1, (inputs.size() + 7) / 8) : 1;
        ingredientPage = Math.max(0, Math.min(pages - 1, ingredientPage));
        if (isPaperWorkbench() && pages > 1) {
            gui.drawString(font, (ingredientPage + 1) + "/" + pages + " ↕", leftPos + 254, topPos + 151, 0x555555, false);
        }
        ItemStack hoveredItem = null;
        int hoveredUnknownCount = 0;
        int rows = isPaperWorkbench() ? 4 : 6;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < 2; j++) {
                int index = i * 2 + j + (isPaperWorkbench() ? ingredientPage * 8 : 0);
                if (index >= inputs.size()) {
                    break;
                }
                int offsetX = leftPos + 254 + 45 * j;
                int offsetY = topPos + 62 + 17 * i;

                GunSmithTableIngredient smithTableIngredient = inputs.get(index);
                Ingredient ingredient = smithTableIngredient.getIngredient();

                ItemStack[] items = ingredient.getItems();
                boolean unknown = items.length == 0;
                int itemIndex = unknown ? 0 : ((int) (System.currentTimeMillis() / 1_000)) % items.length;
                ItemStack item = unknown ? new ItemStack(Items.BARRIER) : items[itemIndex];

                gui.renderFakeItem(item, offsetX, offsetY);
                if (mouseX >= offsetX && mouseX < offsetX + 16 && mouseY >= offsetY && mouseY < offsetY + 16) {
                    if (unknown) hoveredUnknownCount = smithTableIngredient.getCount();
                    else hoveredItem = item;
                }

                PoseStack poseStack = gui.pose();
                poseStack.pushPose();

                poseStack.translate(0, 0, 200);
                poseStack.scale(0.5f, 0.5f, 1);
                int count = smithTableIngredient.getCount();
                if (unknown && isPaperWorkbench()) {
                    gui.drawString(font, count + "/?", (offsetX + 17) * 2, (offsetY + 10) * 2, 0xFFCC66, false);
                } else if (!isPaperWorkbench() && Minecraft.getInstance().player != null && Minecraft.getInstance().player.isCreative()){
                    gui.drawString(font, String.format("%d/∞", count), (offsetX + 17) * 2, (offsetY + 10) * 2, 0xFFFFFF, false);
                } else {
                    int hasCount = 0;
                    if (playerIngredientCount != null && index < playerIngredientCount.size()) {
                        hasCount = playerIngredientCount.get(index);
                    }
                    int color = count <= hasCount ? 0xFFFFFF : 0xFF0000;
                    gui.drawString(font, String.format("%d/%d", count, hasCount), (offsetX + 17) * 2, (offsetY + 10) * 2, color, false);
                }


                poseStack.popPose();
            }
        }
        if (hoveredItem != null) gui.renderTooltip(font, hoveredItem, mouseX, mouseY);
        else if (hoveredUnknownCount > 0) gui.renderTooltip(font,
                Component.literal("材料由服务器确认，所需数量：" + hoveredUnknownCount), mouseX, mouseY);
        else if (isPaperWorkbench() && pages > 1 && mouseX >= leftPos + 250 && mouseX < leftPos + 344
                && mouseY >= topPos + 149 && mouseY < topPos + 162)
            gui.renderTooltip(font, Component.literal("滚动查看全部材料"), mouseX, mouseY);
    }

    @SuppressWarnings("deprecation")
    private void renderLeftModel(GunSmithTableRecipe recipe) {
        // 先标记一下，渲染高模
        RenderDistance.markGuiRenderTimestamp();

        float rotationPeriod = 8f;
        int xPos = leftPos + 60;
        int yPos = topPos + 50;
        int startX = leftPos + 3;
        int startY = topPos + 16;
        int width = 128;
        int height = 99;
        float rotPitch = 15;

        Window window = Minecraft.getInstance().getWindow();
        double windowGuiScale = window.getGuiScale();
        int scissorX = (int) (startX * windowGuiScale);
        int scissorY = (int) (window.getHeight() - ((startY + height) * windowGuiScale));
        int scissorW = (int) (width * windowGuiScale);
        int scissorH = (int) (height * windowGuiScale);
        RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);

        Minecraft.getInstance().textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).setFilter(false, false);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        PoseStack posestack = RenderSystem.getModelViewStack();
        posestack.pushPose();
        posestack.translate(xPos, yPos, 200);
        posestack.translate(8.0D, 8.0D, 0.0D);
        posestack.scale(1.0F, -1.0F, 1.0F);
        posestack.scale(scale, scale, scale);
        float rot = (System.currentTimeMillis() % (int) (rotationPeriod * 1000)) * (360f / (rotationPeriod * 1000));
        posestack.mulPose(Axis.XP.rotationDegrees(rotPitch));
        posestack.mulPose(Axis.YP.rotationDegrees(rot));
        RenderSystem.applyModelViewMatrix();
        PoseStack tmpPose = new PoseStack();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        Lighting.setupForFlatItems();

        Minecraft.getInstance().getItemRenderer().renderStatic(recipe.getOutput(), ItemDisplayContext.FIXED, 0xf000f0, OverlayTexture.NO_OVERLAY, tmpPose, bufferSource, null, 0);

        bufferSource.endBatch();
        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();
        posestack.popPose();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableScissor();
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics gui, int mouseX, int mouseY) {
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        this.renderBackground(gui);
        gui.blit(SIDE, leftPos, topPos, 0, 0, 134, 187);
        gui.blit(TEXTURE, leftPos + 136, topPos + 27, 0, 0, 208, 160);
    }

    @Override
    protected void containerTick() {
        if (isPaperWorkbench()) paperActions.tick();
        else super.containerTick();
    }

    /** Invoked by the Paper-only guard for AbstractContainerScreen's final tick method. */
    public void tickPaperWorkbench() {
        if (isPaperWorkbench()) paperActions.tick();
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ClickType clickType) {
        if (!isPaperWorkbench()) super.slotClicked(slot, slotId, button, clickType);
    }

    @Override
    public void onClose() {
        if (isPaperWorkbench()) {
            paperActions.close();
            if (minecraft != null) minecraft.setScreen(null);
        } else super.onClose();
    }

    @Override
    public void removed() {
        // There is no corresponding server container for the Paper view. In particular, do not
        // run AbstractContainerScreen's cleanup against the player's actual inventory menu.
        if (!isPaperWorkbench()) super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
