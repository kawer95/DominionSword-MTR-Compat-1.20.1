package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.mtr.bridge.MtrNativeRidingState;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCarTransform;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCollisionGeometry;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Periodically ejects living entities that are physically inside a train but are not logical passengers. */
public final class MtrInteriorSafetyService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long GRACE_TICKS = 10L;
    private static final Map<InsideKey, Long> INSIDE_SINCE = new HashMap<>();

    private MtrInteriorSafetyService() {}

    public static void tick(ServerLevel level, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        if (snapshot == null || snapshot.cars().isEmpty()) return;
        long now = level.getGameTime();
        Set<InsideKey> seen = new HashSet<>();
        Set<UUID> nonPlayerSnapshotRiders = new HashSet<>();
        for (MtrTrainSnapshot.Rider rider : snapshot.riders()) {
            if (level.getServer().getPlayerList().getPlayer(rider.uuid()) == null) nonPlayerSnapshotRiders.add(rider.uuid());
        }

        for (int carIndex = 0; carIndex < snapshot.cars().size(); carIndex++) {
            MtrTrainSnapshot.CarPose car = snapshot.cars().get(carIndex);
            MtrTrainCarTransform transform = MtrTrainCarTransform.of(car);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, transform.worldBounds(),
                    candidate -> eligible(candidate, proxy, nonPlayerSnapshotRiders))) {
                if (!inside(entity, transform, car)) continue;
                InsideKey key = new InsideKey(proxy.getUUID(), entity.getUUID());
                seen.add(key);
                long since = INSIDE_SINCE.computeIfAbsent(key, ignored -> now);
                if (now - since < GRACE_TICKS) continue;
                Vec3 exit = findExit(entity, snapshot, carIndex, transform);
                Vec3 before = entity.position();
                MtrTrainCollisionService.grantTemporaryImmunity(entity, 20);
                if (entity instanceof ServerPlayer player) {
                    player.connection.teleport(exit.x, exit.y, exit.z, player.getYRot(), player.getXRot());
                } else {
                    entity.teleportTo(exit.x, exit.y, exit.z);
                }
                entity.setDeltaMovement(Vec3.ZERO);
                entity.fallDistance = 0;
                INSIDE_SINCE.remove(key);
                LOGGER.info("MTR INTERIOR SAFETY TRACE entity={} type={} proxy={} car={} from=({}, {}, {}) exit=({}, {}, {})",
                        entity.getUUID(), entity.getType(), proxy.getUUID(), carIndex,
                        before.x, before.y, before.z, exit.x, exit.y, exit.z);
            }
        }
        INSIDE_SINCE.entrySet().removeIf(entry -> entry.getKey().proxyId.equals(proxy.getUUID()) && !seen.contains(entry.getKey()));
    }

    public static void clear(MtrTrainProxyEntity proxy) {
        INSIDE_SINCE.keySet().removeIf(key -> key.proxyId.equals(proxy.getUUID()));
    }

    public static void clearAll() { INSIDE_SINCE.clear(); }

    private static boolean eligible(LivingEntity entity, MtrTrainProxyEntity proxy, Set<UUID> nonPlayerSnapshotRiders) {
        if (!entity.isAlive() || entity.noPhysics || entity.isSpectator() || proxy.hasPassenger(entity)) return false;
        if (entity instanceof ServerPlayer player && MtrNativeRidingState.isPlayerRiding(player.getUUID())) return false;
        return !nonPlayerSnapshotRiders.contains(entity.getUUID());
    }

    private static boolean inside(Entity entity, MtrTrainCarTransform transform, MtrTrainSnapshot.CarPose car) {
        Vec3 local = transform.toLocal(entity.position());
        double radius = entity.getBbWidth() * .5D;
        return local.y + entity.getBbHeight() > MtrTrainCollisionGeometry.FLOOR_TOP
                && local.y < MtrTrainCollisionGeometry.TOP
                && Math.abs(local.x) < Math.max(.6D, car.width() * .5D) - Math.min(.2D, radius * .5D)
                && Math.abs(local.z) < Math.max(1D, car.length() * .5D) - Math.min(.2D, radius * .5D);
    }

    private static Vec3 findExit(Entity entity, MtrTrainSnapshot snapshot, int carIndex, MtrTrainCarTransform transform) {
        MtrTrainSnapshot.CarPose car = snapshot.cars().get(carIndex);
        Vec3 local = transform.toLocal(entity.position());
        double radius = entity.getBbWidth() * .5D;
        double halfWidth = Math.max(.6D, car.width() * .5D);
        double halfLength = Math.max(1D, car.length() * .5D);
        List<Vec3> candidates = new ArrayList<>();
        for (double extra : new double[]{.35D, .75D, 1.5D, 2.5D}) {
            for (double yOffset : new double[]{0D, .5D, 1D, -.5D}) {
                candidates.add(transform.toWorld(new Vec3(-(halfWidth + radius + extra), local.y + yOffset, local.z)));
                candidates.add(transform.toWorld(new Vec3(halfWidth + radius + extra, local.y + yOffset, local.z)));
                candidates.add(transform.toWorld(new Vec3(local.x, local.y + yOffset, -(halfLength + radius + extra))));
                candidates.add(transform.toWorld(new Vec3(local.x, local.y + yOffset, halfLength + radius + extra)));
            }
        }
        candidates.sort(Comparator.comparingDouble(entity.position()::distanceToSqr));
        return candidates.stream().filter(candidate -> outsideEntireTrain(candidate, entity, snapshot))
                .filter(candidate -> entity.level().noCollision(entity,
                        entity.getBoundingBox().move(candidate.subtract(entity.position()))))
                .findFirst().orElseGet(() -> transform.toWorld(new Vec3(
                        halfWidth + radius + 2.5D, Math.max(local.y, MtrTrainCollisionGeometry.FLOOR_TOP), local.z)));
    }

    private static boolean outsideEntireTrain(Vec3 feet, Entity entity, MtrTrainSnapshot snapshot) {
        double radius = entity.getBbWidth() * .5D;
        for (MtrTrainSnapshot.CarPose car : snapshot.cars()) {
            Vec3 local = MtrTrainCarTransform.of(car).toLocal(feet);
            if (local.y + entity.getBbHeight() > MtrTrainCollisionGeometry.BOTTOM
                    && local.y < MtrTrainCollisionGeometry.TOP
                    && Math.abs(local.x) < Math.max(.6D, car.width() * .5D) + radius
                    && Math.abs(local.z) < Math.max(1D, car.length() * .5D) + radius) return false;
        }
        return true;
    }

    private record InsideKey(UUID proxyId, UUID entityId) {}
}
