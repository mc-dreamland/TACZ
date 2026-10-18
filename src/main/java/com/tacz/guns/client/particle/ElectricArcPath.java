package com.tacz.guns.client.particle;

import com.tacz.guns.client.resource.pojo.display.gun.ElectricArc;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Bounded cosmetic geometry; the caller supplies an already clipped projectile segment. */
public final class ElectricArcPath {
    private ElectricArcPath() { }

    public static List<Vec3> points(Vec3 start, Vec3 end, ElectricArc config, DoubleSupplier random, int budget) {
        int limit = Math.max(0, Math.min(config.maxParticles(), budget));
        List<Vec3> points = new ArrayList<>(limit);
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (limit == 0 || !finite(start) || !finite(end) || length < 1.0e-6 || length > 64) return points;
        Vec3 forward = delta.scale(1 / length);
        Vec3 right = forward.cross(Math.abs(forward.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(forward).normalize();
        int count = Math.max(2, Math.min(128, (int) Math.ceil(length / .5)));
        List<Vec3> nodes = new ArrayList<>(count + 1);
        nodes.add(start);
        for (int i = 1; i <= count; i++) {
            double t = (double) i / count;
            double amplitude = Math.min(config.jitter(), length * .15) * Math.sin(t * Math.PI);
            Vec3 point = i == count ? end : start.lerp(end, t)
                    .add(right.scale(signed(random) * amplitude)).add(up.scale(signed(random) * amplitude));
            line(points, nodes.get(i - 1), point, config.spacing(), limit - 1);
            nodes.add(point);
        }
        if (length > .75) {
            for (int branch = 0; branch < config.branches() && points.size() < limit - 1; branch++) {
                int index = Math.max(0, Math.min(count - 1, (int) (count * (branch + 1.0) / (config.branches() + 1))));
                Vec3 a = nodes.get(index), b = nodes.get(Math.min(count, index + 1));
                double side = (branch & 1) == 0 ? -1 : 1;
                Vec3 middle = a.lerp(b, .5).add(right.scale(side * config.branchLength()))
                        .add(up.scale(signed(random) * config.branchLength() * .5));
                line(points, a, middle, config.spacing(), limit - 1);
                line(points, middle, b, config.spacing(), limit - 1);
            }
        }
        // Always retain the clipped endpoint, even when a very dense configuration hits its cap.
        if (points.isEmpty() || points.get(points.size() - 1).distanceToSqr(end) > 1.0e-12) points.add(end);
        return points;
    }

    private static void line(List<Vec3> result, Vec3 from, Vec3 to, double spacing, int limit) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / spacing));
        for (int i = 1; i <= steps && result.size() < limit; i++) result.add(from.lerp(to, (double) i / steps));
    }

    private static double signed(DoubleSupplier random) { return random.getAsDouble() * 2 - 1; }

    public static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
