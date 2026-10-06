package com.tacz.guns.compat.cloth;

import com.tacz.guns.compat.cloth.client.KeyClothConfig;
import com.tacz.guns.compat.cloth.client.RenderClothConfig;
import com.tacz.guns.compat.cloth.client.ResourceClothConfig;
import com.tacz.guns.compat.cloth.client.SoundClothConfig;
import com.tacz.guns.compat.cloth.client.ZoomClothConfig;
import com.tacz.guns.compat.cloth.common.AmmoClothConfig;
import com.tacz.guns.compat.cloth.common.GunClothConfig;
import com.tacz.guns.compat.cloth.common.OtherClothConfig;
import com.tacz.guns.config.ClientConfig;
import com.tacz.guns.config.CommonConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import javax.annotation.Nullable;

/**
 * TACZ classic Cloth Config screen: eight categories (Key/Render/Resource/Sound/Zoom
 * + Gun/Ammo/Other), aligned with MUKSC/TACZ-1.21.1 (NeoForge idiom) and
 * TaCZ_Refabricated_Unofficial 26.1.2 (game semantics; the only content delta
 * is 26.1.2's extra {@code scope_mask_enable} entry, kept).
 *
 * <p>Cloth Config is an OPTIONAL runtime dependency (modid {@code cloth_config});
 * compile classpath is {@code me.shedaniel.cloth:cloth-config-neoforge:26.1.154}.
 * The T-key entry point sends a clickable download hint instead when absent
 * (see ConfigKey), the Mods-menu falls back to ClothConfigScreen.</p>
 */
public class MenuIntegration implements IExtensionPoint {
    public static ConfigBuilder getConfigBuilder() {
        ConfigBuilder root = ConfigBuilder.create().setTitle(Component.literal("Timeless and Classics Guns"));
        root.setSavingRunnable(() -> {
            CommonConfig.spec.save();
            ClientConfig.spec.save();
        });
        root.setGlobalized(true);
        root.setGlobalizedExpanded(false);
        ConfigEntryBuilder entryBuilder = root.entryBuilder();

        KeyClothConfig.init(root, entryBuilder);
        RenderClothConfig.init(root, entryBuilder);
        ResourceClothConfig.init(root, entryBuilder);
        SoundClothConfig.init(root, entryBuilder);
        ZoomClothConfig.init(root, entryBuilder);

        GunClothConfig.init(root, entryBuilder);
        AmmoClothConfig.init(root, entryBuilder);
        OtherClothConfig.init(root, entryBuilder);

        return root;
    }

    public static void registerModsPage(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, screen) -> getConfigScreen(screen));
    }

    public static Screen getConfigScreen(@Nullable Screen parent) {
        return MenuIntegration.getConfigBuilder().setParentScreen(parent).build();
    }
}
