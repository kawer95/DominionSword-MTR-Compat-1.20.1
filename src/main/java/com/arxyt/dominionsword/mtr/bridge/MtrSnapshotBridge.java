package com.arxyt.dominionsword.mtr.bridge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.List;
import org.mtr.core.simulation.Simulator;

public final class MtrSnapshotBridge {
    private static final Map<String, MtrTrainSnapshot> LATEST = new ConcurrentHashMap<>();
    private static final Map<String, CachedRoute> ROUTES = new ConcurrentHashMap<>();
    private static final Map<String, Set<UUID>> MANAGED_RIDERS = new ConcurrentHashMap<>();
    private static final Map<String, Set<UUID>> APPLIED_RIDERS = new ConcurrentHashMap<>();
    private static final Set<String> FORCED_DEPARTURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> FORCED_DOORS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> COMPATIBILITY_TRIPS = ConcurrentHashMap.newKeySet();
    private static final Map<String, TrainControl> TRAIN_CONTROLS = new ConcurrentHashMap<>();

    private MtrSnapshotBridge() {}

    /** MTR core serializes dimensions as namespace/path; Minecraft uses namespace:path. */
    public static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank() || dimension.indexOf(':') >= 0) return dimension;
        int separator = dimension.indexOf('/');
        return separator <= 0 || separator >= dimension.length() - 1
                ? dimension : dimension.substring(0, separator) + ':' + dimension.substring(separator + 1);
    }

    public static String key(String dimension, long sidingId, long vehicleId) {
        return normalizeDimension(dimension) + '|' + sidingId + '|' + vehicleId;
    }

    public static void publish(MtrTrainSnapshot snapshot) {
        String dimension = normalizeDimension(snapshot.dimension());
        MtrTrainSnapshot normalized = dimension.equals(snapshot.dimension()) ? snapshot : new MtrTrainSnapshot(
                snapshot.capturedAt(), dimension, snapshot.sidingId(), snapshot.vehicleId(), snapshot.departureIndex(), snapshot.speed(),
                snapshot.railProgress(), snapshot.powerLevel(), snapshot.doorMultiplier(), snapshot.manual(),
                snapshot.onRoute(), snapshot.cars(), snapshot.path(), snapshot.stops(), snapshot.riders());
        LATEST.put(key(dimension, normalized.sidingId(), normalized.vehicleId()), normalized);
    }

    public static Map<String, MtrTrainSnapshot> latest() { return Map.copyOf(LATEST); }
    public static MtrTrainSnapshot get(String dimension, long sidingId, long vehicleId) {
        return LATEST.get(key(dimension, sidingId, vehicleId));
    }

    /** Publishes a desired state only; Vehicle.startUp is invoked later on MTR's simulation thread. */
    public static void forceDeparture(String dimension, long sidingId, long vehicleId, boolean requested) {
        String key = key(dimension, sidingId, vehicleId);
        if (requested) FORCED_DEPARTURES.add(key); else FORCED_DEPARTURES.remove(key);
    }

    public static boolean forceDepartureRequested(String dimension, long sidingId, long vehicleId) {
        return FORCED_DEPARTURES.contains(key(dimension, sidingId, vehicleId));
    }

    /** Forces the matching simulation vehicle's global door target until explicitly released. */
    public static void forceDoors(String dimension, long sidingId, long vehicleId, boolean requested) {
        String key = key(dimension, sidingId, vehicleId);
        if (requested) FORCED_DOORS.add(key); else FORCED_DOORS.remove(key);
    }

    public static boolean forceDoorsRequested(String dimension, long sidingId, long vehicleId) {
        return FORCED_DOORS.contains(key(dimension, sidingId, vehicleId));
    }

    /** Marks an untimetabled ATO trip so Siding does not delete it solely for departureIndex == -1. */
    public static void compatibilityTrip(long vehicleId, boolean active) {
        if (active) COMPATIBILITY_TRIPS.add(vehicleId); else COMPATIBILITY_TRIPS.remove(vehicleId);
    }

    public static boolean isCompatibilityTrip(long vehicleId) {
        return COMPATIBILITY_TRIPS.contains(vehicleId);
    }

    /** Minecraft-thread desired control state, consumed only by the matching MTR simulation vehicle. */
    public static void trainControl(String dimension, long sidingId, long vehicleId,
                                    boolean controlled, int targetPathIndex, boolean hold) {
        String key = key(dimension, sidingId, vehicleId);
        if (controlled) TRAIN_CONTROLS.put(key, new TrainControl(targetPathIndex, hold));
        else TRAIN_CONTROLS.remove(key);
    }

    public static TrainControl trainControl(String dimension, long sidingId, long vehicleId) {
        return TRAIN_CONTROLS.get(key(dimension, sidingId, vehicleId));
    }

    /** Publishes only immutable UUID state; MTR simulator state is changed later on its own simulation thread. */
    public static void managedRiders(String dimension, long sidingId, long vehicleId, Set<UUID> riders) {
        MANAGED_RIDERS.put(key(dimension, sidingId, vehicleId), Set.copyOf(riders));
    }

    /** Keeps MTR's rider-validity map aligned before Vehicle removes riders it considers stale. */
    public static void reconcileManagedRiders(String dimension, long sidingId, long vehicleId, Simulator simulator) {
        String key = key(dimension, sidingId, vehicleId);
        Set<UUID> desired = MANAGED_RIDERS.getOrDefault(key, Set.of());
        Set<UUID> previous = APPLIED_RIDERS.put(key, desired);
        if (previous != null) for (UUID uuid : previous) {
            if (!desired.contains(uuid) && simulator.isRiding(uuid, vehicleId)) simulator.stopRiding(uuid);
        }
        for (UUID uuid : desired) simulator.ride(uuid, vehicleId);
        if (desired.isEmpty()) {
            MANAGED_RIDERS.remove(key, desired);
            APPLIED_RIDERS.remove(key, desired);
        }
    }
    public static RouteData route(String key, long signature, Supplier<RouteData> factory) {
        CachedRoute cached = ROUTES.get(key);
        if (cached != null && cached.signature == signature) return cached.data;
        RouteData data = factory.get();
        ROUTES.put(key, new CachedRoute(signature, data));
        return data;
    }
    public static void clear() {
        LATEST.clear();
        ROUTES.clear();
        MANAGED_RIDERS.clear();
        APPLIED_RIDERS.clear();
        FORCED_DEPARTURES.clear();
        FORCED_DOORS.clear();
        COMPATIBILITY_TRIPS.clear();
        TRAIN_CONTROLS.clear();
    }

    public record RouteData(List<MtrTrainSnapshot.PathPart> path, List<MtrTrainSnapshot.Stop> stops) {}
    public record TrainControl(int targetPathIndex, boolean hold) {}
    private record CachedRoute(long signature, RouteData data) {}
}
