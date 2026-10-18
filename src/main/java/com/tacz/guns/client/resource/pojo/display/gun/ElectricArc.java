package com.tacz.guns.client.resource.pojo.display.gun;

import com.google.gson.annotations.SerializedName;

/** Optional client-only trail. These values never affect projectile physics or damage. */
public final class ElectricArc {
    private double spacing = 0.10;
    private double jitter = 0.16;
    private int branches = 2;
    @SerializedName("branch_length")
    private double branchLength = 0.35;
    @SerializedName("life_time")
    private int lifeTime = 3;
    @SerializedName("max_particles")
    private int maxParticles = 160;

    public double spacing() { return bounded(spacing, 0.025, 2, 0.10); }
    public double jitter() { return bounded(jitter, 0, 1, 0.16); }
    public int branches() { return Math.max(0, Math.min(3, branches)); }
    public double branchLength() { return bounded(branchLength, 0, 1, 0.35); }
    public int lifeTime() { return Math.max(1, Math.min(10, lifeTime)); }
    public int maxParticles() { return Math.max(1, Math.min(256, maxParticles)); }

    private static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
