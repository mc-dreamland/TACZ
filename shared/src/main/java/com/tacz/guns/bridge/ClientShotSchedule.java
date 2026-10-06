package com.tacz.guns.bridge;

/** A single committed input with a monotonic local cadence, independent of network replies. */
public final class ClientShotSchedule {
    public static final long PREPARE_WINDOW_MS = 50;
    private boolean hasDeadline;
    private long nextDeadline;
    private boolean pending;
    private long pendingDeadline;

    public boolean prepare(long now) {
        if (pending || remaining(now) >= PREPARE_WINDOW_MS) return false;
        // Preserve small tick/frame lateness, but never replay shots missed during a pause.
        pendingDeadline = hasDeadline && now - nextDeadline < PREPARE_WINDOW_MS ? nextDeadline : now;
        pending = true;
        return true;
    }

    public boolean pending() { return pending; }
    public boolean due(long now) { return pending && now >= pendingDeadline; }
    public long remaining(long now) { return hasDeadline ? Math.max(0, nextDeadline - now) : 0; }

    public void sent(long now, long interval) {
        if (!due(now)) throw new IllegalStateException("No shot is ready to send");
        if (interval <= 0) throw new IllegalArgumentException("Shot interval must be positive");
        long base = now - pendingDeadline < PREPARE_WINDOW_MS ? pendingDeadline : now;
        nextDeadline = base + interval;
        hasDeadline = true;
        pending = false;
    }

    /** Cancel the prepared input without bypassing a previous shot's cooldown. */
    public void cancel() { pending = false; }

    public void reset() {
        hasDeadline = false;
        nextDeadline = 0;
        pending = false;
        pendingDeadline = 0;
    }
}
