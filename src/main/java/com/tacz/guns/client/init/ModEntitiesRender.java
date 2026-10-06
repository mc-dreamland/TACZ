package com.tacz.guns.client.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.renderer.block.GunSmithTableRenderer;
import com.tacz.guns.client.renderer.block.StatueRenderer;
import com.tacz.guns.client.renderer.block.TargetRenderer;
import com.tacz.guns.client.renderer.entity.EntityBulletRenderer;
import com.tacz.guns.client.renderer.entity.TargetMinecartRenderer;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.entity.TargetMinecart;
import com.tacz.guns.init.ModBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public class ModEntitiesRender {
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityKineticBullet.TYPE, EntityBulletRenderer::new);
        event.registerEntityRenderer(TargetMinecart.TYPE, TargetMinecartRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.GUN_SMITH_TABLE_BE.get(), GunSmithTableRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.TARGET_BE.get(), TargetRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.STATUE_BE.get(), StatueRenderer::new);
    }
}
