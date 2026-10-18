package com.tacz.guns.client.particle;

import java.lang.ref.WeakReference;

/** A shared world-tick budget, including different guns and native/Paper visual entities. */
public final class TrailParticleBudget {
    private final int maximum;
    private WeakReference<Object> world = new WeakReference<>(null);
    private long tick = Long.MIN_VALUE;
    private int used;

    public TrailParticleBudget(int maximum) { this.maximum = Math.max(0, maximum); }

    public int remaining(Object currentWorld, long currentTick) {
        if (world.get() != currentWorld || tick != currentTick) {
            world = new WeakReference<>(currentWorld);
            tick = currentTick;
            used = 0;
        }
        return maximum - used;
    }

    public boolean claim(Object currentWorld, long currentTick) {
        if (remaining(currentWorld, currentTick) <= 0) return false;
        used++;
        return true;
    }
}
