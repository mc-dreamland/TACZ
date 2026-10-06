package com.tacz.guns.bridge;

/** Isolated magazine/chamber view; never writes to inventory or grants a server shot. */
public record PresentationAmmo(int ammo, boolean chamber) {
    public PresentationAmmo consume(String bolt, boolean feedAfterManual) {
        int remaining = ammo;
        boolean loaded = chamber;
        if (bolt.equals("manual_action")) loaded = false;
        else if (remaining > 0) remaining--;
        else if (!bolt.equals("open_bolt")) loaded = false;
        if (bolt.equals("closed_bolt") && remaining > 0 && !loaded) { remaining--; loaded = true; }
        if (feedAfterManual && remaining > 0) { remaining--; loaded = true; }
        return new PresentationAmmo(Math.max(0, remaining), loaded);
    }
}
