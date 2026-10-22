package com.tacz.guns.client.gui.compat;

import com.tacz.guns.init.CompatRegistry;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.apache.commons.lang3.StringUtils;

public class ClothConfigScreen extends Screen {
    public static final String CLOTH_CONFIG_URL = "https://www.curseforge.com/minecraft/mc-mods/cloth-config";
    private final Screen lastScreen;
    private MultiLineLabel message = MultiLineLabel.EMPTY;

    protected ClothConfigScreen(Screen lastScreen) {
        super(Component.literal("Cloth Config API"));
        this.lastScreen = lastScreen;
    }

    /**
     * Mods-menu fallback when Cloth Config is absent (MUKSC/TACZ-1.21.1 idiom).
     * Shows a download hint instead of the config screen.
     */
    public static void registerNoClothConfigPage(ModContainer container) {
        if (!ModList.get().isLoaded(CompatRegistry.CLOTH_CONFIG)) {
            container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, screen) -> new ClothConfigScreen(screen));
        }
    }

    @Override
    protected void init() {
        int posX = (this.width - 200) / 2;
        int posY = this.height / 2;
        this.message = MultiLineLabel.create(this.font, Component.translatable("gui.tacz.cloth_config_warning.tips"), 300);
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.tacz.cloth_config_warning.download"), b -> openUrl(CLOTH_CONFIG_URL))
                        .bounds(posX, posY - 15, 200, 20).build()
        );
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_BACK, b -> this.minecraft.setScreenAndShow(this.lastScreen))
                        .bounds(posX, posY + 50, 200, 20).build()
        );
    }

    @Override
    public void render(GuiGraphics gui, int pMouseX, int pMouseY, float pPartialTick) {
        // 26.1.2: vanilla Screen#extractRenderState already extracts the (blurred)
        // background exactly once per frame — never call extractBackground manually
        // here (r13 crash: "Can only blur once per frame").
        int centerX = this.width / 2;
        int centerY = this.height / 4 - 20;
        int lineY = centerY;
        for (var line : this.font.split(Component.translatable("gui.tacz.cloth_config_warning.tips"), 300)) {
            gui.drawCenteredString(this.font, line, centerX, lineY, 0xFFFFFFFF);
            lineY += 9;
        }
        super.render(gui, pMouseX, pMouseY, pPartialTick);
    }

    private void openUrl(String url) {
        if (StringUtils.isNotBlank(url) && minecraft != null) {
            minecraft.setScreenAndShow(new ConfirmLinkScreen(yes -> {
                if (yes) {
                    Util.getPlatform().openUri(url);
                }
                minecraft.setScreenAndShow(this);
            }, url, true));
        }
    }
}
