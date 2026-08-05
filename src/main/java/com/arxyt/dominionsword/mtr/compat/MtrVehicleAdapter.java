package com.arxyt.dominionsword.mtr.compat;

import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.arxyt.dominionsword.control.DominionTaskScheduler;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import com.arxyt.dominionsword.mtr.service.MtrControlService;
import com.arxyt.dominionsword.mtr.service.MtrDoorGeometryService;
import com.arxyt.dominionsword.mtr.service.MtrProxyManager;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

public final class MtrVehicleAdapter implements DominionVehicleAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, String> PREPARED_TARGETS = new HashMap<>();
    private static final Map<UUID, DoorReservation> DOOR_RESERVATIONS = new ConcurrentHashMap<>();
    private static final long DOOR_RESERVATION_MS = 30_000;
    private static final Map<String, Long> TRACE_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TARGET_SYNC_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> OBSERVED_COMMANDS = new ConcurrentHashMap<>();

    @Override public int priority() { return 1000; }
    @Override public boolean supports(Entity vehicle) { return vehicle instanceof MtrTrainProxyEntity; }
    @Override public boolean selectable(Entity vehicle) {
        return vehicle instanceof MtrTrainProxyEntity proxy && !proxy.getPassengers().isEmpty()
                && (proxy.level().isClientSide || MtrProxyManager.snapshot(proxy) != null);
    }
    @Override public AABB selectionBounds(Entity vehicle) {
        return vehicle instanceof MtrTrainProxyEntity proxy ? proxy.trainSelectionBounds() : vehicle.getBoundingBox();
    }
    @Override public AABB portraitBounds(Entity vehicle) { return new AABB(vehicle.getX() - 1, vehicle.getY(), vehicle.getZ() - .25, vehicle.getX() + 1, vehicle.getY() + 2, vehicle.getZ() + .25); }
    @Override public float portraitScaleMultiplier(Entity vehicle) { return .9F; }
    @Override public PortraitRenderScope beginPortraitRender(Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity)) return PortraitRenderScope.NOOP;
        com.arxyt.dominionsword.mtr.client.MtrPortraitRenderState.enter();
        return com.arxyt.dominionsword.mtr.client.MtrPortraitRenderState::leave;
    }

    @Override public List<SeatView> seats(Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return List.of();
        List<SeatView> result = new ArrayList<>(proxy.seatCount());
        for (int i = 0; i < proxy.seatCount(); i++) result.add(new SeatView(i, i == 0 ? "驾驶位" : "乘员位", proxy.passengerAt(i)));
        return result;
    }

    @Override public boolean hasDriver(Entity vehicle) {
        return vehicle instanceof MtrTrainProxyEntity proxy && proxy.passengerAt(0) != null;
    }

    @Override public boolean board(ServerPlayer player, Mob unit, Entity vehicle, int seat, boolean force) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy) || seat < 0 || seat >= proxy.seatCount()) return false;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        Vec3 doorway = boardingPosition(unit, proxy);
        boolean trainStopped = stopped(snapshot);
        boolean doorReady = trainStopped && MtrControlService.boardingDoorReady(proxy, unit);
        double distanceSqr = unit.distanceToSqr(doorway);
        boolean capacity = proxy.getPassengers().size() < proxy.seatCount();
        trace(unit, proxy, "board-gate", doorway, snapshot,
                "seat=" + seat + " force=" + force + " stopped=" + trainStopped
                        + " doorReady=" + doorReady + " distanceSqr=" + distanceSqr + " capacity=" + capacity);
        if (!trainStopped || !doorReady || !atDoor(unit, doorway) || !capacity) return false;
        if (!proxy.assignSeat(unit, seat, force)) return false;
        unit.stopRiding();
        unit.setNoGravity(true);
        boolean boarded = unit.startRiding(proxy, true);
        if (!boarded) { unit.setNoGravity(false); proxy.clearSeat(unit); }
        if (boarded) MtrControlService.noteDoorPassage(proxy, unit);
        trace(unit, proxy, "board-result", doorway, snapshot,
                "seat=" + seat + " startRiding=" + boarded + " passengerCount=" + proxy.getPassengers().size());
        DOOR_RESERVATIONS.remove(unit.getUUID());
        return boarded;
    }

    @Override public Vec3 boardingPosition(Mob unit, Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return vehicle.position();
        if (proxy.level().isClientSide) {
            Vec3 synced = com.arxyt.dominionsword.mtr.client.MtrClientBoardingTargets.get(unit.getUUID(), proxy.getUUID());
            return synced == null ? proxy.position() : synced;
        }
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null || snapshot.cars().isEmpty()) return proxy.position();
        Vec3 doorway = nearestDoor(unit, proxy, snapshot);
        traceCommand(unit, proxy, doorway, snapshot);
        trace(unit, proxy, "boarding-position", doorway, snapshot, "");
        return doorway;
    }

    @Override public boolean canBoardFrom(Mob unit, Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return false;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        Vec3 doorway = boardingPosition(unit, vehicle);
        boolean trainStopped = stopped(snapshot);
        double distanceSqr = unit.distanceToSqr(doorway);
        boolean atDoor = atDoor(unit, doorway);
        boolean doorReady = trainStopped && atDoor && MtrControlService.boardingDoorReady(proxy, unit);
        trace(unit, proxy, "can-board", doorway, snapshot,
                "stopped=" + trainStopped + " distanceSqr=" + distanceSqr + " atDoor=" + atDoor
                        + " doorReady=" + doorReady);
        if (!trainStopped || !atDoor) return false;
        if (!doorReady) {
            DoorReservation reservation = DOOR_RESERVATIONS.get(unit.getUUID());
            if (reservation != null && reservation.proxyId.equals(proxy.getUUID())) {
                MtrControlService.requestBoardingDoor(proxy, unit, reservation.car, reservation.x, reservation.y, reservation.z);
            }
            return false;
        }
        // Navigation cannot enter the non-block MTR interior reliably. Once the real door is open,
        // finish only the short final approach server-side so board() sees the exact reserved doorway.
        unit.getNavigation().stop();
        unit.setDeltaMovement(Vec3.ZERO);
        unit.teleportTo(doorway.x, doorway.y, doorway.z);
        trace(unit, proxy, "final-door-snap", doorway, snapshot, "teleported=true");
        return true;
    }

    @Override public boolean dismount(ServerPlayer player, Entity vehicle, int seat) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy) || !stopped(MtrProxyManager.snapshot(proxy))) return false;
        Entity passenger = proxy.passengerAt(seat);
        if (passenger == null) return false;
        Vec3 exit = passenger instanceof Mob mob ? boardingPosition(mob, proxy) : proxy.position().add(2, 0, 0);
        passenger.stopRiding();
        passenger.setNoGravity(false);
        passenger.teleportTo(exit.x, exit.y, exit.z);
        DOOR_RESERVATIONS.remove(passenger.getUUID());
        return true;
    }

    @Override public boolean dismountAll(ServerPlayer player, Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy) || !stopped(MtrProxyManager.snapshot(proxy))) return false;
        return MtrControlService.requestDismountAll(proxy);
    }

    /** Completes the queued operation after MTR reports that all doors have opened. */
    public static boolean completeDismountAll(MtrTrainProxyEntity proxy) {
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null || snapshot.cars().isEmpty()) return false;
        List<Entity> passengers = new ArrayList<>(proxy.getPassengers());
        for (Entity passenger : passengers) {
            Vec3 before = passenger.position();
            int seat = proxy.seatFor(passenger);
            Vec3 exit = passenger instanceof Mob mob ? nearestDoor(mob, proxy, snapshot) : proxy.position().add(2, 0, 0);
            passenger.stopRiding(); passenger.setNoGravity(false); passenger.teleportTo(exit.x, exit.y, exit.z);
            LOGGER.info("MTR DISMOUNT TRACE passenger={} type={} seat={} from=({}, {}, {}) exit=({}, {}, {}) ridingAfter={}",
                    passenger.getUUID(), passenger.getType(), seat, before.x, before.y, before.z,
                    exit.x, exit.y, exit.z, passenger.isPassenger());
            DOOR_RESERVATIONS.remove(passenger.getUUID());
        }
        return !passengers.isEmpty();
    }

    @Override public void prepareMoveRoute(ServerPlayer player, Entity vehicle, Vec3 target) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return;
        MtrTrainSnapshot.Stop stop = resolveForwardStop(proxy, target);
        if (stop == null) PREPARED_TARGETS.remove(proxy.getUUID()); else PREPARED_TARGETS.put(proxy.getUUID(), stop.key());
    }

    @Override public List<Vec3> plannedMoveRoute(ServerPlayer player, Entity vehicle, Vec3 target) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return List.of();
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null) return List.of();
        MtrTrainSnapshot.Stop stop = MtrControlService.findStop(snapshot, PREPARED_TARGETS.get(proxy.getUUID()));
        if (stop == null) stop = resolveForwardStop(proxy, target);
        return stop == null ? List.of() : route(snapshot, stop);
    }

    @Override public boolean move(ServerPlayer player, Entity vehicle, Vec3 target) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return false;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        // The main mod intentionally pulses persistent vehicle movement. Keep the resolved
        // platform key for the whole command instead of throwing it away after its first tick.
        MtrTrainSnapshot.Stop stop = snapshot == null ? null : MtrControlService.findStop(snapshot, PREPARED_TARGETS.get(proxy.getUUID()));
        if (stop == null) stop = resolveForwardStop(proxy, target);
        if (stop == null) {
            if (player != null) player.sendSystemMessage(Component.translatable("message.dominionsword_mtr_compat.invalid_station"));
            clearPersistentMove(proxy);
            PREPARED_TARGETS.remove(proxy.getUUID());
            LOGGER.info("Dominion Sword MTR rejected and cleared invalid persistent target: proxy={} target=({}, {}, {})",
                    proxy.getUUID(), target.x, target.y, target.z);
            return false;
        }
        if (player != null && MtrControlService.hasRealPlayerDriver(player.server, snapshot)) {
            player.sendSystemMessage(Component.translatable("message.dominionsword_mtr_compat.player_driver"));
            return false;
        }
        // A persistent pulse is not a new destination selection. Reapplying target() would
        // reset HOLD to ATO every tick after arrival.
        if (!stop.key().equals(proxy.targetKey())) {
            MtrControlService.target(proxy, stop);
            if (Math.abs(snapshot.speed()) < .00002) MtrControlService.setMode(player, proxy, MtrControlService.Mode.STARTING);
        }
        return true;
    }

    @Override public List<ActionView> actions(Entity vehicle) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return List.of();
        MtrControlService.State state = MtrControlService.state(proxy);
        return List.of(new ActionView("mtr_emergency", "急刹"),
                new ActionView("mtr_hold", "保持停车", true, state.mode() == MtrControlService.Mode.HOLD),
                new ActionView("mtr_station", "选择目标站"),
                new ActionView("mtr_clear_target", "清除目标站"));
    }

    @Override public boolean performAction(ServerPlayer player, Entity vehicle, String actionId) {
        if (!(vehicle instanceof MtrTrainProxyEntity proxy)) return false;
        return switch (actionId) {
            case "mtr_emergency" -> MtrControlService.setMode(player, proxy, MtrControlService.Mode.EMERGENCY);
            case "mtr_hold" -> MtrControlService.setMode(player, proxy, MtrControlService.Mode.HOLD);
            case "mtr_station" -> { MtrCompatNetwork.openStationScreen(player, proxy); yield true; }
            case "mtr_clear_target" -> {
                PREPARED_TARGETS.remove(proxy.getUUID());
                clearPersistentMove(proxy);
                MtrControlService.target(proxy, null);
                MtrControlService.setMode(player, proxy, MtrControlService.Mode.HOLD);
                LOGGER.info("Dominion Sword MTR target and persistent move cleared: proxy={} player={}",
                        proxy.getUUID(), player == null ? "none" : player.getUUID());
                yield true;
            }
            default -> false;
        };
    }

    private static boolean stoppedWithDoors(MtrTrainSnapshot snapshot) {
        return stopped(snapshot) && snapshot.doorMultiplier() > 0;
    }

    private static void clearPersistentMove(MtrTrainProxyEntity proxy) {
        net.minecraft.nbt.CompoundTag tag = proxy.getPersistentData();
        tag.remove("DominionOfflineVehicleMove");
        tag.remove("DominionOfflineVehicleX");
        tag.remove("DominionOfflineVehicleY");
        tag.remove("DominionOfflineVehicleZ");
        tag.remove("DominionOfflineVehicleSettleTicks");
        tag.remove("DominionOfflineVehicleBestDistance");
        tag.remove("DominionOfflineVehicleBadTicks");
        DominionTaskScheduler.refresh(proxy);
    }

    private static boolean stopped(MtrTrainSnapshot snapshot) {
        return snapshot != null && Math.abs(snapshot.speed()) < .00002;
    }

    private static Vec3 nearestDoor(Mob unit, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        long now = System.currentTimeMillis();
        DOOR_RESERVATIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        List<DoorCandidate> candidates = doors(unit, proxy, snapshot);
        DoorReservation old = DOOR_RESERVATIONS.get(unit.getUUID());
        if (old != null && old.proxyId.equals(proxy.getUUID())) {
            for (DoorCandidate candidate : candidates) if (candidate.key.equals(old.doorKey)) {
                DOOR_RESERVATIONS.put(unit.getUUID(), reservation(proxy, candidate, now));
                publishBoardingTarget(unit, proxy, candidate.position, now);
                trace(unit, proxy, "door-reservation-reused", candidate.position, snapshot,
                        "doorKey=" + candidate.key + " car=" + candidate.car + " local=(" + candidate.x + ','
                                + candidate.y + ',' + candidate.z + ") doorBoxWorld=" + candidate.boxPosition
                                + " standingRoom=" + hasStandingRoom(unit, candidate.position)
                                + " geometry=" + candidate.geometry);
                return candidate.position;
            }
        }

        DoorCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (DoorCandidate candidate : candidates) {
            long sharing = DOOR_RESERVATIONS.entrySet().stream().filter(entry -> !entry.getKey().equals(unit.getUUID())
                    && entry.getValue().proxyId.equals(proxy.getUUID()) && entry.getValue().doorKey.equals(candidate.key)).count();
            double score = unit.distanceToSqr(candidate.position) + sharing * 16;
            if (!hasStandingRoom(unit, candidate.position)) score += 10_000;
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        if (best == null) return proxy.position();
        DOOR_RESERVATIONS.put(unit.getUUID(), reservation(proxy, best, now));
        publishBoardingTarget(unit, proxy, best.position, now);
        trace(unit, proxy, "door-selected", best.position, snapshot,
                "candidateCount=" + candidates.size() + " doorKey=" + best.key + " car=" + best.car
                        + " local=(" + best.x + "," + best.y + "," + best.z + ") standingRoom="
                        + hasStandingRoom(unit, best.position) + " doorBoxWorld=" + best.boxPosition
                        + " geometry=" + best.geometry);
        return best.position;
    }

    private static List<DoorCandidate> doors(Mob unit, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        List<DoorCandidate> result = new ArrayList<>();
        for (int carIndex = 0; carIndex < snapshot.cars().size(); carIndex++) {
            MtrTrainSnapshot.CarPose car = snapshot.cars().get(carIndex);
            List<MtrDoorGeometryService.DoorBox> reported = MtrDoorGeometryService.doors(proxy, carIndex);
            if (!reported.isEmpty()) {
                for (int doorIndex = 0; doorIndex < reported.size(); doorIndex++) {
                    MtrDoorGeometryService.DoorBox door = reported.get(doorIndex);
                    double outside = door.centerX() + door.side() * Math.max(.55D, unit.getBbWidth() * .5D + .25D);
                    Vec3 boxPoint = localToWorld(car, outside, Math.max(.75D, door.maxY()), door.centerZ());
                    Vec3 point = standingPoint(unit, boxPoint);
                    result.add(new DoorCandidate(carIndex + ":real:" + doorIndex, point, carIndex,
                            door.centerX(), Math.max(.75D, door.maxY()), door.centerZ(), boxPoint, door.toString()));
                }
                continue;
            }
            int doorGroups = Math.max(1, Math.min(4, (int) Math.round(car.length() / 7D)));
            double usableHalfLength = Math.max(0, car.length() * .5 - 2);
            double outside = car.width() * .5 + Math.max(.75, unit.getBbWidth() * .5 + .25);
            for (int door = 0; door < doorGroups; door++) {
                double forward = doorGroups == 1 ? 0 : -usableHalfLength + door * (usableHalfLength * 2 / (doorGroups - 1));
                for (int sideSign : new int[]{-1, 1}) {
                    double lateral = outside * sideSign;
                    Vec3 boxPoint = localToWorld(car, lateral, .9D, forward);
                    Vec3 point = standingPoint(unit, boxPoint);
                    result.add(new DoorCandidate(carIndex + ":" + door + ":" + sideSign, point, carIndex,
                            car.width() * .5D * sideSign, .9D, forward, boxPoint, "fallback"));
                }
            }
        }
        return result;
    }

    private static Vec3 localToWorld(MtrTrainSnapshot.CarPose car, double lateral, double up, double forward) {
        double sinPitch = Math.sin(car.pitch()), cosPitch = Math.cos(car.pitch());
        double worldY = up * cosPitch + forward * sinPitch;
        double rotatedForward = -up * sinPitch + forward * cosPitch;
        double sinYaw = Math.sin(car.yaw()), cosYaw = Math.cos(car.yaw());
        return new Vec3(car.x() + lateral * cosYaw + rotatedForward * sinYaw, car.y() + worldY,
                car.z() - lateral * sinYaw + rotatedForward * cosYaw);
    }

    private static boolean hasStandingRoom(Mob unit, Vec3 point) {
        Vec3 delta = point.subtract(unit.position());
        if (!unit.level().noCollision(unit, unit.getBoundingBox().move(delta))) return false;
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(point.x, point.y - .2, point.z);
        return !unit.level().getBlockState(below).getCollisionShape(unit.level(), below).isEmpty();
    }

    /** Projects the model doorway's upper marker onto a real collision surface where the mob's feet can stand. */
    private static Vec3 standingPoint(Mob unit, Vec3 doorwayPoint) {
        int startY = net.minecraft.util.Mth.floor(doorwayPoint.y + 1D);
        int endY = Math.max(unit.level().getMinBuildHeight(), net.minecraft.util.Mth.floor(doorwayPoint.y - 4D));
        for (int y = startY; y >= endY; y--) {
            net.minecraft.core.BlockPos floor = net.minecraft.core.BlockPos.containing(doorwayPoint.x, y, doorwayPoint.z);
            net.minecraft.world.phys.shapes.VoxelShape shape = unit.level().getBlockState(floor)
                    .getCollisionShape(unit.level(), floor);
            if (shape.isEmpty()) continue;
            double standingY = floor.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            Vec3 candidate = new Vec3(doorwayPoint.x, standingY, doorwayPoint.z);
            Vec3 move = candidate.subtract(unit.position());
            if (unit.level().noCollision(unit, unit.getBoundingBox().move(move))) return candidate;
        }
        return new Vec3(doorwayPoint.x, unit.getY(), doorwayPoint.z);
    }

    /** Navigation reaches the floor in front of the doorway, while MTR's doorway point is one block higher. */
    private static boolean atDoor(Mob unit, Vec3 doorway) {
        double dx = doorway.x - unit.getX();
        double dz = doorway.z - unit.getZ();
        return dx * dx + dz * dz <= 2.25D && Math.abs(doorway.y - unit.getY()) <= 1.5D;
    }

    private static DoorReservation reservation(MtrTrainProxyEntity proxy, DoorCandidate candidate, long now) {
        return new DoorReservation(proxy.getUUID(), candidate.key, now + DOOR_RESERVATION_MS,
                candidate.car, candidate.x, candidate.y, candidate.z);
    }

    private static void publishBoardingTarget(Mob unit, MtrTrainProxyEntity proxy, Vec3 position, long now) {
        Long previous = TARGET_SYNC_AT.get(unit.getUUID());
        if (previous != null && now - previous < 500L) return;
        TARGET_SYNC_AT.put(unit.getUUID(), now);
        MtrCompatNetwork.syncBoardingTarget(proxy, unit.getUUID(), position);
    }

    private static void traceCommand(Mob unit, MtrTrainProxyEntity proxy, Vec3 doorway, MtrTrainSnapshot snapshot) {
        long serial = unit.getPersistentData().getLong("DominionVehicleBoardSerial");
        Long previous = OBSERVED_COMMANDS.put(unit.getUUID(), serial);
        if (previous != null && previous == serial) return;
        long issuedTick = unit.getPersistentData().getLong("DominionVehicleBoardIssuedTick");
        trace(unit, proxy, "command-observed-" + serial, doorway, snapshot,
                "commandSerial=" + serial + " issuedTick=" + issuedTick
                        + " ageTicks=" + Math.max(0L, unit.level().getGameTime() - issuedTick));
    }

    private record DoorCandidate(String key, Vec3 position, int car, double x, double y, double z,
                                 Vec3 boxPosition, String geometry) {}
    private record DoorReservation(UUID proxyId, String doorKey, long expiresAt, int car, double x, double y, double z) {}

    public static MtrTrainSnapshot.Stop resolveForwardStop(MtrTrainProxyEntity proxy, Vec3 target) {
        if (target == null) return null;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null) return null;
        return snapshot.stops().stream().filter(stop -> stop.progress() > snapshot.railProgress() + .5)
                .filter(stop -> distanceSqr(stop.point(), target) <= 24D * 24D)
                .min((a, b) -> Double.compare(distanceSqr(a.point(), target), distanceSqr(b.point(), target))).orElse(null);
    }

    public static List<Vec3> route(MtrTrainSnapshot snapshot, MtrTrainSnapshot.Stop stop) {
        List<Vec3> raw = new ArrayList<>();
        for (MtrTrainSnapshot.PathPart part : snapshot.path()) {
            if (part.endProgress() < snapshot.railProgress() || part.index() > stop.pathIndex()) continue;
            int from = 0;
            if (snapshot.railProgress() > part.startProgress()) {
                double ratio = (snapshot.railProgress() - part.startProgress()) / Math.max(.001, part.endProgress() - part.startProgress());
                from = Math.max(0, Math.min(part.points().size() - 1, (int) Math.floor(ratio * part.points().size())));
            }
            for (int i = from; i < part.points().size(); i++) {
                MtrTrainSnapshot.Point p = part.points().get(i);
                raw.add(new Vec3(p.x(), p.y(), p.z()));
            }
        }
        if (raw.size() <= 96) return List.copyOf(raw);
        List<Vec3> result = new ArrayList<>(96);
        result.add(raw.get(0));
        for (int i = 1; i < 95; i++) result.add(raw.get((int) Math.round(i * (raw.size() - 1) / 95D)));
        result.add(raw.get(raw.size() - 1));
        return List.copyOf(result);
    }

    private static double distanceSqr(MtrTrainSnapshot.Point point, Vec3 target) {
        double dx = point.x() - target.x, dy = point.y() - target.y, dz = point.z() - target.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void trace(Mob unit, MtrTrainProxyEntity proxy, String stage, Vec3 doorway,
                              MtrTrainSnapshot snapshot, String details) {
        if (unit == null || proxy == null || doorway == null) return;
        long now = System.currentTimeMillis();
        String key = unit.getUUID() + ":" + stage;
        long interval = "board-result".equals(stage) || "final-door-snap".equals(stage) ? 0L : 1_000L;
        Long previous = TRACE_AT.get(key);
        if (previous != null && now - previous < interval) return;
        TRACE_AT.put(key, now);
        Path path = unit.getNavigation().getPath();
        String pathState = path == null ? "none" : "target=" + path.getTarget() + ",node="
                + path.getNextNodeIndex() + "/" + path.getNodeCount() + ",done=" + unit.getNavigation().isDone();
        Vec3 delta = doorway.subtract(unit.position());
        Vec3 velocity = unit.getDeltaMovement();
        Vec3 move = doorway.subtract(unit.position());
        boolean targetCollisionFree = unit.level().noCollision(unit, unit.getBoundingBox().move(move));
        DoorReservation reservation = DOOR_RESERVATIONS.get(unit.getUUID());
        double horizontalDistanceSqr = delta.x * delta.x + delta.z * delta.z;
        long commandSerial = unit.getPersistentData().getLong("DominionVehicleBoardSerial");
        long issuedTick = unit.getPersistentData().getLong("DominionVehicleBoardIssuedTick");
        LOGGER.info("MTR BOARD TRACE stage={} unit={} type={} proxy={} commandSerial={} commandAgeTicks={} world=({}, {}, {}) door=({}, {}, {}) delta=({}, {}, {}) distanceSqr={} horizontalDistanceSqr={} velocity=({}, {}, {}) path=[{}] horizontalCollision={} verticalCollision={} targetCollisionFree={} snapshotSpeed={} snapshotDoors={} reservation={} {}",
                stage, unit.getUUID(), unit.getType(), proxy.getUUID(), commandSerial,
                Math.max(0L, unit.level().getGameTime() - issuedTick), unit.getX(), unit.getY(), unit.getZ(),
                doorway.x, doorway.y, doorway.z, delta.x, delta.y, delta.z, unit.distanceToSqr(doorway), horizontalDistanceSqr,
                velocity.x, velocity.y, velocity.z, pathState, unit.horizontalCollision, unit.verticalCollision,
                targetCollisionFree, snapshot == null ? Double.NaN : snapshot.speed(),
                snapshot == null ? 0 : snapshot.doorMultiplier(), reservation, details);
    }
}
