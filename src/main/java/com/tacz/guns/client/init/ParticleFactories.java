package com.tacz.guns.client.init;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.particle.BulletHoleParticle;
import com.tacz.guns.init.ModParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public class ParticleFactories {
    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpecial(ModParticles.BULLET_HOLE.get(), new BulletHoleParticle.Provider());
    }
}
