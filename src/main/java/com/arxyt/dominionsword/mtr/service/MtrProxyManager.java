package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.arxyt.dominionsword.mtr.registry.MtrCompatEntities;
import com.arxyt.dominionsword.mtr.persistence.MtrPassengerSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber
public final class MtrProxyManager {
    private static final long IDLE_REMOVE_MS = 10_000;
    private static final double MATERIALIZE_RADIUS_SQR = 192D * 192D;
    private static final Map<UUID, MtrTrainSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_NEAR = new HashMap<>();
    private static final Map<UUID, Set<Long>> CHUNK_TICKETS = new HashMap<>();
    private static final TicketType<UUID> OCCUPIED_TRAIN = TicketType.create("dominionsword_mtr_occupied_train", Comparator.comparing(UUID::toString), 300);

    private MtrProxyManager() {}

    public static MtrTrainSnapshot snapshot(MtrTrainProxyEntity proxy) { return SNAPSHOTS.get(proxy.getUUID()); }

    public static UUID proxyUuid(String dimension, long sidingId, long vehicleId) {
        return UUID.nameUUIDFromBytes(("dominionsword-mtr:" + MtrSnapshotBridge.normalizeDimension(dimension)
                + ':' + sidingId + ':' + vehicleId).getBytes(StandardCharsets.UTF_8));
    }

