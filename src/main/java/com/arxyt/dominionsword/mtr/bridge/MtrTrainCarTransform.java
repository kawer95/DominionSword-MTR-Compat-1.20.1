package com.arxyt.dominionsword.mtr.bridge;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable car-local pose transform shared by authoritative and predicted entity collision. */
public record MtrTrainCarTransform(double x, double y, double z, double yaw, double pitch,
                                   double length, double width) {
    public static MtrTrainCarTransform of(MtrTrainSnapshot.CarPose car) {
        return new MtrTrainCarTransform(car.x(), car.y(), car.z(), car.yaw(), car.pitch(), car.length(), car.width());
    }

    public Vec3 toLocal(Vec3 world) {
        double dx = world.x - x, dy = world.y - y, dz = world.z - z;
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        double localX = dx * cosYaw - dz * sinYaw;
        double rotatedZ = dx * sinYaw + dz * cosYaw;
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        return new Vec3(localX, dy * cosPitch - rotatedZ * sinPitch,
                dy * sinPitch + rotatedZ * cosPitch);
    }

    public Vec3 toWorld(Vec3 local) {
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        double worldY = local.y * cosPitch + local.z * sinPitch;
        double rotatedZ = -local.y * sinPitch + local.z * cosPitch;
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        return new Vec3(x + local.x * cosYaw + rotatedZ * sinYaw, y + worldY,
                z - local.x * sinYaw + rotatedZ * cosYaw);
    }

    public AABB worldBounds() {
        double horizontal = Math.hypot(length * 0.5D, width * 0.5D) + 2D;
        double vertical = MtrTrainCollisionGeometry.TOP + Math.abs(Math.sin(pitch)) * length * 0.5D + 2D;
        return new AABB(x - horizontal, y - vertical, z - horizontal,
                x + horizontal, y + vertical, z + horizontal);
    }
}
