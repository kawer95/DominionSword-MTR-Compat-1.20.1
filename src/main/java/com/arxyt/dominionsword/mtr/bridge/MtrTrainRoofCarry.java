package com.arxyt.dominionsword.mtr.bridge;

import net.minecraft.world.phys.Vec3;

/** Shared frigatemod-style support probe for the flat top surface of an MTR car. */
public final class MtrTrainRoofCarry {
    private static final double BELOW_TOLERANCE = 0.10D;
    private static final double ABOVE_TOLERANCE = 0.40D;
    private static final double CONTACT_OFFSET = 0.02D;

    private MtrTrainRoofCarry() {}

    public static Vec3 contact(Vec3 localFeet, Vec3 lastLocalFeet, double carWidth, double carLength,
                               double entityWidth, boolean ascending) {
        if (ascending || !overRoof(localFeet, carWidth, carLength, entityWidth)) return null;
        double roof = MtrTrainCollisionGeometry.TOP;
        double delta = localFeet.y - roof;
        boolean supported = delta >= -BELOW_TOLERANCE && delta <= ABOVE_TOLERANCE;
        boolean crossed = !supported && lastLocalFeet != null && localFeet.y < lastLocalFeet.y
                && roof >= localFeet.y - BELOW_TOLERANCE && roof <= lastLocalFeet.y + BELOW_TOLERANCE;
        return supported || crossed ? new Vec3(localFeet.x, roof + CONTACT_OFFSET, localFeet.z) : null;
    }

    private static boolean overRoof(Vec3 localFeet, double carWidth, double carLength, double entityWidth) {
        double radius = entityWidth * 0.5D;
        double halfWidth = Math.max(0.6D, carWidth * 0.5D);
        double halfLength = Math.max(1.0D, carLength * 0.5D);
        return localFeet.x + radius >= -halfWidth && localFeet.x - radius <= halfWidth
                && localFeet.z + radius >= -halfLength && localFeet.z - radius <= halfLength;
    }
}
