package com.tacz.guns;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tacz.guns.api.resource.ResourceManager;
import com.tacz.guns.config.ClientConfig;
import com.tacz.guns.config.CommonConfig;
import com.tacz.guns.config.PreLoadConfig;
import com.tacz.guns.config.ServerConfig;
import com.tacz.guns.init.CapabilityRegistry;
import com.tacz.guns.init.CommonRegistry;
import com.tacz.guns.init.ModAttributes;
import com.tacz.guns.init.ModBlocks;
import com.tacz.guns.init.ModCreativeTabs;
import com.tacz.guns.init.ModEntities;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.init.ModParticles;
import com.tacz.guns.init.ModRecipe;
import com.tacz.guns.init.ModSounds;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(GunMod.MOD_ID)
public class GunMod {
    public static final String MOD_ID = "tacz";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String DEFAULT_GUN_PACK_NAME = "tacz_default_gun";

    public static net.neoforged.fml.ModContainer container;

    public GunMod(IEventBus modEventBus, ModContainer modContainer) {
        container = modContainer;
        PreLoadConfig.migrateLegacyConfig();
        modContainer.registerConfig(ModConfig.Type.STARTUP, PreLoadConfig.spec, "tacz-pre.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, CommonConfig.spec);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.spec);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.spec);

        CapabilityRegistry.ATTACHMENT_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.TILE_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        ModRecipe.RECIPE_SERIALIZERS.register(modEventBus);
        ModRecipe.RECIPE_TYPES.register(modEventBus);
        ModRecipe.RECIPE_BOOK_CATEGORIES.register(modEventBus);
        ModRecipe.INGREDIENT_TYPES.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        com.tacz.guns.init.ModContainer.CONTAINER_TYPE.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        com.tacz.guns.init.ModLootModifiers.LOOT_MODIFIER_SERIALIZERS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);
        ModAttributes.ATTRIBUTES.register(modEventBus);

        modEventBus.addListener(ModItems::onCommonSetup);
        modEventBus.addListener(NetworkHandler::register);
        modEventBus.addListener(NetworkHandler::registerConfigurationTasks);
        modEventBus.addListener(CommonRegistry::onSetupEvent);
        modEventBus.addListener(CommonRegistry::onLoadComplete);
        modEventBus.addListener(CommonRegistry::registerAttributes);
        modEventBus.addListener(CommonRegistry::onAddPackFinders);

        registerDefaultExtraGunPack();
        AttachmentPropertyManager.registerModifier();
        LOGGER.info("TaCZ NeoForge 1.21.10 loading. modId={}", MOD_ID);
    }

    private static void registerDefaultExtraGunPack() {
        String jarDefaultPackPath = String.format("/assets/%s/custom/%s", GunMod.MOD_ID, DEFAULT_GUN_PACK_NAME);
        ResourceManager.registerExportResource(GunMod.class, jarDefaultPackPath);
    }
}
