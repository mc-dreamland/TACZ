package com.tacz.guns.paper.gameplay;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Server-side approximation of head, torso (including arms), and legs; no client bones are trusted. */
public final class HitZones {
    private static final double PADDING = .015;
    private static final double EPSILON = 1.0e-9;

    private HitZones() {}

    public enum Region {
        HEAD("head"), TORSO("torso"), LEGS("legs");
        private final String id;
        Region(String id) { this.id = id; }
        public String id() { return id; }
    }

    public enum Shape { HUMANOID, GENERIC, BODY }

    public record Profile(Shape shape, double headWidth, double headHeight, double legsHeight) {
        public static final Profile DEFAULT_HUMANOID = new Profile(Shape.HUMANOID, .85, .25, .45);
        public static final Profile DEFAULT_GENERIC = new Profile(Shape.GENERIC, .85, .25, 0);
        public static final Profile BODY = new Profile(Shape.BODY, 0, 0, 0);

        public Profile {
            Objects.requireNonNull(shape, "shape");
            for (double value : new double[]{headWidth, headHeight, legsHeight}) {
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("Hit zone proportions must be finite and between 0 and 1");
                }
            }
        }
    }

    public record Hit(Region region, Vector point, double distance) {}

    /** Pose also covers plugin-forced crawling, for which Player.isSwimming() may be false. */
    public static boolean horizontal(Player player) {
        Pose pose = player.getPose();
        return pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SPIN_ATTACK;
    }

    /** Returns the first volume entered, including a zero-distance hit when starting inside one. */
    public static Hit trace(LivingEntity entity, Vector origin, Vector normalizedDirection,
                            double maxDistance, Profile profile) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(profile, "profile");
        if (!finite(origin) || !finite(normalizedDirection) || !Double.isFinite(maxDistance) || maxDistance < 0) return null;
        double magnitude = normalizedDirection.length();
        if (!Double.isFinite(magnitude) || magnitude < EPSILON) return null;
        Vector direction = normalizedDirection.clone().multiply(1 / magnitude);
        BoundingBox bounds = entity.getBoundingBox();
        if (bounds == null || !valid(bounds)) return null;
        List<Volume> volumes = new ArrayList<>(8);
        Vector localOrigin = origin, localDirection = direction;
        if (profile.shape == Shape.BODY) {
            volumes.add(new Volume(Region.TORSO, Box.of(bounds).expand(PADDING)));
        } else if (entity instanceof Player player && horizontal(player)) {
            Frame frame = horizontalFrame(player, bounds);
            if (frame == null) return null;
            localOrigin = frame.local(origin.clone().subtract(frame.origin));
            localDirection = frame.local(direction);
            double width = Math.max(bounds.getWidthX(), bounds.getWidthZ());
            double length = 1.87625 * frame.scale;
            double neck = length * (1 - profile.headHeight);
            double knees = profile.shape == Shape.HUMANOID ? Math.min(neck, length * profile.legsHeight) : 0;
            addHumanoid(volumes, new Box(-width / 2, 0, -width / 2, width / 2, length, width / 2),
                    neck, length, knees, profile.headWidth, profile.headHeight > 0 && profile.headWidth > 0);
        } else {
            upright(entity, bounds, profile, volumes);
        }
        Volume first = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Volume volume : volumes) {
            double entry = volume.box.entry(localOrigin, localDirection, maxDistance);
            // Shared surfaces have no padding. At an exact tie, use the same region independent
            // of iteration order; a later internal head intersection can never replace an entry.
            if (Double.isFinite(entry) && (entry < distance - EPSILON
                    || Math.abs(entry - distance) <= EPSILON && (first == null || volume.region.ordinal() < first.region.ordinal()))) {
                first = volume;
                distance = entry;
            }
        }
        return first == null ? null : new Hit(first.region, origin.clone().add(direction.multiply(distance)), distance);
    }

    private static void upright(LivingEntity entity, BoundingBox bounds, Profile profile, List<Volume> volumes) {
        Box body = Box.of(bounds);
        Location eyeLocation = entity.getEyeLocation();
        if (profile.headWidth == 0 || profile.headHeight == 0 || eyeLocation == null) {
            double legs = profile.shape == Shape.HUMANOID ? body.y0 + bounds.getHeight() * profile.legsHeight : body.y0;
            addHumanoid(volumes, body, body.y1, body.y1, legs, 0, false);
            return;
        }
        double height = bounds.getHeight() * profile.headHeight;
        // Eye height locates the finite head volume, including babies and crouching targets.
        double lower = clamp(eyeLocation.getY() - height * .4, body.y0, body.y1);
        double upper = clamp(eyeLocation.getY() + height * .6, lower, body.y1);
        if (profile.shape == Shape.HUMANOID) {
            double legs = Math.min(lower, body.y0 + bounds.getHeight() * profile.legsHeight);
            addHumanoid(volumes, body, lower, upper, legs, profile.headWidth, upper - lower > EPSILON);
            return;
        }
        // A generic creature's forward, eye-height head remains bounded by its physical AABB.
        // Move it to the facing edge so frontal shots can reach it; the rest stays torso.
        double halfX = bounds.getWidthX() * profile.headWidth / 2;
        double halfZ = bounds.getWidthZ() * profile.headWidth / 2;
        double yaw = Math.toRadians(entity.getBodyYaw());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double x = clamp(eyeLocation.getX(), body.x0 + halfX, body.x1 - halfX);
        double z = clamp(eyeLocation.getZ(), body.z0 + halfZ, body.z1 - halfZ);
        double shift = Math.min(toEdge(x, forwardX, body.x0 + halfX, body.x1 - halfX),
                toEdge(z, forwardZ, body.z0 + halfZ, body.z1 - halfZ));
        x += forwardX * shift;
        z += forwardZ * shift;
        Box outer = body.expand(PADDING);
        Box head = new Box(outerEdge(x - halfX, body.x0, outer.x0), outerEdge(lower, body.y0, outer.y0), outerEdge(z - halfZ, body.z0, outer.z0),
                outerEdge(x + halfX, body.x1, outer.x1), outerEdge(upper, body.y1, outer.y1), outerEdge(z + halfZ, body.z1, outer.z1));
        if (!head.valid()) { volumes.add(new Volume(Region.TORSO, outer)); return; }
        volumes.add(new Volume(Region.HEAD, head));
        // Disjoint subtraction avoids an enclosing torso box hiding every generic head hit.
        add(volumes, Region.TORSO, new Box(outer.x0, outer.y0, outer.z0, head.x0, outer.y1, outer.z1));
        add(volumes, Region.TORSO, new Box(head.x1, outer.y0, outer.z0, outer.x1, outer.y1, outer.z1));
        add(volumes, Region.TORSO, new Box(head.x0, outer.y0, outer.z0, head.x1, head.y0, outer.z1));
        add(volumes, Region.TORSO, new Box(head.x0, head.y1, outer.z0, head.x1, outer.y1, outer.z1));
        add(volumes, Region.TORSO, new Box(head.x0, head.y0, outer.z0, head.x1, head.y1, head.z0));
        add(volumes, Region.TORSO, new Box(head.x0, head.y0, head.z1, head.x1, head.y1, outer.z1));
    }

    private static double outerEdge(double value, double original, double padded) {
        return Math.abs(value - original) < EPSILON ? padded : value;
    }

    private static double toEdge(double center, double forward, double minimum, double maximum) {
        if (Math.abs(forward) < EPSILON) return Double.POSITIVE_INFINITY;
        return (forward > 0 ? maximum - center : minimum - center) / forward;
    }

    /** Axial Y is height when upright, and the feet-to-head direction when horizontal. */
    private static void addHumanoid(List<Volume> volumes, Box body, double neck, double crown, double legs,
                                    double headWidth, boolean hasHead) {
        double torsoTop = hasHead ? neck : body.y1;
        double torsoPadding = hasHead ? 0 : PADDING;
        if (legs > body.y0 + EPSILON) add(volumes, Region.LEGS, new Box(body.x0 - PADDING, body.y0 - PADDING, body.z0 - PADDING,
                body.x1 + PADDING, legs, body.z1 + PADDING));
        add(volumes, Region.TORSO, new Box(body.x0 - PADDING, legs <= body.y0 + EPSILON ? body.y0 - PADDING : legs,
                body.z0 - PADDING, body.x1 + PADDING, torsoTop + torsoPadding, body.z1 + PADDING));
        if (hasHead) {
            double x = (body.x0 + body.x1) / 2, z = (body.z0 + body.z1) / 2;
            double halfX = (body.x1 - body.x0) * headWidth / 2;
            double halfZ = (body.z1 - body.z0) * headWidth / 2;
            add(volumes, Region.HEAD, new Box(x - halfX - PADDING, neck, z - halfZ - PADDING,
                    x + halfX + PADDING, crown + PADDING, z + halfZ + PADDING));
        }
    }

    private static Frame horizontalFrame(Player player, BoundingBox bounds) {
        Location location = player.getLocation();
        if (location == null) return null;
        double yaw = Math.toRadians(player.getBodyYaw());
        boolean swimming = player.getPose() == Pose.SWIMMING;
        double pitch = Math.toRadians(swimming && !player.isInWater() ? 0 : location.getPitch());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
        Vector up = forward.clone().multiply(Math.cos(pitch)).add(new Vector(0, -Math.sin(pitch), 0));
        Vector normal = forward.clone().multiply(Math.sin(pitch)).add(new Vector(0, Math.cos(pitch), 0));
        double scale = Math.max(bounds.getWidthX(), bounds.getWidthZ()) / .6;
        Vector origin = location.toVector();
        // The client swim/crawl renderer translates by (0,-1,.3) after rotation; elytra
        // and spin attacks do not. Keep that distinction while omitting animation interpolation.
        if (swimming) origin.add(up.clone().multiply(-scale)).add(normal.clone().multiply(.3 * scale));
        return new Frame(origin, right, up, normal, scale);
    }

    private static void add(List<Volume> volumes, Region region, Box box) {
        if (box.valid()) volumes.add(new Volume(region, box));
    }

    private static boolean finite(Vector vector) {
        return vector != null && Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }

    private static boolean valid(BoundingBox box) {
        return finite(box.getMin()) && finite(box.getMax()) && box.getWidthX() > EPSILON
                && box.getWidthZ() > EPSILON && box.getHeight() > EPSILON;
    }

    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }

    private record Frame(Vector origin, Vector right, Vector up, Vector normal, double scale) {
        Vector local(Vector vector) { return new Vector(vector.dot(right), vector.dot(up), vector.dot(normal)); }
    }

    private record Volume(Region region, Box box) {}

    private record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
        static Box of(BoundingBox box) { return new Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()); }
        Box expand(double padding) { return new Box(x0 - padding, y0 - padding, z0 - padding, x1 + padding, y1 + padding, z1 + padding); }
        boolean valid() { return x1 - x0 > EPSILON && y1 - y0 > EPSILON && z1 - z0 > EPSILON; }

        double entry(Vector origin, Vector direction, double maxDistance) {
            double near = 0, far = maxDistance;
            double[] starts = {origin.getX(), origin.getY(), origin.getZ()};
            double[] deltas = {direction.getX(), direction.getY(), direction.getZ()};
            double[] minimums = {x0, y0, z0}, maximums = {x1, y1, z1};
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(deltas[axis]) < EPSILON) {
                    if (starts[axis] < minimums[axis] - EPSILON || starts[axis] > maximums[axis] + EPSILON) return Double.POSITIVE_INFINITY;
                    continue;
                }
                double first = (minimums[axis] - starts[axis]) / deltas[axis];
                double second = (maximums[axis] - starts[axis]) / deltas[axis];
                near = Math.max(near, Math.min(first, second));
                far = Math.min(far, Math.max(first, second));
                if (near > far + EPSILON) return Double.POSITIVE_INFINITY;
            }
            return near <= maxDistance + EPSILON ? Math.min(near, maxDistance) : Double.POSITIVE_INFINITY;
        }
    }
}
