package com.tacz.guns.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tracks local presentation separately from physical shots included in authoritative ammo. */
public final class ShotPresentationTracker {
    private static final int MAX_TRIGGERS = 512;
    private static final long RETENTION_MS = 60_000;
    private final Map<Long, Trigger> triggers = new LinkedHashMap<>();
    private final Map<String, Shot> watermarks = new LinkedHashMap<>();
    private long issuedSequence = -1;

    public record Shot(long actionSeq, String instance, int shotIndex) { }
    private static final class Trigger {
        final long sequence, created, interval;
        final String instance;
        final boolean predicted, consumesAmmo;
        final int physical, visuals;
        final boolean[] presented;
        int allowed, confirmed;
        boolean cancelled, budgetActive = true;
        Trigger(long sequence, String instance, int physical, int visuals, long interval, long now, boolean predicted, boolean consumesAmmo) {
            this.sequence = sequence; this.instance = instance; this.physical = physical; this.visuals = visuals;
            this.interval = interval; this.created = now; this.predicted = predicted; this.consumesAmmo = consumesAmmo;
            this.allowed = physical; this.presented = new boolean[physical];
        }
    }

    public void begin(long sequence, String instance, int physical, int visuals, long interval, long now, boolean predicted, boolean consumesAmmo) {
        if (sequence < 0 || physical < 1 || physical > 16 || visuals < 1 || visuals > physical || interval < 0)
            throw new IllegalArgumentException("Invalid shot presentation plan");
        prune(now);
        issuedSequence = Math.max(issuedSequence, sequence);
        triggers.putIfAbsent(sequence, new Trigger(sequence, instance, physical, visuals, interval, now, predicted, consumesAmmo));
        while (triggers.size() > MAX_TRIGGERS) triggers.remove(triggers.keySet().iterator().next());
    }

    /** A known prediction is never replayed by an acknowledgement, even before its local deadline. */
    public boolean confirm(long sequence, String instance, int index, boolean visible) {
        Trigger trigger = triggers.get(sequence);
        if (trigger == null) return visible && (sequence < 0 || sequence > issuedSequence);
        if (!trigger.instance.equals(instance)) return false;
        trigger.confirmed = Math.max(trigger.confirmed, index + 1);
        if (trigger.predicted || trigger.cancelled || index < 0 || index >= trigger.presented.length) return false;
        if (trigger.presented[index]) return false;
        trigger.presented[index] = true;
        return visible;
    }

    public void result(long sequence, String instance, String status, int fired) {
        Trigger trigger = triggers.get(sequence);
        if (trigger == null || !trigger.instance.equals(instance) || status.equals("queued")) return;
        trigger.allowed = Math.max(0, Math.min(trigger.physical, fired));
        trigger.confirmed = Math.max(trigger.confirmed, trigger.allowed);
        if (!status.equals("complete") && trigger.allowed == 0) trigger.cancelled = true;
    }

    /** Returns at most one due visual. Very stale burst effects are suppressed, never played in a burst of callbacks. */
    public Shot poll(long now) {
        prune(now);
        for (Trigger trigger : triggers.values()) {
            if (!trigger.predicted || trigger.cancelled) continue;
            for (int index = 0; index < Math.min(trigger.visuals, trigger.allowed); index++) {
                if (trigger.presented[index]) continue;
                long due = trigger.created + index * trigger.interval;
                if (now < due) break;
                trigger.presented[index] = true;
                if (trigger.visuals == 1) {
                    // Double barrel: two physical rounds, one original shoot_burst animation/sound.
                    for (int covered = 1; covered < trigger.allowed; covered++) trigger.presented[covered] = true;
                }
                if (index > 0 && now - due > 100) continue;
                return new Shot(trigger.sequence, trigger.instance, index);
            }
        }
        return null;
    }

    public void snapshot(String instance, long sequence, int index) {
        if (sequence < 0 || index < 0) return;
        Shot old = watermarks.get(instance);
        if (old == null || sequence > old.actionSeq || sequence == old.actionSeq && index > old.shotIndex)
            watermarks.put(instance, new Shot(sequence, instance, index));
        while (watermarks.size() > MAX_TRIGGERS) watermarks.remove(watermarks.keySet().iterator().next());
    }

    /** Reserved counts protect against repeated prediction from the same delayed ammo snapshot. */
    public int reservedAmmo(String instance) { return outstanding(instance, true, true).size(); }

    public List<Shot> outstanding(String instance, boolean includeFuture) { return outstanding(instance, includeFuture, false); }

    private List<Shot> outstanding(String instance, boolean includeFuture, boolean ammoOnly) {
        List<Shot> result = new ArrayList<>();
        Shot watermark = watermarks.get(instance);
        for (Trigger trigger : triggers.values()) {
            if (!trigger.instance.equals(instance) || ammoOnly && !trigger.consumesAmmo || !trigger.predicted || !trigger.budgetActive) continue;
            for (int i = 0; i < trigger.allowed; i++) {
                if (watermark != null && (trigger.sequence < watermark.actionSeq || trigger.sequence == watermark.actionSeq && i <= watermark.shotIndex)) continue;
                if (includeFuture || trigger.presented[i]) result.add(new Shot(trigger.sequence, instance, i));
            }
        }
        return result;
    }

    /** Keep played tombstones and physical reservations until authority resolves them. */
    public void cancelFuture() { for (Trigger trigger : triggers.values()) { trigger.cancelled = true; trigger.budgetActive = false; } }
    public boolean contains(long sequence) { return triggers.containsKey(sequence); }
    public void reset() { triggers.clear(); watermarks.clear(); issuedSequence = -1; }
    private void prune(long now) { triggers.values().removeIf(trigger -> now - trigger.created > RETENTION_MS); }
}
