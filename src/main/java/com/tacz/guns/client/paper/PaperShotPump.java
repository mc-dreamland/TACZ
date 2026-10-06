package com.tacz.guns.client.paper;

import com.tacz.guns.GunMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Both callbacks execute on the client thread; charge/input processing stays in ShootKey. */
@Mod.EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class PaperShotPump {
    private PaperShotPump() { }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) { PaperClientGameplay.pumpShot(); PaperShotPresentation.pump(); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) { PaperClientGameplay.pumpShot(); PaperShotPresentation.pump(); }
    }
}
