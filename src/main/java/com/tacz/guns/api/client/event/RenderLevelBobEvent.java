package com.tacz.guns.api.client.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import com.tacz.guns.api.event.common.KubeJSGunEventPoster;

/**
 * 当第一人称视角触发摇晃时，世界背景的摇晃
 */
public class RenderLevelBobEvent extends Event implements KubeJSGunEventPoster<RenderLevelBobEvent> {


    public interface HurtCallback {
        void post(BobHurt event);
    }

    public interface ViewCallback {
        void post(BobView event);
    }

    public static class BobHurt extends RenderLevelBobEvent implements ICancellableEvent {
        public BobHurt() {
            postClientEventToKubeJS(this);
        }
    }

    public static class BobView extends RenderLevelBobEvent implements ICancellableEvent {
        public BobView() {
            postClientEventToKubeJS(this);
        }
    }
}
