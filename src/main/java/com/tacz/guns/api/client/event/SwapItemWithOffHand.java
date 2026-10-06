package com.tacz.guns.api.client.event;

import com.tacz.guns.api.event.common.KubeJSGunEventPoster;
import net.neoforged.bus.api.Event;

public class SwapItemWithOffHand extends Event implements KubeJSGunEventPoster<SwapItemWithOffHand> {
    public SwapItemWithOffHand() {
        postClientEventToKubeJS(this);
    }
}
