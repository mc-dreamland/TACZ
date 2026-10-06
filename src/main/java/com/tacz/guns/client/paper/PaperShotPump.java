package com.tacz.guns.client.paper;

import com.tacz.guns.GunMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Both callbacks execute on the client thread; charge/input processing stays in ShootKey. */
@EventBusSubscriber(modid = GunMod.MOD_ID, value = Dist.CLIENT)
public final class PaperShotPump {
    private PaperShotPump() { }

    @SubscribeEvent
    public static void renderTick(RenderFrameEvent.Pre event) {
        { PaperClientGameplay.pumpShot(); PaperShotPresentation.pump(); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void clientTick(ClientTickEvent.Post event) {
        { PaperClientGameplay.pumpShot(); PaperShotPresentation.pump(); }
    }
}
