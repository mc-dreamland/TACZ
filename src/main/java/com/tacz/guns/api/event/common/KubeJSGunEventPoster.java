package com.tacz.guns.api.event.common;

import net.neoforged.bus.api.Event;

/**
 * Stable event-poster facade while the optional KubeJS integration is unavailable for
 * Minecraft 1.21.10. Native NeoForge gun events still run normally.
 */
public interface KubeJSGunEventPoster<E extends Event> {
    default void postEventToKubeJS(E event) {
    }

    // 客户端事件应调用此方法
    default void postClientEventToKubeJS(E event) {
    }

    // 服务端事件应调用此方法
    default void postServerEventToKubeJS(E event) {
    }
}
