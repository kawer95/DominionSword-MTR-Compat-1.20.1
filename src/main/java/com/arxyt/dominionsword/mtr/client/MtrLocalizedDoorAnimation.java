package com.arxyt.dominionsword.mtr.client;

import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectDoubleImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicleHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Smooths MTR's normally instantaneous rider-local door override without replacing native door rendering. */
public final class MtrLocalizedDoorAnimation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<DoorKey, DoorState> STATES = new HashMap<>();

    private MtrLocalizedDoorAnimation() {}

    /** The optimized renderer must not queue its closed-door base while this car still has a closing override. */
    public static boolean isClosing(long vehicleId, int carNumber) {
        return STATES.entrySet().stream().anyMatch(entry -> entry.getKey().vehicleId == vehicleId
                && entry.getKey().carNumber == carNumber && entry.getValue().value > 0D);
    }

    public static void apply(VehicleExtension vehicle, int carNumber,
                             ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways) {
        if (vehicle == null) return;
        long now = System.nanoTime();
        Set<DoorKey> active = new HashSet<>();

        for (int index = 0; index < openDoorways.size(); index++) {
            ObjectDoubleImmutablePair<Box> pair = openDoorways.get(index);
            Box doorway = pair.left();
            if (!heldByOverride(vehicle, carNumber, doorway)) continue;
            DoorKey key = DoorKey.of(vehicle.getId(), carNumber, doorway);
            DoorState state = STATES.computeIfAbsent(key, ignored -> new DoorState(doorway, now));
            state.approach(1D, now);
            state.lastActive = now;
            active.add(key);
            openDoorways.set(index, new ObjectDoubleImmutablePair<>(doorway, state.value));
            state.trace(vehicle, carNumber, "local-opening", openDoorways.size(), false, now);
        }

        for (Map.Entry<DoorKey, DoorState> entry : new ArrayList<>(STATES.entrySet())) {
            DoorKey key = entry.getKey();
            if (key.vehicleId != vehicle.getId() || key.carNumber != carNumber || active.contains(key)) continue;
            DoorState state = entry.getValue();
            state.approach(0D, now);
            if (state.value <= 0D || now - state.lastActive > 10_000_000_000L) {
                STATES.remove(key);
            } else if (openDoorways.stream().noneMatch(pair -> sameDoor(pair.left(), state.doorway))) {
                openDoorways.add(new ObjectDoubleImmutablePair<>(state.doorway, state.value));
                state.trace(vehicle, carNumber, "local-closing-appended", openDoorways.size(), true, now);
            }
        }
    }

    private static boolean heldByOverride(VehicleExtension vehicle, int carNumber, Box doorway) {
        boolean[] held = {false};
        vehicle.vehicleExtraData.iterateRidingEntities(rider -> {
            if (!held[0] && rider.getDoorOverride() && rider.getRidingCar() == carNumber
                    && RenderVehicleHelper.getDoorBlockedAmount(
                    doorway, rider.getX(), rider.getY(), rider.getZ()) > 0D) held[0] = true;
        });
        return held[0];
    }

    private static boolean sameDoor(Box first, Box second) {
        return first.getMinXMapped() == second.getMinXMapped() && first.getMinYMapped() == second.getMinYMapped()
                && first.getMinZMapped() == second.getMinZMapped() && first.getMaxXMapped() == second.getMaxXMapped()
                && first.getMaxYMapped() == second.getMaxYMapped() && first.getMaxZMapped() == second.getMaxZMapped();
    }

    private record DoorKey(long vehicleId, int carNumber, double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {
        private static DoorKey of(long vehicleId, int carNumber, Box box) {
            return new DoorKey(vehicleId, carNumber, box.getMinXMapped(), box.getMinYMapped(), box.getMinZMapped(),
                    box.getMaxXMapped(), box.getMaxYMapped(), box.getMaxZMapped());
        }
    }

    private static final class DoorState {
        private final Box doorway;
        private double value;
        private long updatedAt;
        private long lastActive;
        private long lastTrace;

        private DoorState(Box doorway, long now) {
            this.doorway = doorway;
            updatedAt = now;
            lastActive = now;
        }

        private void approach(double target, long now) {
            double elapsedMillis = Math.min(250D, Math.max(0D, (now - updatedAt) / 1_000_000D));
            double step = elapsedMillis / Vehicle.DOOR_MOVE_TIME;
            value = target > value ? Math.min(target, value + step) : Math.max(target, value - step);
            updatedAt = now;
        }

        private void trace(VehicleExtension vehicle, int carNumber, String phase, int doorwayCount,
                           boolean appendedAfterBaseDecision, long now) {
            if (now - lastTrace < 250_000_000L) return;
            lastTrace = now;
            LOGGER.info("MTR DOOR CLIENT TRACE phase={} vehicle={} car={} multiplier={} nativeDoorValue={} overrideValue={} doorwayCount={} appendedAfterBaseDecision={}",
                    phase, vehicle.getId(), carNumber, vehicle.vehicleExtraData.getDoorMultiplier(),
                    vehicle.persistentVehicleData.getDoorValue(), value, doorwayCount, appendedAfterBaseDecision);
        }
    }
}
