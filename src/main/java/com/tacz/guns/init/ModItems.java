package com.tacz.guns.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.item.gun.GunItemManager;
import com.tacz.guns.item.AmmoBoxItem;
import com.tacz.guns.item.AmmoItem;
import com.tacz.guns.item.AttachmentItem;
import com.tacz.guns.item.DefaultTableItem;
import com.tacz.guns.item.GunSmithTableItem;
import com.tacz.guns.item.ModernKineticGunItem;
import com.tacz.guns.item.TargetMinecartItem;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GunMod.MOD_ID);

    public static final DeferredItem<ModernKineticGunItem> MODERN_KINETIC_GUN =
            ITEMS.registerItem("modern_kinetic_gun", ModernKineticGunItem::new);

    public static final DeferredItem<AmmoItem> AMMO =
            ITEMS.registerItem("ammo", AmmoItem::new);
    public static final DeferredItem<AttachmentItem> ATTACHMENT =
            ITEMS.registerItem("attachment", AttachmentItem::new);

    public static final DeferredItem<GunSmithTableItem> GUN_SMITH_TABLE =
            ITEMS.registerItem("gun_smith_table",
                    props -> new DefaultTableItem(ModBlocks.GUN_SMITH_TABLE.get(), props));
    public static final DeferredItem<GunSmithTableItem> WORKBENCH_111 =
            ITEMS.registerItem("workbench_a",
                    props -> new GunSmithTableItem(ModBlocks.WORKBENCH_111.get(), props));
    public static final DeferredItem<GunSmithTableItem> WORKBENCH_211 =
            ITEMS.registerItem("workbench_b",
                    props -> new GunSmithTableItem(ModBlocks.WORKBENCH_211.get(), props));
    public static final DeferredItem<GunSmithTableItem> WORKBENCH_121 =
            ITEMS.registerItem("workbench_c",
                    props -> new GunSmithTableItem(ModBlocks.WORKBENCH_121.get(), props));

    public static final DeferredItem<BlockItem> TARGET =
            ITEMS.registerSimpleBlockItem("target", ModBlocks.TARGET);
    public static final DeferredItem<BlockItem> STATUE =
            ITEMS.registerSimpleBlockItem("statue", ModBlocks.STATUE);
    public static final DeferredItem<AmmoBoxItem> AMMO_BOX =
            ITEMS.registerItem("ammo_box", AmmoBoxItem::new);
    public static final DeferredItem<TargetMinecartItem> TARGET_MINECART =
            ITEMS.registerItem("target_minecart", TargetMinecartItem::new);

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        GunItemManager.registerGunItem(ModernKineticGunItem.TYPE_NAME, MODERN_KINETIC_GUN.get());
        GunMod.LOGGER.info(
                "registries ready: gun={} workbench_a={} gun_smith_table={} recipe={}",
                MODERN_KINETIC_GUN.getId(),
                ModBlocks.WORKBENCH_111.getId(),
                GUN_SMITH_TABLE.getId(),
                ModRecipe.GUN_SMITH_TABLE_CRAFTING.getId());
    }
}
