package com.arxyt.dominionsword.mtr.bridge;

import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared local-space shell generator used by both server collision and client debug rendering. */
public final class MtrTrainCollisionGeometry {
    public static final double BOTTOM = -0.40D;
    public static final double TOP = 3.58D;
    private static final double EPSILON = 1.0E-4D;
    private static final double WALL = 0.16D;
    public static final double FLOOR_TOP = 0.72D;
    private static final double ROOF_BOTTOM = 3.20D;

    private MtrTrainCollisionGeometry() {}

    public static List<AABB> shell(double width, double length, boolean doorsOpen, List<DoorOpening> reported) {
        double halfWidth = Math.max(0.6D, width * 0.5D);
        double halfLength = Math.max(1.0D, length * 0.5D);
        List<AABB> boxes = new ArrayList<>();
        boxes.add(new AABB(-halfWidth, BOTTOM, -halfLength, halfWidth, FLOOR_TOP, halfLength));
        boxes.add(new AABB(-halfWidth, ROOF_BOTTOM, -halfLength, halfWidth, TOP, halfLength));
        boxes.add(new AABB(-halfWidth, FLOOR_TOP, -halfLength, halfWidth, ROOF_BOTTOM, -halfLength + WALL));
        boxes.add(new AABB(-halfWidth, FLOOR_TOP, halfLength - WALL, halfWidth, ROOF_BOTTOM, halfLength));

        List<DoorOpening> active = doorsOpen ? reported : List.of();
        List<Interval> fallback = doorsOpen && active.isEmpty() ? fallbackDoorways(halfLength) : List.of();
        addSide(boxes, halfWidth, -1, -halfLength, halfLength, active.isEmpty() ? fallback
                : active.stream().filter(door -> door.side() < 0).map(door -> new Interval(door.minZ(), door.maxZ())).toList());
        addSide(boxes, halfWidth, 1, -halfLength, halfLength, active.isEmpty() ? fallback
                : active.stream().filter(door -> door.side() > 0).map(door -> new Interval(door.minZ(), door.maxZ())).toList());
        return List.copyOf(boxes);
    }

    private static void addSide(List<AABB> boxes, double halfWidth, int side, double minimumZ,
                                double maximumZ, List<Interval> openings) {
        List<Interval> sorted = openings.stream().map(opening -> new Interval(
                        Math.max(minimumZ, opening.minimum() - 0.08D), Math.min(maximumZ, opening.maximum() + 0.08D)))
                .filter(opening -> opening.maximum() > opening.minimum()).sorted(Comparator.comparingDouble(Interval::minimum)).toList();
        double cursor = minimumZ;
        for (Interval opening : sorted) {
            addSideSegment(boxes, halfWidth, side, cursor, opening.minimum());
            cursor = Math.max(cursor, opening.maximum());
        }
        addSideSegment(boxes, halfWidth, side, cursor, maximumZ);
    }

    private static void addSideSegment(List<AABB> boxes, double halfWidth, int side, double minimumZ, double maximumZ) {
        if (maximumZ - minimumZ <= EPSILON) return;
        if (side < 0) boxes.add(new AABB(-halfWidth, FLOOR_TOP, minimumZ, -halfWidth + WALL, ROOF_BOTTOM, maximumZ));
        else boxes.add(new AABB(halfWidth - WALL, FLOOR_TOP, minimumZ, halfWidth, ROOF_BOTTOM, maximumZ));
    }

    private static List<Interval> fallbackDoorways(double halfLength) {
        double usable = Math.max(0, halfLength - 2D);
        if (usable < 0.55D) return List.of(new Interval(-0.65D, 0.65D));
        int count = Math.max(1, Math.min(4, (int) Math.round(halfLength * 2 / 7D)));
        List<Interval> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double center = count == 1 ? 0 : -usable + index * usable * 2 / (count - 1D);
            result.add(new Interval(Math.max(-halfLength, center - 0.725D), Math.min(halfLength, center + 0.725D)));
        }
        return result;
    }

    public record DoorOpening(int side, double minZ, double maxZ) {}
    private record Interval(double minimum, double maximum) {}
}
