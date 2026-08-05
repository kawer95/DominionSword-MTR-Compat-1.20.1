package com.arxyt.dominionsword.mtr.persistence;

import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server SavedData escrow for non-player train occupants. Records survive the gap between MTR
 * destroying/recreating its Java vehicle objects and the compatibility proxy being materialized.
 */
public final class MtrPassengerSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "dominionsword_mtr_passengers";
    private final Map<UUID, PassengerRecord> records = new LinkedHashMap<>();

    public static MtrPassengerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MtrPassengerSavedData::load, MtrPassengerSavedData::new, NAME);
    }

    /** Saves before dismount/discard so mod capabilities and AI state are captured intact. */
    public boolean escrow(MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot, Entity passenger, int seat) {
        if (snapshot == null || passenger instanceof Player || passenger.isRemoved()) return false;
        CompoundTag entityData = new CompoundTag();
        if (!passenger.saveAsPassenger(entityData)) {
            LOGGER.warn("Dominion Sword MTR could not serialize passenger {}", passenger.getUUID());
            return false;
        }
        records.put(passenger.getUUID(), new PassengerRecord(passenger.getUUID(), snapshot.dimension(),
                snapshot.sidingId(), snapshot.vehicleId(), snapshot.departureIndex(), snapshot.railProgress(),
                Math.max(0, seat), entityData));
        setDirty();
        return true;
    }

    public boolean hasMatch(MtrTrainSnapshot snapshot) {
        return records.values().stream().anyMatch(record -> record.matches(snapshot));
    }

    /** Restores existing loaded entities when possible, otherwise recreates the exact saved entity NBT. */
    public int restore(MinecraftServer server, ServerLevel level, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        int restored = 0;
        for (PassengerRecord record : new ArrayList<>(records.values())) {
            if (!record.matches(snapshot)) continue;
            Entity entity = findLoaded(server, record.uuid());
            boolean created = false;
            if (entity == null) {
                Optional<Entity> loaded = Util.ifElse(EntityType.by(record.entityData()).map(type -> type.create(level)),
                        candidate -> candidate.load(record.entityData().copy()),
                        () -> LOGGER.warn("Dominion Sword MTR skipped passenger with unknown entity id {}",
                                record.entityData().getString("id")));
                if (loaded.isEmpty()) continue;
                entity = loaded.get();
                entity.setPos(proxy.seatPosition(record.seat()));
                if (!level.addFreshEntity(entity)) continue;
                created = true;
            }
            if (entity.level() != level || entity instanceof Player || entity.isRemoved()) continue;
            int seat = Math.min(record.seat(), proxy.seatCount() - 1);
            entity.stopRiding();
            if (!proxy.assignSeat(entity, seat, false) || !entity.startRiding(proxy, true)) {
                if (created) entity.discard();
                continue;
            }
            entity.setNoGravity(true);
            records.remove(record.uuid());
            setDirty();
            restored++;
            LOGGER.info("Dominion Sword MTR restored passenger {} to siding={}, vehicle={}, seat={}",
                    record.uuid(), snapshot.sidingId(), snapshot.vehicleId(), seat);
        }
        return restored;
    }

    private static Entity findLoaded(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PassengerRecord record : records.values()) list.add(record.save());
        tag.put("Passengers", list);
        return tag;
    }

    private static MtrPassengerSavedData load(CompoundTag tag) {
        MtrPassengerSavedData data = new MtrPassengerSavedData();
        ListTag list = tag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            PassengerRecord record = PassengerRecord.load(list.getCompound(i));
            if (record != null) data.records.put(record.uuid(), record);
        }
        data.setDirty(false);
        return data;
    }

    private record PassengerRecord(UUID uuid, String dimension, long sidingId, long vehicleId,
                                   long departureIndex, double railProgress, int seat, CompoundTag entityData) {
        private boolean matches(MtrTrainSnapshot snapshot) {
            if (!MtrSnapshotBridge.normalizeDimension(dimension).equals(MtrSnapshotBridge.normalizeDimension(snapshot.dimension()))
                    || sidingId != snapshot.sidingId()) return false;
            if (vehicleId == snapshot.vehicleId()) return true;
            double progressDifference = Math.abs(railProgress - snapshot.railProgress());
            if (departureIndex >= 0) return departureIndex == snapshot.departureIndex() && progressDifference <= 32D;
            // Repairs occupants escrowed by 1.4.7 after MTR replaced its -1 departure vehicle at the same depot point.
            return snapshot.departureIndex() < 0 && progressDifference <= 4D;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Uuid", uuid);
            tag.putString("Dimension", dimension);
            tag.putLong("Siding", sidingId);
            tag.putLong("Vehicle", vehicleId);
            tag.putLong("Departure", departureIndex);
            tag.putDouble("Progress", railProgress);
            tag.putInt("Seat", seat);
            tag.put("Entity", entityData.copy());
            return tag;
        }

        private static PassengerRecord load(CompoundTag tag) {
            if (!tag.hasUUID("Uuid") || !tag.contains("Entity", Tag.TAG_COMPOUND)) return null;
            return new PassengerRecord(tag.getUUID("Uuid"), tag.getString("Dimension"), tag.getLong("Siding"),
                    tag.getLong("Vehicle"), tag.getLong("Departure"), tag.getDouble("Progress"),
                    Math.max(0, tag.getInt("Seat")), tag.getCompound("Entity"));
        }
    }
}
