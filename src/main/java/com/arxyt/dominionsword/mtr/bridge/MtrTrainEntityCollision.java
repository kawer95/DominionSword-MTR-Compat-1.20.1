package com.arxyt.dominionsword.mtr.bridge;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Continuous entity-versus-hull solver in car-local coordinates, shared by server and client prediction. */
public final class MtrTrainEntityCollision {
    private static final double EPSILON = 1.0E-4D;

    private MtrTrainEntityCollision() {}

    public static Resolution resolve(Vec3 previousFeet, Vec3 feet, double width, double height, List<AABB> shell) {
        double radius = Math.max(0.05D, width * 0.5D);
        double safeHeight = Math.max(0.05D, height);
        if (collides(previousFeet, radius, safeHeight, shell)) {
            return resolveOverlap(feet, radius, safeHeight, shell);
        }

        Vec3 movement = feet.subtract(previousFeet);
        Vec3 resolved = previousFeet;
        boolean collided = false;
        AxisClip vertical = clipAxis(resolved, movement.y, 1, radius, safeHeight, shell);
        resolved = vertical.position();
        collided |= vertical.collided();
        AxisClip x = clipAxis(resolved, movement.x, 0, radius, safeHeight, shell);
        resolved = x.position();
        collided |= x.collided();
        AxisClip z = clipAxis(resolved, movement.z, 2, radius, safeHeight, shell);
        resolved = z.position();
        collided |= z.collided();

        Resolution overlap = resolveOverlap(resolved, radius, safeHeight, shell);
        return new Resolution(overlap.position(), collided || overlap.collided());
    }

    private static AxisClip clipAxis(Vec3 start, double amount, int axis, double radius,
                                     double height, List<AABB> shell) {
        if (Math.abs(amount) < 1.0E-9D) return new AxisClip(start, false);
        int steps = Math.max(1, (int) Math.ceil(Math.abs(amount) / 0.08D));
        double safeFraction = 0D;
        for (int step = 1; step <= steps; step++) {
            double fraction = step / (double) steps;
            Vec3 probe = moveAxis(start, amount * fraction, axis);
            if (!collides(probe, radius, height, shell)) {
                safeFraction = fraction;
                continue;
            }
            double blockedFraction = fraction;
            for (int iteration = 0; iteration < 12; iteration++) {
                double middle = (safeFraction + blockedFraction) * 0.5D;
                if (collides(moveAxis(start, amount * middle, axis), radius, height, shell)) blockedFraction = middle;
                else safeFraction = middle;
            }
            double epsilon = Math.copySign(EPSILON, -amount);
            return new AxisClip(moveAxis(start, amount * safeFraction + epsilon, axis), true);
        }
        return new AxisClip(moveAxis(start, amount, axis), false);
    }

    private static Vec3 moveAxis(Vec3 start, double amount, int axis) {
        return switch (axis) {
            case 0 -> start.add(amount, 0, 0);
            case 1 -> start.add(0, amount, 0);
            default -> start.add(0, 0, amount);
        };
    }

    private static Resolution resolveOverlap(Vec3 feet, double radius, double height, List<AABB> shell) {
        Vec3 resolved = feet;
        boolean collided = false;
        for (int pass = 0; pass < 16; pass++) {
            Vec3 best = null;
            for (AABB solid : shell) {
                Vec3 push = smallestPush(resolved, radius, height, solid);
                if (push != null && (best == null || push.lengthSqr() < best.lengthSqr())) best = push;
            }
            if (best == null) break;
            resolved = resolved.add(best);
            collided = true;
        }
        return new Resolution(resolved, collided);
    }

    private static Vec3 smallestPush(Vec3 feet, double radius, double height, AABB solid) {
        double minX = feet.x - radius, maxX = feet.x + radius;
        double minY = feet.y, maxY = feet.y + height;
        double minZ = feet.z - radius, maxZ = feet.z + radius;
        if (maxX <= solid.minX || minX >= solid.maxX || maxY <= solid.minY || minY >= solid.maxY
                || maxZ <= solid.minZ || minZ >= solid.maxZ) return null;
        return List.of(
                        new Vec3(solid.minX - maxX - EPSILON, 0, 0), new Vec3(solid.maxX - minX + EPSILON, 0, 0),
                        new Vec3(0, solid.minY - maxY - EPSILON, 0), new Vec3(0, solid.maxY - minY + EPSILON, 0),
                        new Vec3(0, 0, solid.minZ - maxZ - EPSILON), new Vec3(0, 0, solid.maxZ - minZ + EPSILON))
                .stream().min(Comparator.comparingDouble(Vec3::lengthSqr)).orElse(null);
    }

    private static boolean collides(Vec3 feet, double radius, double height, List<AABB> shell) {
        AABB entity = new AABB(feet.x - radius, feet.y, feet.z - radius,
                feet.x + radius, feet.y + height, feet.z + radius);
        for (AABB solid : shell) if (entity.intersects(solid)) return true;
        return false;
    }

    private record AxisClip(Vec3 position, boolean collided) {}
    public record Resolution(Vec3 position, boolean collided) {}
}
