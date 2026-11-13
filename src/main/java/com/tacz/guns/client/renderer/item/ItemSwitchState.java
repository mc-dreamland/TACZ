package com.tacz.guns.client.renderer.item;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** A serial handover: outgoing snapshot first, latest selection second. */
public final class ItemSwitchState<T> {
    private final UnaryOperator<T> snapshot;
    private T outgoing;
    private long deadline;
    private boolean managed;
    private boolean pendingDraw;

    public ItemSwitchState(UnaryOperator<T> snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    /** Returns true only when a new outgoing animation must be started. */
    public boolean request(T previous, long durationMs, long nowMs) {
        // No frame has presented the next selection yet. Retarget it without
        // restarting the old animation or extending its deadline.
        if (outgoing != null || pendingDraw) {
            pendingDraw = true;
            return false;
        }
        managed = true;
        pendingDraw = true;
        if (durationMs > 0) {
            outgoing = Objects.requireNonNull(snapshot.apply(previous));
            deadline = nowMs + durationMs;
        }
        return true;
    }

    public T resolve(T selected, long nowMs) {
        if (outgoing != null && nowMs < deadline) {
            return outgoing;
        }
        outgoing = null;
        return selected;
    }

    /** Kept until resolve commits the handover, even if a frame crosses the deadline. */
    public boolean isKeeping() {
        return outgoing != null;
    }

    public boolean isManaged() {
        return managed;
    }

    public boolean takePendingDraw() {
        if (outgoing != null || !pendingDraw) {
            return false;
        }
        pendingDraw = false;
        return true;
    }

    public void clear() {
        outgoing = null;
        deadline = 0;
        managed = false;
        pendingDraw = false;
    }
}
