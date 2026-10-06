package com.tacz.guns.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.item.AmmoBoxItem;
import com.tacz.guns.item.AmmoItem;
import com.tacz.guns.item.AttachmentItem;
import com.tacz.guns.item.GunSmithTableItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative tabs are split by the same categories used by the gun-pack data.
 * Every generator creates data-bearing ItemStacks, not bare registry items.
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GunMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> OTHER_TAB = TABS.register("other",
            () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.tab.tacz.other"))
                    .icon(() -> ModItems.GUN_SMITH_TABLE.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        GunSmithTableItem.fillItemCategory().forEach(output::accept);
                        output.accept(ModItems.TARGET.get());
                        output.accept(ModItems.STATUE.get());
                        output.accept(ModItems.TARGET_MINECART.get());
                        AmmoBoxItem.fillItemCategory(output);
                    }).build());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> AMMO_TAB = TABS.register("ammo",
            () -> categoryTab("itemGroup.tab.tacz.ammo", DefaultAssets.DEFAULT_AMMO_ID,
                    OTHER_TAB.getId(), output -> AmmoItem.fillItemCategory().forEach(output::accept)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_SCOPE_TAB =
            registerAttachmentTab("scope", "tacz.type.scope.name", AttachmentType.SCOPE,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "scope_acog_ta31"), AMMO_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_MUZZLE_TAB =
            registerAttachmentTab("muzzle", "tacz.type.muzzle.name", AttachmentType.MUZZLE,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "muzzle_compensator_trident"), ATTACHMENT_SCOPE_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_STOCK_TAB =
            registerAttachmentTab("stock", "tacz.type.stock.name", AttachmentType.STOCK,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "stock_militech_b5"), ATTACHMENT_MUZZLE_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_GRIP_TAB =
            registerAttachmentTab("grip", "tacz.type.grip.name", AttachmentType.GRIP,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "grip_magpul_afg_2"), ATTACHMENT_STOCK_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_EXTENDED_MAG_TAB =
            registerAttachmentTab("extended_mag", "tacz.type.extended_mag.name", AttachmentType.EXTENDED_MAG,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "extended_mag_3"), ATTACHMENT_GRIP_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ATTACHMENT_LASER_TAB =
            registerAttachmentTab("laser", "tacz.type.laser.name", AttachmentType.LASER,
                    ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, "laser_compact"), ATTACHMENT_EXTENDED_MAG_TAB);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_PISTOL_TAB =
            registerGunTab("pistol", GunTabType.PISTOL, "glock_17", ATTACHMENT_LASER_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_SNIPER_TAB =
            registerGunTab("sniper", GunTabType.SNIPER, "ai_awp", GUN_PISTOL_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_RIFLE_TAB =
            registerGunTab("rifle", GunTabType.RIFLE, "ak47", GUN_SNIPER_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_SHOTGUN_TAB =
            registerGunTab("shotgun", GunTabType.SHOTGUN, "db_short", GUN_RIFLE_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_SMG_TAB =
            registerGunTab("smg", GunTabType.SMG, "hk_mp5a5", GUN_SHOTGUN_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_RPG_TAB =
            registerGunTab("rpg", GunTabType.RPG, "rpg7", GUN_SMG_TAB);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GUN_MG_TAB =
            registerGunTab("mg", GunTabType.MG, "m249", GUN_RPG_TAB);

    private ModCreativeTabs() {
    }

    private static CreativeModeTab categoryTab(String titleKey, ResourceLocation iconId,
                                                ResourceLocation tabBefore,
                                                java.util.function.Consumer<CreativeModeTab.Output> contents) {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable(titleKey))
                .withTabsBefore(tabBefore)
                .icon(() -> AmmoItemBuilder.create().setId(iconId).build())
                .displayItems((parameters, output) -> contents.accept(output))
                .build();
    }

    private static DeferredHolder<CreativeModeTab, CreativeModeTab> registerAttachmentTab(
            String id, String titleKey, AttachmentType type, ResourceLocation iconId,
            DeferredHolder<CreativeModeTab, CreativeModeTab> tabBefore) {
        return TABS.register(id, () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable(titleKey))
                .withTabsBefore(tabBefore.getId())
                .icon(() -> AttachmentItemBuilder.create().setId(iconId).build())
                .displayItems((parameters, output) -> AttachmentItem.fillItemCategory(type).forEach(output::accept))
                .build());
    }

    private static DeferredHolder<CreativeModeTab, CreativeModeTab> registerGunTab(
            String id, GunTabType type, String iconPath,
            DeferredHolder<CreativeModeTab, CreativeModeTab> tabBefore) {
        ResourceLocation iconId = ResourceLocation.fromNamespaceAndPath(GunMod.MOD_ID, iconPath);
        return TABS.register(id, () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("tacz.type." + id + ".name"))
                .withTabsBefore(tabBefore.getId())
                .icon(() -> GunItemBuilder.create().setId(iconId).build())
                .displayItems((parameters, output) -> AbstractGunItem.fillItemCategory(type).forEach(output::accept))
                .build());
    }
}
