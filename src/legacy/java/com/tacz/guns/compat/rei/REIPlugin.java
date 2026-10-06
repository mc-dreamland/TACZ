package com.tacz.guns.compat.rei;

import com.tacz.guns.api.item.gun.GunItemManager;
import com.tacz.guns.init.ModItems;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;

@me.shedaniel.rei.forge.REIPluginCommon
public class REIPlugin implements me.shedaniel.rei.api.common.plugins.REICommonPlugin {
    @Override
    public void registerItemComparators(ItemComparatorRegistry registry) {
        registry.register(REISubtype.getAmmoSubtype(), ModItems.AMMO.get());
        registry.register(REISubtype.getAttachmentSubtype(), ModItems.ATTACHMENT.get());
        registry.register(REISubtype.getAmmoBoxSubtype(), ModItems.AMMO_BOX.get());
        registry.register(REISubtype.getTableSubType(), ModItems.WORKBENCH_111.get());
        registry.register(REISubtype.getTableSubType(), ModItems.WORKBENCH_121.get());
        registry.register(REISubtype.getTableSubType(), ModItems.WORKBENCH_211.get());
        GunItemManager.getAllGunItems().forEach(item ->
                registry.register(REISubtype.getGunSubtype(), item));
    }


    @Override
    public Class<me.shedaniel.rei.api.common.plugins.REICommonPlugin> getPluginProviderClass() {
        return me.shedaniel.rei.api.common.plugins.REICommonPlugin.class;
    }
}
