package com.tacz.guns.paper.pack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemModelDataTest {
    @Test void defaultRangesStayStableForBedrockMappings() {
        assertEquals(3000000, ItemModelData.defaultValue("gun", "bujidao:ar_01"));
        assertEquals(3000009, ItemModelData.defaultValue("gun", "bujidao:sr_04"));
        assertEquals(3001000, ItemModelData.defaultValue("ammo", "tacz:12g"));
        assertEquals(3001005, ItemModelData.defaultValue("ammo", "tacz:762x39"));
        assertEquals(3002000, ItemModelData.defaultValue("attachment", "bujidao:ar_02jing"));
        assertEquals(3002009, ItemModelData.defaultValue("attachment", "tacz:extended_mag_3"));
        assertEquals(0, ItemModelData.defaultValue("gun", "custom:unassigned"));
    }

    @Test void ammoBoxesSupportDefaultsOverrideDisablingAndValidation() {
        var config = new YamlConfiguration();
        for (int level = 0; level < 3; level++) assertEquals(3003000 + level, ItemModelData.ammoBox(config, level));
        config.set("ammo-box.modeldata.0", 4000000);
        config.set("ammo-box.modeldata.1", 0);
        assertEquals(4000000, ItemModelData.ammoBox(config, 0));
        assertNull(ItemModelData.ammoBox(config, 1));
        assertDoesNotThrow(() -> ItemModelData.validateAmmoBoxes(config));
        for (Object bad : new Object[]{-1, 1.5, "3003000", 16777217}) {
            config.set("ammo-box.modeldata.2", bad);
            assertThrows(IllegalArgumentException.class, () -> ItemModelData.validateAmmoBoxes(config));
        }
    }
}
