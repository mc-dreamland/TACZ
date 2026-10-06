package com.tacz.guns.api.client.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import com.tacz.guns.api.event.common.KubeJSGunEventPoster;

/**
 * 当第一人称视角触发摇晃时，玩家手部的摇晃
 */
public class RenderItemInHandBobEvent extends Event implements KubeJSGunEventPoster<RenderItemInHandBobEvent> {


    public interface HurtCallback {
        void post(BobHurt event);
    }

    public interface ViewCallback {
        void post(BobView event);
    }

    public static class BobHurt extends RenderItemInHandBobEvent implements ICancellableEvent {
        public BobHurt() {
            postClientEventToKubeJS(this);
        }
    }

    public static class BobView extends RenderItemInHandBobEvent implements ICancellableEvent {
        public BobView() {
            postClientEventToKubeJS(this);
        }
    }
}
