package com.tacz.guns.client.renderer.entity;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** A tracer's displayed endpoints; the caller orients the mesh along this segment. */
record TracerSegment(Vec3 start, Vec3 end) {
    private static final double CONVERGENCE_DISTANCE = 50;

    static TracerSegment create(Vec3 bulletOrigin, Vec3 head, Vec3 velocity, double length,
                                @Nullable Vec3 muzzle, boolean terminal) {
        double travelled = head.distanceTo(bulletOrigin);
        Vec3 tail = head.subtract(velocity.normalize().scale(Math.min(length, travelled)));
        if (muzzle == null) return new TracerSegment(tail, head);
        Vec3 offset = muzzle.subtract(bulletOrigin);
        Vec3 start = display(tail, bulletOrigin, offset);
        // Confirmed impacts stay on the collision surface, including very short shotgun shots.
        Vec3 end = terminal ? head : display(head, bulletOrigin, offset);
        return new TracerSegment(start, end);
    }

    private static Vec3 display(Vec3 point, Vec3 origin, Vec3 offset) {
        double weight = Math.max(0, 1 - point.distanceTo(origin) / CONVERGENCE_DISTANCE);
        return point.add(offset.scale(weight));
    }
}
