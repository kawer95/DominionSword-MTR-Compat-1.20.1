package com.arxyt.dominionsword.mtr.entity;

import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.service.MtrProxyManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MtrTrainProxyEntity extends Entity {
    public static final int MAX_SEATS = 128;
    private static final EntityDataAccessor<Long> SIDING = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> VEHICLE = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> SEATS = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> TARGET = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SEAT_MAP = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> BOUNDS_MIN_X = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOUNDS_MIN_Y = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOUNDS_MIN_Z = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOUNDS_MAX_X = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOUNDS_MAX_Y = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BOUNDS_MAX_Z = SynchedEntityData.defineId(MtrTrainProxyEntity.class, EntityDataSerializers.FLOAT);

    public MtrTrainProxyEntity(EntityType<? extends MtrTrainProxyEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setInvisible(true);
        getPersistentData().putBoolean("DominionExclusiveSelection", true);
        getPersistentData().putBoolean("DominionDisallowSquad", true);
    }

    @Override protected void defineSynchedData() {
        entityData.define(SIDING, 0L);
        entityData.define(VEHICLE, 0L);
        entityData.define(SEATS, 1);
        entityData.define(TARGET, "");
        entityData.define(SEAT_MAP, "");
        entityData.define(BOUNDS_MIN_X, -.5F);
        entityData.define(BOUNDS_MIN_Y, -.5F);
        entityData.define(BOUNDS_MIN_Z, -.5F);
        entityData.define(BOUNDS_MAX_X, .5F);
        entityData.define(BOUNDS_MAX_Y, 3.5F);
        entityData.define(BOUNDS_MAX_Z, .5F);
    }

    public void bind(long sidingId, long vehicleId) {
        entityData.set(SIDING, sidingId);
        entityData.set(VEHICLE, vehicleId);
        getPersistentData().putBoolean("DominionExclusiveSelection", true);
        getPersistentData().putBoolean("DominionDisallowSquad", true);
    }
    public long sidingId() { return entityData.get(SIDING); }
    public long vehicleId() { return entityData.get(VEHICLE); }
    public int seatCount() { return entityData.get(SEATS); }
    public void seatCount(int value) { entityData.set(SEATS, Math.max(1, Math.min(MAX_SEATS, value))); }
    public String targetKey() { return entityData.get(TARGET); }
    public void targetKey(String value) { entityData.set(TARGET, value == null ? "" : value); }

    public void updateTrainSelectionBounds(MtrTrainSnapshot snapshot) {
        AABB bounds = boundsFromSnapshot(snapshot);
        if (bounds == null) return;
        entityData.set(BOUNDS_MIN_X, (float) (bounds.minX - getX()));
        entityData.set(BOUNDS_MIN_Y, (float) (bounds.minY - getY()));
        entityData.set(BOUNDS_MIN_Z, (float) (bounds.minZ - getZ()));
        entityData.set(BOUNDS_MAX_X, (float) (bounds.maxX - getX()));
        entityData.set(BOUNDS_MAX_Y, (float) (bounds.maxY - getY()));
        entityData.set(BOUNDS_MAX_Z, (float) (bounds.maxZ - getZ()));
    }

    public AABB trainSelectionBounds() {
        AABB live = boundsFromSnapshot(MtrProxyManager.snapshot(this));
        if (live != null) return live;
        return new AABB(getX() + entityData.get(BOUNDS_MIN_X), getY() + entityData.get(BOUNDS_MIN_Y), getZ() + entityData.get(BOUNDS_MIN_Z),
                getX() + entityData.get(BOUNDS_MAX_X), getY() + entityData.get(BOUNDS_MAX_Y), getZ() + entityData.get(BOUNDS_MAX_Z));
    }

    public int seatFor(Entity passenger) {
        return seatAssignments().getOrDefault(passenger.getUUID(), Math.max(0, getPassengers().indexOf(passenger)));
    }
    public Entity passengerAt(int seat) {
        for (Entity passenger : getPassengers()) if (seatFor(passenger) == seat) return passenger;
        return null;
    }
    public boolean assignSeat(Entity passenger, int seat, boolean force) {
        if (seat < 0 || seat >= seatCount()) return false;
        Entity occupied = passengerAt(seat);
        if (occupied != null && occupied != passenger) {
            if (!force) return false;
            occupied.stopRiding();
            occupied.setNoGravity(false);
        }
        Map<UUID, Integer> map = seatAssignments();
        map.put(passenger.getUUID(), seat);
        writeSeatAssignments(map);
        return true;
    }
    public void clearSeat(Entity passenger) {
        Map<UUID, Integer> map = seatAssignments();
        map.remove(passenger.getUUID());
        writeSeatAssignments(map);
    }

    @Override public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0;
    }

    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengers().size() < seatCount(); }
    @Override protected void addPassenger(Entity passenger) {
        if (getPassengers().size() >= seatCount()) return;
        if (!seatAssignments().containsKey(passenger.getUUID())) {
            int seat = 0;
            while (seat < seatCount() && passengerAt(seat) != null) seat++;
            if (seat >= seatCount()) return;
            assignSeat(passenger, seat, false);
        }
        super.addPassenger(passenger);
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        clearSeat(passenger);
    }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(this);
        if (snapshot == null || snapshot.cars().isEmpty()) {
            move.accept(passenger, getX(), getY() + .5, getZ());
            return;
        }
        int seat = seatFor(passenger);
        SeatPose pose = seatPose(snapshot, seat);
        move.accept(passenger, pose.x, pose.y, pose.z);
        passenger.setYRot((float) Math.toDegrees(pose.yaw));
        passenger.setYHeadRot(passenger.getYRot());
    }

    public Vec3 seatPosition(int seat) {
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(this);
        if (snapshot == null || snapshot.cars().isEmpty()) return position();
        SeatPose pose = seatPose(snapshot, Math.max(0, seat));
        return new Vec3(pose.x, pose.y, pose.z);
    }

    private static SeatPose seatPose(MtrTrainSnapshot snapshot, int seat) {
        if (seat == 0) {
            MtrTrainSnapshot.CarPose car = snapshot.cars().get(0);
            return local(car, 0, Math.max(0, car.length() * .35), .5);
        }
        int remaining = seat - 1;
        for (MtrTrainSnapshot.CarPose car : snapshot.cars()) {
            int rows = Math.max(1, (int) Math.floor(Math.max(2, car.length() - 2) / 1.35));
            int count = rows * 2;
            if (remaining < count) {
                int row = remaining / 2;
                double lateral = (remaining % 2 == 0 ? -1 : 1) * Math.min(.65, car.width() * .28);
                double forward = -car.length() * .4 + row * (car.length() * .8 / Math.max(1, rows - 1));
                return local(car, lateral, forward, .35);
            }
            remaining -= count;
        }
        MtrTrainSnapshot.CarPose car = snapshot.cars().get(snapshot.cars().size() - 1);
        return local(car, 0, 0, .35);
    }

    private static SeatPose local(MtrTrainSnapshot.CarPose car, double lateral, double forward, double up) {
        double sin = Math.sin(car.yaw()), cos = Math.cos(car.yaw());
        return new SeatPose(car.x() + lateral * cos + forward * sin, car.y() + up,
                car.z() - lateral * sin + forward * cos, car.yaw());
    }

    @Override protected AABB makeBoundingBox() {
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(this);
        AABB bounds = boundsFromSnapshot(snapshot);
        return bounds == null ? super.makeBoundingBox() : bounds;
    }

    private static AABB boundsFromSnapshot(MtrTrainSnapshot snapshot) {
        if (snapshot == null || snapshot.cars().isEmpty()) return null;
        AABB box = null;
        for (MtrTrainSnapshot.CarPose car : snapshot.cars()) {
            AABB carBox = orientedCarBounds(car);
            box = box == null ? carBox : box.minmax(carBox);
        }
        return box;
    }

    /** Encloses the actual yaw/pitch-oriented MTR car dimensions without turning car length into lateral width. */
    private static AABB orientedCarBounds(MtrTrainSnapshot.CarPose car) {
        double halfWidth = Math.max(.05D, car.width() * .5D);
        double halfLength = Math.max(.05D, car.length() * .5D);
        AABB result = null;
        for (double lateral : new double[]{-halfWidth, halfWidth}) {
            for (double up : new double[]{-.5D, 3.5D}) {
                for (double forward : new double[]{-halfLength, halfLength}) {
                    Vec3 point = localPoint(car, lateral, up, forward);
                    AABB corner = new AABB(point, point);
                    result = result == null ? corner : result.minmax(corner);
                }
            }
        }
        return result;
    }

    private static Vec3 localPoint(MtrTrainSnapshot.CarPose car, double lateral, double up, double forward) {
        double sinPitch = Math.sin(car.pitch()), cosPitch = Math.cos(car.pitch());
        double worldY = up * cosPitch + forward * sinPitch;
        double rotatedForward = -up * sinPitch + forward * cosPitch;
        double sinYaw = Math.sin(car.yaw()), cosYaw = Math.cos(car.yaw());
        return new Vec3(car.x() + lateral * cosYaw + rotatedForward * sinYaw, car.y() + worldY,
                car.z() - lateral * sinYaw + rotatedForward * cosYaw);
    }

    @Override public Component getName() { return Component.translatable("entity.dominionsword_mtr_compat.train_proxy"); }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        bind(tag.getLong("MtrSiding"), tag.getLong("MtrVehicle"));
        seatCount(tag.getInt("MtrSeats"));
        targetKey(tag.getString("MtrTarget"));
        entityData.set(SEAT_MAP, tag.getString("MtrSeatMap"));
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("MtrSiding", sidingId());
        tag.putLong("MtrVehicle", vehicleId());
        tag.putInt("MtrSeats", seatCount());
        tag.putString("MtrTarget", targetKey());
        tag.putString("MtrSeatMap", entityData.get(SEAT_MAP));
    }

    private Map<UUID, Integer> seatAssignments() {
        Map<UUID, Integer> result = new HashMap<>();
        String value = entityData.get(SEAT_MAP);
        if (value.isEmpty()) return result;
        for (String entry : value.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) continue;
            try { result.put(UUID.fromString(parts[0]), Integer.parseInt(parts[1])); } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private void writeSeatAssignments(Map<UUID, Integer> map) {
        entityData.set(SEAT_MAP, map.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(";")));
    }

    private record SeatPose(double x, double y, double z, double yaw) {}
}
