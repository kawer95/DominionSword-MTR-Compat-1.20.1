package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import com.arxyt.dominionsword.mtr.bridge.MtrForcedDoorAccess;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleSimulationMixin {
    private static final Logger DOMINION_SWORD_LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean DOMINION_SWORD_FIRST_SNAPSHOT = new AtomicBoolean();
    private static volatile long dominionSword$lastFailureLog;
    @Unique private boolean dominionSword$loggedForcedDeparture;
    @Unique private boolean dominionSword$forcedDoorsActive;
    @Unique private boolean dominionSword$wasControlled;
    @Shadow @Final public VehicleExtraData vehicleExtraData;
    @Inject(method = "simulate", at = @At("HEAD"), remap = false)
    private void dominionSword$registerManagedRiders(long millisElapsed,
            ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions,
            Long2LongAVLTreeMap vehicleTimes, CallbackInfo ci) {
        Vehicle self = (Vehicle) (Object) this;
        Data data = ((NameColorDataAccessor) self).dominionSword$getData();
        if (data instanceof Simulator simulator && self.getTransportMode() == TransportMode.TRAIN) {
            boolean forceDoors = MtrSnapshotBridge.forceDoorsRequested(
                    simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
            ((MtrForcedDoorAccess) (Object) vehicleExtraData).dominionSword$setForcedOpen(forceDoors);
            if (forceDoors != dominionSword$forcedDoorsActive) {
                dominionSword$forcedDoorsActive = forceDoors;
                DOMINION_SWORD_LOGGER.info("Dominion Sword MTR forced all doors {}: dimension={}, siding={}, vehicle={}",
                        forceDoors ? "active" : "released", simulator.dimension,
                        vehicleExtraData.getSidingId(), self.getId());
            }
            MtrSnapshotBridge.reconcileManagedRiders(simulator.dimension, vehicleExtraData.getSidingId(), self.getId(), simulator);
            /* MTR's own manual-driving path calls startUp(-1, now). Reusing it here bypasses only
             * timetable departure matching; subsequent simulation still owns signals, speed and stops. */
            boolean forcedDeparture = MtrSnapshotBridge.forceDepartureRequested(
                    simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
            MtrSnapshotBridge.TrainControl control = MtrSnapshotBridge.trainControl(
                    simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
            if (control == null) {
                if (dominionSword$wasControlled) ((VehicleMethodInvoker) self).dominionSword$setNextStoppingIndex();
                dominionSword$wasControlled = false;
            } else {
                dominionSword$wasControlled = true;
                if (control.targetPathIndex() >= 0) ((VehicleSchemaAccessor) self)
                        .dominionSword$setNextStoppingIndexAto(control.targetPathIndex());
            }
            if (!self.isMoving() && forcedDeparture) {
                self.startUp(-1L, data.getCurrentMillis());
                if (control != null && control.targetPathIndex() >= 0) ((VehicleSchemaAccessor) self)
                        .dominionSword$setNextStoppingIndexAto(control.targetPathIndex());
                if (self.isMoving() && !dominionSword$loggedForcedDeparture) {
                    MtrSnapshotBridge.compatibilityTrip(self.getId(), true);
                    dominionSword$loggedForcedDeparture = true;
                    DOMINION_SWORD_LOGGER.info("Dominion Sword MTR forced departure accepted: dimension={}, siding={}, vehicle={}",
                            simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
                }
            }
            if (!forcedDeparture) dominionSword$loggedForcedDeparture = false;
            if (!forcedDeparture && !self.getIsOnRoute() && MtrSnapshotBridge.isCompatibilityTrip(self.getId())) {
                MtrSnapshotBridge.compatibilityTrip(self.getId(), false);
            }
        }
    }

    /** A controlled train cannot let MTR's dwell/timetable code depart again from the selected destination. */
    @Inject(method = "startUp", at = @At("HEAD"), cancellable = true, remap = false)
    private void dominionSword$holdAtTarget(long departureIndex, long sidingDepartureTime, CallbackInfo ci) {
        Vehicle self = (Vehicle) (Object) this;
        Data data = ((NameColorDataAccessor) self).dominionSword$getData();
        if (data instanceof Simulator simulator) {
            MtrSnapshotBridge.TrainControl control = MtrSnapshotBridge.trainControl(
                    simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
            if (control != null && control.hold()) ci.cancel();
        }
    }

    @Inject(method = "simulate", at = @At("TAIL"), remap = false)
    private void dominionSword$capture(long millisElapsed,
            ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions,
            Long2LongAVLTreeMap vehicleTimes, CallbackInfo ci) {
        Vehicle self = (Vehicle) (Object) this;
        Data data = ((NameColorDataAccessor) self).dominionSword$getData();
        double speed = ((VehicleSchemaAccessor) self).dominionSword$getSpeed();
        double railProgress = ((VehicleSchemaAccessor) self).dominionSword$getRailProgress();
        if (!(data instanceof Simulator simulator) || self.getTransportMode() != TransportMode.TRAIN || !self.isValid()) return;
        try {
            List<MtrTrainSnapshot.CarPose> cars = new ArrayList<>();
            self.getVehicleCarsAndPositions().forEach(carAndBogies -> {
                VehicleCar car = carAndBogies.left();
                List<Vector> centers = new ArrayList<>();
                carAndBogies.right().forEach(pair -> centers.add(Vector.getAverage(pair.left(), pair.right())));
                if (centers.isEmpty()) return;
                Vector first = centers.get(0), last = centers.get(centers.size() - 1);
                double x = centers.stream().mapToDouble(v -> v.x).average().orElse(first.x);
                double y = centers.stream().mapToDouble(v -> v.y).average().orElse(first.y);
                double z = centers.stream().mapToDouble(v -> v.z).average().orElse(first.z);
                double deltaX = last.x - first.x, deltaY = last.y - first.y, deltaZ = last.z - first.z;
                double yaw = Math.atan2(deltaX, deltaZ);
                double pitch = Math.atan2(deltaY, Math.hypot(deltaX, deltaZ));
                cars.add(new MtrTrainSnapshot.CarPose(x, y, z, yaw, pitch, car.getLength(), car.getWidth()));
            });

            String bridgeKey = MtrSnapshotBridge.key(simulator.dimension, vehicleExtraData.getSidingId(), self.getId());
            long routeSignature = routeSignature(vehicleExtraData);
            MtrSnapshotBridge.RouteData route = MtrSnapshotBridge.route(bridgeKey, routeSignature,
                    () -> captureRoute(vehicleExtraData, simulator));
            List<MtrTrainSnapshot.Rider> riders = new ArrayList<>();
            vehicleExtraData.iterateRidingEntities(rider -> riders.add(new MtrTrainSnapshot.Rider(
                    rider.uuid, rider.getRidingCar(), rider.getX(), rider.getY(), rider.getZ(),
                    rider.getIsOnGangway(), rider.isDriver(), rider.manualAccelerate(), rider.manualBrake(),
                    rider.manualToggleDoors(), rider.manualToggleAto(), rider.getDoorOverride())));
            MtrSnapshotBridge.publish(new MtrTrainSnapshot(System.currentTimeMillis(), simulator.dimension,
                    vehicleExtraData.getSidingId(), self.getId(), self.getDepartureIndex(), speed, railProgress,
                    vehicleExtraData.getPowerLevel(), vehicleExtraData.getDoorMultiplier(),
                    vehicleExtraData.getIsCurrentlyManual(), self.getIsOnRoute(), List.copyOf(cars),
                    route.path(), route.stops(), List.copyOf(riders)));
            if (DOMINION_SWORD_FIRST_SNAPSHOT.compareAndSet(false, true)) {
                DOMINION_SWORD_LOGGER.info("Dominion Sword MTR bridge captured its first train snapshot: dimension={}, siding={}, vehicle={}, cars={}",
                        simulator.dimension, vehicleExtraData.getSidingId(), self.getId(), cars.size());
            }
        } catch (RuntimeException exception) {
            // A partial simulation tick must never affect MTR, but a bounded diagnostic must expose a dead bridge.
            long now = System.currentTimeMillis();
            if (now - dominionSword$lastFailureLog >= 10_000L) {
                dominionSword$lastFailureLog = now;
                DOMINION_SWORD_LOGGER.warn("Dominion Sword MTR snapshot capture failed for vehicle {}", self.getId(), exception);
            }
        }
    }

    private static MtrTrainSnapshot.Point point(Vector vector) {
        return new MtrTrainSnapshot.Point(vector.x, vector.y, vector.z);
    }

    private static long routeSignature(VehicleExtraData extra) {
        int size = extra.immutablePath.size();
        if (size == 0) return 0;
        PathData first = extra.immutablePath.get(0), last = extra.immutablePath.get(size - 1);
        long value = 31L * size + first.getSavedRailBaseId();
        value = 31L * value + last.getSavedRailBaseId();
        return 31L * value + Double.doubleToLongBits(last.getEndDistance());
    }

    private static MtrSnapshotBridge.RouteData captureRoute(VehicleExtraData extra, Simulator simulator) {
        List<MtrTrainSnapshot.PathPart> parts = new ArrayList<>();
        List<MtrTrainSnapshot.Stop> stops = new ArrayList<>();
        for (int i = 0; i < extra.immutablePath.size(); i++) {
            PathData part = extra.immutablePath.get(i);
            double length = Math.max(0, part.getRailLength());
            List<MtrTrainSnapshot.Point> points = new ArrayList<>();
            for (double d = 0; d < length; d += 2) points.add(point(part.getPosition(d)));
            points.add(point(part.getPosition(length)));
            long platformId = part.getDwellTime() > 0 ? part.getSavedRailBaseId() : 0;
            parts.add(new MtrTrainSnapshot.PathPart(i, platformId, part.getStartDistance(), part.getEndDistance(), part.getDwellTime(), List.copyOf(points)));
            if (part.getDwellTime() > 0) {
                Platform platform = simulator.platformIdMap.get(platformId);
                String name = platform == null ? "Platform " + platformId : platform.getStationName();
                stops.add(new MtrTrainSnapshot.Stop(platformId, i, part.getEndDistance(), name, point(part.getPosition(length))));
            }
        }
        return new MtrSnapshotBridge.RouteData(List.copyOf(parts), List.copyOf(stops));
    }
}
