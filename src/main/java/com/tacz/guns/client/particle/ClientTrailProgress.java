package com.tacz.guns.client.particle;

import net.minecraft.world.phys.Vec3;

import java.util.function.BiFunction;

/** Tracks only visual progress: corrections and terminal packets must not replay an old segment. */
public final class ClientTrailProgress {
    private Vec3 lastEnd;
    private boolean blocked;

    public record Segment(Vec3 start, Vec3 end) { }

    public Segment advance(Vec3 initial, Vec3 requestedEnd, Vec3 direction,
                           BiFunction<Vec3, Vec3, Vec3> clip) {
        if (blocked || !ElectricArcPath.finite(initial) || !ElectricArcPath.finite(requestedEnd)) return null;
        Vec3 start = lastEnd == null ? initial : lastEnd;
        Vec3 delta = requestedEnd.subtract(start);
        if (delta.lengthSqr() < 1.0e-10 || delta.lengthSqr() > 64 * 64
                || direction.lengthSqr() > 1.0e-10 && delta.dot(direction) <= 0) return null;
        Vec3 end = clip.apply(start, requestedEnd);
        if (!ElectricArcPath.finite(end)) return null;
        blocked = end.distanceToSqr(requestedEnd) > 1.0e-8;
        lastEnd = end;
        return start.distanceToSqr(end) < 1.0e-10 ? null : new Segment(start, end);
    }

    public static boolean beyond(Vec3 point, Vec3 end, Vec3 direction) {
        return point.subtract(end).dot(direction) > 1.0e-5;
    }
}
