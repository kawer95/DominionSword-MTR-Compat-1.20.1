package com.arxyt.dominionsword.mtr.bridge;

import java.util.List;
import java.util.UUID;

/** Immutable data copied from MTR's simulation thread. Contains no Minecraft world objects. */
public record MtrTrainSnapshot(
        long capturedAt, String dimension, long sidingId, long vehicleId, long departureIndex,
        double speed, double railProgress, int powerLevel, int doorMultiplier,
        boolean manual, boolean onRoute, List<CarPose> cars, List<PathPart> path,
        List<Stop> stops, List<Rider> riders) {

    public record Point(double x, double y, double z) {}
    public record CarPose(double x, double y, double z, double yaw, double pitch, double length, double width) {}
    public record PathPart(int index, long platformId, double startProgress, double endProgress,
                           long dwellTime, List<Point> points) {}
    public record Stop(long platformId, int pathIndex, double progress, String name, Point point) {
        public String key() { return platformId + ":" + pathIndex; }
    }
    public record Rider(UUID uuid, long car, double x, double y, double z, boolean gangway,
                        boolean driver, boolean accelerate, boolean brake, boolean doors,
                        boolean toggleAto, boolean doorOverride) {}
}