    /** Resolves or immediately materializes the authoritative proxy for a validated direct MTR click. */
    public static MtrTrainProxyEntity forInteraction(ServerPlayer player, long sidingId, long vehicleId) {
        if (player == null) return null;
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        UUID id = proxyUuid(dimension, sidingId, vehicleId);
        Entity existing = level.getEntity(id);
        if (existing instanceof MtrTrainProxyEntity proxy) return proxy;
        MtrTrainSnapshot value = MtrSnapshotBridge.get(dimension, sidingId, vehicleId);
        if (value == null || value.cars().isEmpty() || System.currentTimeMillis() - value.capturedAt() > IDLE_REMOVE_MS) return null;
        MtrTrainProxyEntity proxy = MtrCompatEntities.TRAIN_PROXY.get().create(level);
        if (proxy == null) return null;
        proxy.setUUID(id);
        proxy.bind(sidingId, vehicleId);
        if (!level.addFreshEntity(proxy)) return null;
        SNAPSHOTS.put(id, value);
        LAST_NEAR.put(id, System.currentTimeMillis());
        updateProxy(proxy, value);
        return proxy;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof MtrTrainProxyEntity proxy) {
            LAST_NEAR.put(proxy.getUUID(), System.currentTimeMillis());
            String dimension = event.getLevel().dimension().location().toString();
            MtrTrainSnapshot value = MtrSnapshotBridge.get(dimension, proxy.sidingId(), proxy.vehicleId());
            if (value != null) SNAPSHOTS.put(proxy.getUUID(), value);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        Map<UUID, MtrTrainProxyEntity> loaded = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) if (entity instanceof MtrTrainProxyEntity proxy) loaded.put(proxy.getUUID(), proxy);
        }

        for (MtrTrainSnapshot value : MtrSnapshotBridge.latest().values()) {
            if (now - value.capturedAt() > IDLE_REMOVE_MS) continue;
            ServerLevel level = findLevel(server, value.dimension());
            if (level == null || value.cars().isEmpty()) continue;
            UUID id = proxyUuid(value.dimension(), value.sidingId(), value.vehicleId());
            MtrTrainProxyEntity proxy = loaded.get(id);
            boolean near = nearPlayer(level, value);
            boolean retained = proxy != null && (!proxy.getPassengers().isEmpty() || proxy.getPersistentData().hasUUID("dominionsword_controller_player"));
            boolean awaitingPassengers = MtrPassengerSavedData.get(server).hasMatch(value);
            if (near || retained || awaitingPassengers) {
                LAST_NEAR.put(id, now);
                if (proxy == null) {
                    proxy = MtrCompatEntities.TRAIN_PROXY.get().create(level);
                    if (proxy == null) continue;
                    proxy.setUUID(id);
                    proxy.bind(value.sidingId(), value.vehicleId());
                    level.addFreshEntity(proxy);
                    loaded.put(id, proxy);
                }
                SNAPSHOTS.put(id, value);
                updateProxy(proxy, value);
                MtrPassengerSavedData.get(server).restore(server, level, proxy, value);
                updateChunkTickets(proxy);
                MtrControlService.tick(server, proxy, value);
                MtrTrainCollisionService.tick(level, proxy, value);
                MtrInteriorSafetyService.tick(level, proxy, value);
            }
        }

        for (MtrTrainProxyEntity proxy : loaded.values()) {
            MtrTrainSnapshot value = SNAPSHOTS.get(proxy.getUUID());
            boolean stale = value == null ? now - LAST_NEAR.getOrDefault(proxy.getUUID(), now) > IDLE_REMOVE_MS
                    : now - value.capturedAt() > IDLE_REMOVE_MS;
            boolean retained = !proxy.getPassengers().isEmpty() || proxy.getPersistentData().hasUUID("dominionsword_controller_player");
            long lastNear = LAST_NEAR.getOrDefault(proxy.getUUID(), 0L);
            if (stale || (!retained && now - lastNear > IDLE_REMOVE_MS)) removeSafely(proxy, value);
        }
    }

    private static void updateProxy(MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        MtrTrainSnapshot.CarPose head = snapshot.cars().get(0);
        proxy.setPos(head.x(), head.y(), head.z());
        proxy.setYRot((float) Math.toDegrees(head.yaw()));
        proxy.updateTrainSelectionBounds(snapshot);
        int seats = 1;
        for (MtrTrainSnapshot.CarPose car : snapshot.cars()) seats += Math.max(2, (int) Math.floor(Math.max(2, car.length() - 2) / 1.35) * 2);
        proxy.seatCount(Math.min(MtrTrainProxyEntity.MAX_SEATS, seats));
        proxy.refreshDimensions();
    }

    private static boolean nearPlayer(ServerLevel level, MtrTrainSnapshot snapshot) {
        for (ServerPlayer player : level.players()) for (MtrTrainSnapshot.CarPose car : snapshot.cars())
            if (player.distanceToSqr(car.x(), car.y(), car.z()) <= MATERIALIZE_RADIUS_SQR) return true;
        return false;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(MtrSnapshotBridge.normalizeDimension(dimension));
        if (id == null) return null;
        for (ServerLevel level : server.getAllLevels()) if (level.dimension().location().equals(id)) return level;
        return null;
    }

    private static void removeSafely(MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        Vec3 safe = proxy.position();
        if (snapshot != null && !snapshot.stops().isEmpty()) {
            MtrTrainSnapshot.Stop stop = snapshot.stops().stream().min((a, b) -> Double.compare(
                    Math.abs(a.progress() - snapshot.railProgress()), Math.abs(b.progress() - snapshot.railProgress()))).orElse(null);
            if (stop != null) safe = new Vec3(stop.point().x(), stop.point().y() + 1, stop.point().z());
        }
        for (Entity passenger : new java.util.ArrayList<>(proxy.getPassengers())) {
            int seat = proxy.seatFor(passenger);
            boolean escrowed = proxy.getServer() != null
                    && MtrPassengerSavedData.get(proxy.getServer()).escrow(proxy, snapshot, passenger, seat);
            passenger.stopRiding();
            if (escrowed) passenger.discard();
            else {
                passenger.teleportTo(safe.x, safe.y, safe.z);
                passenger.setNoGravity(false);
                if (passenger instanceof Mob mob) mob.setNoAi(false);
            }
        }
        if (proxy.getServer() != null) MtrControlService.release(proxy.getServer(), proxy, snapshot);
        releaseChunkTickets(proxy);
        MtrControlService.clear(proxy);
        MtrDoorGeometryService.clear(proxy);
        MtrTrainCollisionService.clear(proxy);
        MtrInteriorSafetyService.clear(proxy);
        SNAPSHOTS.remove(proxy.getUUID());
        LAST_NEAR.remove(proxy.getUUID());
        proxy.discard();
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        java.util.List<MtrTrainProxyEntity> proxies = new java.util.ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) for (Entity entity : level.getAllEntities())
            if (entity instanceof MtrTrainProxyEntity proxy) proxies.add(proxy);
        for (MtrTrainProxyEntity proxy : proxies) removeSafely(proxy, SNAPSHOTS.get(proxy.getUUID()));
        SNAPSHOTS.clear();
        LAST_NEAR.clear();
        MtrSnapshotBridge.clear();
        MtrDoorGeometryService.clearAll();
        MtrTrainCollisionService.clearAll();
        MtrInteriorSafetyService.clearAll();
        com.arxyt.dominionsword.mtr.bridge.MtrNativeRidingState.clear();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        com.arxyt.dominionsword.mtr.bridge.MtrNativeRidingState.update(event.getEntity().getUUID(), false);
    }

    private static void updateChunkTickets(MtrTrainProxyEntity proxy) {
        if (!(proxy.level() instanceof ServerLevel level)) return;
        Set<Long> wanted = new HashSet<>();
        if (!proxy.getPassengers().isEmpty()) {
            wanted.add(new ChunkPos(proxy.blockPosition()).toLong());
            for (Entity passenger : proxy.getPassengers()) wanted.add(new ChunkPos(net.minecraft.core.BlockPos.containing(proxy.seatPosition(proxy.seatFor(passenger)))).toLong());
        }
        Set<Long> old = CHUNK_TICKETS.computeIfAbsent(proxy.getUUID(), id -> new HashSet<>());
        for (long packed : new HashSet<>(old)) if (!wanted.contains(packed)) {
            level.getChunkSource().removeRegionTicket(OCCUPIED_TRAIN, new ChunkPos(packed), 2, proxy.getUUID());
            old.remove(packed);
        }
        for (long packed : wanted) {
            ChunkPos pos = new ChunkPos(packed);
            level.getChunkSource().addRegionTicket(OCCUPIED_TRAIN, pos, 2, proxy.getUUID());
            old.add(packed);
        }
        if (old.isEmpty()) CHUNK_TICKETS.remove(proxy.getUUID());
    }

    private static void releaseChunkTickets(MtrTrainProxyEntity proxy) {
        if (!(proxy.level() instanceof ServerLevel level)) return;
        Set<Long> old = CHUNK_TICKETS.remove(proxy.getUUID());
        if (old != null) for (long packed : old)
            level.getChunkSource().removeRegionTicket(OCCUPIED_TRAIN, new ChunkPos(packed), 2, proxy.getUUID());
    }
}
