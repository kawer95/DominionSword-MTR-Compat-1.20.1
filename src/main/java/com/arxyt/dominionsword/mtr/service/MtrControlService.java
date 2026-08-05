package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import com.arxyt.dominionsword.mtr.bridge.MtrNativeRidingState;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.arxyt.dominionsword.mtr.compat.MtrVehicleAdapter;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.data.Vehicle;
import org.mtr.core.operation.UpdateVehicleRidingEntities;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import org.slf4j.Logger;

public final class MtrControlService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DOOR_ANIMATION_MS = Vehicle.DOOR_MOVE_TIME;
    public enum Mode { ATO, STARTING, EMERGENCY, HOLD }
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private MtrControlService() {}

    public static State state(MtrTrainProxyEntity proxy) { return STATES.computeIfAbsent(proxy.getUUID(), id -> new State()); }
    public static void clear(MtrTrainProxyEntity proxy) {
        State removed = STATES.remove(proxy.getUUID());
        if (removed != null && removed.exitAllRequested) MtrCompatNetwork.syncForcedDoors(proxy, false);
    }

    public static boolean hasRealPlayerDriver(MinecraftServer server, MtrTrainSnapshot snapshot) {
        for (MtrTrainSnapshot.Rider rider : snapshot.riders())
            if (rider.driver() && server.getPlayerList().getPlayer(rider.uuid()) != null
                    && MtrNativeRidingState.isPlayerRiding(rider.uuid())) return true;
        return false;
    }

    public static boolean setMode(ServerPlayer actor, MtrTrainProxyEntity proxy, Mode mode) {
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null) return false;
        MinecraftServer server = actor == null ? proxy.getServer() : actor.server;
        if (server == null) return false;
        if (mode != Mode.HOLD && mode != Mode.EMERGENCY && hasRealPlayerDriver(server, snapshot)) {
            if (actor != null) actor.sendSystemMessage(Component.translatable("message.dominionsword_mtr_compat.player_driver"));
            return false;
        }
        state(proxy).mode = mode;
        return true;
    }

    public static void target(MtrTrainProxyEntity proxy, MtrTrainSnapshot.Stop stop) {
        State state = state(proxy);
        state.targetKey = stop == null ? "" : stop.key();
        proxy.targetKey(state.targetKey);
        if (stop != null) state.mode = Mode.ATO;
    }

    /** Starts a bounded door-open request once a unit has actually reached its reserved doorway. */
    public static void requestBoardingDoor(MtrTrainProxyEntity proxy, Entity unit, int car, double x, double y, double z) {
        if (unit == null || !unit.isAlive()) return;
        State state = state(proxy);
        long now = System.currentTimeMillis();
        boolean operatorAlreadyBoarded = state.doorOperator != null && proxy.getPassengers().stream()
                .anyMatch(passenger -> passenger.getUUID().equals(state.doorOperator));
        if (now < state.nextDoorRequestAt || state.doorOperator != null && !operatorAlreadyBoarded) return;
        if (state.doorOperator == null && !state.compatDoorOpen) {
            state.openPulseSent = false;
            state.closePulseSent = false;
        }
        state.doorOperator = unit.getUUID();
        state.doorCar = Math.max(0, car);
        state.doorX = x;
        state.doorY = y;
        state.doorZ = z;
        state.doorRequestedAt = now;
        state.doorHoldUntil = now + 6_000L;
        state.loggedDoorAcknowledged = false;
        state.loggedDoorReady = false;
        LOGGER.info("Dominion Sword MTR boarding door requested: proxy={}, unit={}, car={}, local=({}, {}, {})",
                proxy.getUUID(), unit.getUUID(), state.doorCar, x, y, z);
    }

    /** Each completed boarding restarts the quiet-period countdown before doors are released. */
    public static void noteDoorPassage(MtrTrainProxyEntity proxy, Entity unit) {
        State state = state(proxy);
        if (unit != null) state.doorOperator = unit.getUUID();
        state.doorHoldUntil = System.currentTimeMillis() + 5_000L;
    }

    /** Opens every MTR door first; the adapter unloads occupants only after snapshot acknowledgement. */
    public static boolean requestDismountAll(MtrTrainProxyEntity proxy) {
        if (proxy == null || proxy.getPassengers().isEmpty()) return false;
        State state = state(proxy);
        state.mode = Mode.HOLD;
        state.exitAllRequested = true;
        state.exitDoorAcknowledgedAt = 0L;
        state.exitClientDoorOpenedAt = 0L;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot != null) {
            MtrSnapshotBridge.forceDoors(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), true);
            MtrTrainSnapshot.Stop target = findStop(snapshot, state.targetKey.isEmpty() ? proxy.targetKey() : state.targetKey);
            MtrSnapshotBridge.forceDeparture(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false);
            MtrSnapshotBridge.trainControl(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), true,
                    target == null ? -1 : target.pathIndex(), true);
        }
        MtrCompatNetwork.syncForcedDoors(proxy, true);
        LOGGER.info("Dominion Sword MTR dismount-all requested: proxy={}, occupants={}",
                proxy.getUUID(), proxy.getPassengers().size());
        if (snapshot != null) LOGGER.info(
                "MTR DISMOUNT TRACE stage=request proxy={} speed={} power={} doors={} railProgress={} riders={} passengers={}",
                proxy.getUUID(), snapshot.speed(), snapshot.powerLevel(), snapshot.doorMultiplier(),
                snapshot.railProgress(), snapshot.riders().size(), proxy.getPassengers().size());
        return true;
    }

    /** Accepts only a nearby controlling client's acknowledgement after native doorValue reached 1. */
    public static void acknowledgeDismountDoors(ServerPlayer player, UUID proxyId, long vehicleId) {
        if (player == null || !(player.serverLevel().getEntity(proxyId) instanceof MtrTrainProxyEntity proxy)
                || proxy.vehicleId() != vehicleId || player.distanceToSqr(proxy) > 512D * 512D) return;
        State state = STATES.get(proxyId);
        if (state == null || !state.exitAllRequested) return;
        if (state.exitClientDoorOpenedAt == 0L) {
            state.exitClientDoorOpenedAt = System.currentTimeMillis();
            LOGGER.info("Dominion Sword MTR client confirmed all doors visually open: proxy={}, player={}",
                    proxyId, player.getUUID());
        }
    }

    /** True only after MTR has retained the localized door override and its animation has had time to open. */
    public static boolean boardingDoorReady(MtrTrainProxyEntity proxy, Entity unit) {
        if (proxy == null || unit == null) return false;
        State state = STATES.get(proxy.getUUID());
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (state == null || snapshot == null || !unit.getUUID().equals(state.doorOperator)
                || System.currentTimeMillis() >= state.doorHoldUntil) return false;
        boolean acknowledged = snapshot.riders().stream().anyMatch(rider -> rider.uuid().equals(unit.getUUID())
                && rider.car() == state.doorCar && rider.doorOverride()
                && Math.abs(rider.x() - state.doorX) <= .05D && Math.abs(rider.z() - state.doorZ) <= .05D);
        if (acknowledged && !state.loggedDoorAcknowledged) {
            state.loggedDoorAcknowledged = true;
            LOGGER.info("Dominion Sword MTR boarding door acknowledged by simulation: proxy={}, unit={}, car={}",
                    proxy.getUUID(), unit.getUUID(), state.doorCar);
        }
        boolean ready = acknowledged && System.currentTimeMillis() - state.doorRequestedAt >= DOOR_ANIMATION_MS;
        if (ready && !state.loggedDoorReady) {
            state.loggedDoorReady = true;
            LOGGER.info("Dominion Sword MTR boarding door ready: proxy={}, unit={}, car={}",
                    proxy.getUUID(), unit.getUUID(), state.doorCar);
        }
        return ready;
    }

    /** Returns all globally open doors, or only the real doorway held by the compatibility rider. */
    public static List<MtrDoorGeometryService.DoorBox> collisionOpenDoors(MtrTrainProxyEntity proxy,
            MtrTrainSnapshot snapshot, int carIndex, List<MtrDoorGeometryService.DoorBox> reported) {
        if (snapshot.doorMultiplier() > 0) return reported;
        State state = STATES.get(proxy.getUUID());
        long now = System.currentTimeMillis();
        if (state.exitAllRequested && now - state.lastExitTraceAt >= 500L) {
            state.lastExitTraceAt = now;
            LOGGER.info("MTR DISMOUNT TRACE stage=wait proxy={} forced={} speed={} power={} doors={} acknowledgedAt={} clientOpenedAt={} elapsedSinceClientOpen={} passengers={} riders={}",
                    proxy.getUUID(), MtrSnapshotBridge.forceDoorsRequested(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId()),
                    snapshot.speed(), snapshot.powerLevel(), snapshot.doorMultiplier(), state.exitDoorAcknowledgedAt,
                    state.exitClientDoorOpenedAt, state.exitClientDoorOpenedAt == 0L ? -1L : now - state.exitClientDoorOpenedAt,
                    proxy.getPassengers().size(), snapshot.riders().size());
        }
        if (state == null || state.doorOperator == null || now >= state.doorHoldUntil || state.doorCar != carIndex) return List.of();
        boolean acknowledged = snapshot.riders().stream().anyMatch(rider -> rider.uuid().equals(state.doorOperator)
                && rider.car() == state.doorCar && rider.doorOverride());
        if (!acknowledged || now - state.doorRequestedAt < DOOR_ANIMATION_MS) return List.of();
        return reported.stream().filter(door -> state.doorX >= door.minX() - .35D && state.doorX <= door.maxX() + .35D
                && state.doorY >= door.minY() - .05D && state.doorY <= door.maxY() + .05D
                && state.doorZ >= door.minZ() - .35D && state.doorZ <= door.maxZ() + .35D).toList();
    }

    public static void tick(MinecraftServer server, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        State state = state(proxy);
        boolean humanDriver = hasRealPlayerDriver(server, snapshot);
        if (humanDriver) {
            state.mode = Mode.ATO;
            state.unitDriver = false;
            state.doorOperator = null;
            state.doorHoldUntil = 0;
            state.compatDoorOpen = false;
            state.openPulseSent = false;
            state.closePulseSent = false;
        }
        MtrTrainSnapshot.Stop target = findStop(snapshot, state.targetKey.isEmpty() ? proxy.targetKey() : state.targetKey);
        if (target != null && snapshot.railProgress() >= target.progress() - .75 && Math.abs(snapshot.speed()) < 0.00002) state.mode = Mode.HOLD;
        boolean unitControlled = !humanDriver && proxy.passengerAt(0) != null;
        MtrSnapshotBridge.trainControl(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), unitControlled,
                target == null ? -1 : target.pathIndex(), unitControlled && (target == null || state.mode == Mode.HOLD));
        boolean forceDeparture = !humanDriver && state.mode == Mode.STARTING && proxy.passengerAt(0) != null;
        MtrSnapshotBridge.forceDeparture(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), forceDeparture);
        if (++state.cooldown < 2) return;
        state.cooldown = 0;

        long now = System.currentTimeMillis();
        MtrSnapshotBridge.forceDoors(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), state.exitAllRequested);
        if (state.exitAllRequested && MtrSnapshotBridge.forceDoorsRequested(
                snapshot.dimension(), proxy.sidingId(), proxy.vehicleId()) && snapshot.doorMultiplier() > 0) {
            if (state.exitDoorAcknowledgedAt == 0L) {
                state.exitDoorAcknowledgedAt = now;
                LOGGER.info("Dominion Sword MTR all doors acknowledged open: proxy={}", proxy.getUUID());
            }
            if (state.exitClientDoorOpenedAt > 0L && now - state.exitClientDoorOpenedAt >= 150L) {
                int occupants = proxy.getPassengers().size();
                boolean completed = MtrVehicleAdapter.completeDismountAll(proxy);
                state.exitAllRequested = false;
                state.exitDoorAcknowledgedAt = 0L;
                state.exitClientDoorOpenedAt = 0L;
                MtrSnapshotBridge.forceDoors(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false);
                MtrCompatNetwork.syncForcedDoors(proxy, false);
                MtrSnapshotBridge.trainControl(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false, -1, false);
                LOGGER.info("Dominion Sword MTR dismount-all completed: proxy={}, occupants={}, success={}",
                        proxy.getUUID(), occupants, completed);
            }
        } else if (state.exitAllRequested) {
            state.exitDoorAcknowledgedAt = 0L;
        }
        boolean doorHolding = !humanDriver && state.doorOperator != null && now < state.doorHoldUntil;
        if (doorHolding && proxy.level() instanceof net.minecraft.server.level.ServerLevel level
                && proxy.passengerAt(0) == null && level.getEntity(state.doorOperator) == null) {
            state.doorOperator = null;
            state.doorHoldUntil = 0;
            doorHolding = false;
        }
        boolean accelerate = false, brake = false, toggleAto = false;
        if (!humanDriver) {
            if (state.mode == Mode.EMERGENCY) brake = snapshot.powerLevel() > -8;
            else if (state.mode == Mode.HOLD) {
                brake = snapshot.powerLevel() > -1;
                accelerate = snapshot.powerLevel() < -1;
            } else if (state.mode == Mode.STARTING) {
                // The unit remains MTR's real manual driver until motion begins, so establish P1 as well as startUp.
                accelerate = snapshot.powerLevel() < 1;
                if (Math.abs(snapshot.speed()) > 0.00002) {
                    toggleAto = snapshot.manual();
                    state.mode = Mode.ATO;
                    MtrSnapshotBridge.forceDeparture(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false);
                }
            }
            state.compatDoorOpen = doorHolding;
            if (!doorHolding && state.doorOperator != null) {
                state.doorOperator = null;
                state.nextDoorRequestAt = now + 3_000L;
            }
        }

        Map<UUID, MtrTrainSnapshot.Rider> riders = new LinkedHashMap<>();
        Set<UUID> previouslyManaged = new HashSet<>(state.managedUnits);
        Set<UUID> currentUnits = new HashSet<>();
        for (Entity passenger : proxy.getPassengers()) {
            currentUnits.add(passenger.getUUID());
            int seat = proxy.seatFor(passenger);
            boolean driver = !humanDriver && seat == 0;
            riders.put(passenger.getUUID(), new MtrTrainSnapshot.Rider(passenger.getUUID(), carForSeat(snapshot, seat),
                    0, 0, 0, false, driver, driver && accelerate, driver && brake, false, driver && toggleAto, false));
        }
        // The approaching unit owns the localized door override even when a driver already occupies seat zero.
        UUID doorDriver = state.doorOperator != null ? state.doorOperator
                : proxy.passengerAt(0) == null ? null : proxy.passengerAt(0).getUUID();
        if (!humanDriver && doorDriver != null && (doorHolding || state.compatDoorOpen)) {
            MtrTrainSnapshot.Rider base = riders.get(doorDriver);
            if (base == null) base = new MtrTrainSnapshot.Rider(doorDriver, state.doorCar, state.doorX, state.doorY, state.doorZ,
                    false, true, false, false, false, false, false);
            riders.put(doorDriver, new MtrTrainSnapshot.Rider(base.uuid(), state.doorCar, state.doorX, state.doorY, state.doorZ,
                    base.gangway(), true, base.accelerate(), base.brake(), false, base.toggleAto(), doorHolding));
            currentUnits.add(doorDriver);
        }
        // This MTR operation patches only the UUIDs present in the request. Never echo
        // unmanaged snapshot riders: doing so re-added a real player one tick after MTR
        // had processed that player's native dismount tombstone.
        for (UUID removed : previouslyManaged) if (!currentUnits.contains(removed)) {
            riders.put(removed, new MtrTrainSnapshot.Rider(removed, -1, 0, 0, 0,
                    false, false, false, false, false, false, false));
        }
        // MTR's client has dismounted but an older compatibility pulse may have resurrected
        // the snapshot rider. Its own RIDING_PLAYERS map is the authoritative logical state.
        for (MtrTrainSnapshot.Rider rider : snapshot.riders()) {
            if (server.getPlayerList().getPlayer(rider.uuid()) != null && !MtrNativeRidingState.isPlayerRiding(rider.uuid())) {
                riders.put(rider.uuid(), new MtrTrainSnapshot.Rider(rider.uuid(), -1, 0, 0, 0,
                        false, false, false, false, false, false, false));
            }
        }
        state.managedUnits.clear();
        state.managedUnits.addAll(currentUnits);
        state.unitDriver = !humanDriver && proxy.passengerAt(0) != null;

        MtrSnapshotBridge.managedRiders(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), currentUnits);
        send(server, proxy, riders);
    }

    public static void release(MinecraftServer server, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        State state = STATES.get(proxy.getUUID());
        if (snapshot != null) MtrSnapshotBridge.forceDeparture(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false);
        if (snapshot != null) MtrSnapshotBridge.forceDoors(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false);
        else MtrSnapshotBridge.forceDoors(proxy.level().dimension().location().toString(), proxy.sidingId(), proxy.vehicleId(), false);
        if (snapshot != null) MtrSnapshotBridge.trainControl(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), false, -1, false);
        if (state == null || snapshot == null || state.managedUnits.isEmpty()) return;
        Map<UUID, MtrTrainSnapshot.Rider> riders = new LinkedHashMap<>();
        for (UUID managed : state.managedUnits) riders.put(managed, new MtrTrainSnapshot.Rider(
                managed, -1, 0, 0, 0, false, false, false, false, false, false, false));
        MtrSnapshotBridge.managedRiders(snapshot.dimension(), proxy.sidingId(), proxy.vehicleId(), Set.of());
        send(server, proxy, riders);
        state.managedUnits.clear();
    }

    private static void send(MinecraftServer server, MtrTrainProxyEntity proxy, Map<UUID, MtrTrainSnapshot.Rider> riders) {
        UpdateVehicleRidingEntities update = new UpdateVehicleRidingEntities(proxy.sidingId(), proxy.vehicleId());
        for (MtrTrainSnapshot.Rider rider : riders.values()) update.add(new VehicleRidingEntity(rider.uuid(), rider.car(),
                rider.x(), rider.y(), rider.z(), rider.gangway(), rider.driver(), rider.accelerate(), rider.brake(),
                rider.doors(), rider.toggleAto(), rider.doorOverride()));
        Init.sendMessageC2S(OperationProcessor.UPDATE_RIDING_ENTITIES,
                new org.mtr.mapping.holder.MinecraftServer(server), new World(proxy.level()), update, null, null);
    }

    private static long carForSeat(MtrTrainSnapshot snapshot, int seat) {
        if (seat <= 0) return 0;
        int left = seat - 1;
        for (int car = 0; car < snapshot.cars().size(); car++) {
            int rows = Math.max(1, (int) Math.floor(Math.max(2, snapshot.cars().get(car).length() - 2) / 1.35));
            if (left < rows * 2) return car;
            left -= rows * 2;
        }
        return Math.max(0, snapshot.cars().size() - 1);
    }

    public static MtrTrainSnapshot.Stop findStop(MtrTrainSnapshot snapshot, String key) {
        if (key == null || key.isEmpty()) return null;
        return snapshot.stops().stream().filter(stop -> stop.key().equals(key)).findFirst().orElse(null);
    }

    public static final class State {
        private Mode mode = Mode.ATO;
        private String targetKey = "";
        private int cooldown;
        private boolean unitDriver;
        private UUID doorOperator;
        private long doorHoldUntil;
        private boolean compatDoorOpen;
        private boolean openPulseSent;
        private boolean closePulseSent;
        private long nextDoorRequestAt;
        private int doorCar;
        private double doorX;
        private double doorY;
        private double doorZ;
        private long doorRequestedAt;
        private boolean loggedDoorAcknowledged;
        private boolean loggedDoorReady;
        private boolean exitAllRequested;
        private long exitDoorAcknowledgedAt;
        private long exitClientDoorOpenedAt;
        private long lastExitTraceAt;
        private final Set<UUID> managedUnits = new HashSet<>();
        public Mode mode() { return mode; }
        public String targetKey() { return targetKey; }
        public boolean unitDriver() { return unitDriver; }
    }
}
