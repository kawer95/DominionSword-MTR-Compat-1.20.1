package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.config.ServerConfig;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCarTransform;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCollisionGeometry;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainEntityCollision;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainRoofCarry;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Server-authoritative entity-versus-train hull contact pass. */
public final class MtrTrainCollisionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean LOGGED_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_CANDIDATE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_COLLISION = new AtomicBoolean();
    /** Frigatemod-style persistent local contact history; xo/yo/zo is only the first-frame fallback. */
    private static final Map<ContactKey, ContactState> LAST_LOCAL_POSITIONS = new HashMap<>();
    private static final Map<ContactKey, ContactState> LAST_DECK_POSITIONS = new HashMap<>();
    private static final Map<CarKey, MtrTrainCarTransform> LAST_CAR_TRANSFORMS = new HashMap<>();
    private static final Map<UUID, Long> COLLISION_IMMUNITY_UNTIL = new HashMap<>();

    private MtrTrainCollisionService() {}

    public static void tick(ServerLevel level, MtrTrainProxyEntity proxy, MtrTrainSnapshot snapshot) {
        if (!enabled() || snapshot.cars().isEmpty()) {
            clear(proxy);
            return;
        }
        long gameTime = level.getGameTime();
        COLLISION_IMMUNITY_UNTIL.entrySet().removeIf(entry -> entry.getValue() < gameTime);
        if (LOGGED_ACTIVE.compareAndSet(false, true)) LOGGER.info(
                "Dominion Sword MTR collision service active: dimension={}, proxy={}, cars={}, doors={}",
                level.dimension().location(), proxy.getUUID(), snapshot.cars().size(), snapshot.doorMultiplier());
        Set<UUID> carried = new HashSet<>();
        Set<CarKey> seenCars = new HashSet<>();
        for (int carIndex = 0; carIndex < snapshot.cars().size(); carIndex++) {
            MtrTrainSnapshot.CarPose car = snapshot.cars().get(carIndex);
            MtrTrainCarTransform transform = MtrTrainCarTransform.of(car);
            CarKey carKey = new CarKey(proxy.getUUID(), carIndex);
            seenCars.add(carKey);
            MtrTrainCarTransform previousTransform = LAST_CAR_TRANSFORMS.put(carKey, transform);
            List<MtrDoorGeometryService.DoorBox> reportedDoors = MtrDoorGeometryService.doors(proxy, carIndex);
            List<MtrTrainCollisionGeometry.DoorOpening> doors = MtrControlService.collisionOpenDoors(
                            proxy, snapshot, carIndex, reportedDoors).stream()
                    .map(door -> new MtrTrainCollisionGeometry.DoorOpening(door.side(), door.minZ(), door.maxZ())).toList();
            boolean doorsOpen = snapshot.doorMultiplier() > 0 || !doors.isEmpty();
            List<AABB> shell = MtrTrainCollisionGeometry.shell(car.width(), car.length(), doorsOpen, doors);
            AABB interactionBounds = previousTransform == null ? transform.worldBounds()
                    : transform.worldBounds().minmax(previousTransform.worldBounds());
            for (Entity entity : level.getEntities(proxy, interactionBounds, candidate -> eligible(candidate, proxy))) {
                if (previousTransform != null && entity instanceof LivingEntity living
                        && !carried.contains(entity.getUUID())
                        && carry(living, proxy.getUUID(), carIndex, previousTransform, transform, car, gameTime)) {
                    carried.add(entity.getUUID());
                }
                resolve(entity, proxy.getUUID(), carIndex, transform, shell, gameTime);
            }
        }
        LAST_LOCAL_POSITIONS.entrySet().removeIf(entry -> entry.getKey().proxyId.equals(proxy.getUUID())
                && gameTime - entry.getValue().lastSeenTick > 2L);
        LAST_DECK_POSITIONS.entrySet().removeIf(entry -> entry.getKey().proxyId.equals(proxy.getUUID())
                && gameTime - entry.getValue().lastSeenTick > 2L);
        LAST_CAR_TRANSFORMS.keySet().removeIf(key -> key.proxyId.equals(proxy.getUUID()) && !seenCars.contains(key));
    }

    public static void clear(MtrTrainProxyEntity proxy) {
        LAST_LOCAL_POSITIONS.keySet().removeIf(key -> key.proxyId.equals(proxy.getUUID()));
        LAST_DECK_POSITIONS.keySet().removeIf(key -> key.proxyId.equals(proxy.getUUID()));
        LAST_CAR_TRANSFORMS.keySet().removeIf(key -> key.proxyId.equals(proxy.getUUID()));
    }

    public static void clearAll() {
        LAST_LOCAL_POSITIONS.clear();
        LAST_DECK_POSITIONS.clear();
        LAST_CAR_TRANSFORMS.clear();
        COLLISION_IMMUNITY_UNTIL.clear();
        LOGGED_ACTIVE.set(false);
        LOGGED_CANDIDATE.set(false);
        LOGGED_COLLISION.set(false);
    }

    private static boolean enabled() {
        try {
            return ServerConfig.MTR_TRAIN_COLLISION.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean eligible(Entity entity, MtrTrainProxyEntity proxy) {
        if (!entity.isAlive() || entity.noPhysics || proxy.hasPassenger(entity)) return false;
        if (entity instanceof Player player && player.isSpectator()) return false;
        Long immunityUntil = COLLISION_IMMUNITY_UNTIL.get(entity.getUUID());
        if (immunityUntil != null && immunityUntil >= entity.level().getGameTime()) return false;
        return !(entity instanceof MtrTrainProxyEntity);
    }

    /** MTR has just detached this player; ignore both hull collision and roof carry for the full grace period. */
    public static void grantDismountImmunity(ServerPlayer player, int ticks) {
        grantTemporaryImmunity(player, ticks);
    }

    public static void grantTemporaryImmunity(Entity entity, int ticks) {
        if (entity == null || ticks <= 0) return;
        COLLISION_IMMUNITY_UNTIL.merge(entity.getUUID(), entity.level().getGameTime() + ticks, Math::max);
        LAST_LOCAL_POSITIONS.keySet().removeIf(key -> key.entityId.equals(entity.getUUID()));
        LAST_DECK_POSITIONS.keySet().removeIf(key -> key.entityId.equals(entity.getUUID()));
    }

    private static void resolve(Entity entity, UUID proxyId, int carIndex, MtrTrainCarTransform transform,
                                List<AABB> shell, long gameTime) {
        ContactKey key = new ContactKey(proxyId, carIndex, entity.getUUID());
        Vec3 current = transform.toLocal(entity.position());
        ContactState old = LAST_LOCAL_POSITIONS.get(key);
        Vec3 previous = old == null
                ? transform.toLocal(new Vec3(entity.xo, entity.yo, entity.zo))
                : old.position;
        if (LOGGED_CANDIDATE.compareAndSet(false, true)) LOGGER.info(
                "Dominion Sword MTR collision candidate: type={}, world=({}, {}, {}), local=({}, {}, {}), car={}, shellBoxes={}",
                entity.getType(), entity.getX(), entity.getY(), entity.getZ(), current.x, current.y, current.z,
                carIndex, shell.size());
        MtrTrainEntityCollision.Resolution resolution = MtrTrainEntityCollision.resolve(
                previous, current, entity.getBbWidth(), entity.getBbHeight(), shell);
        LAST_LOCAL_POSITIONS.put(key, new ContactState(resolution.position(), gameTime));
        if (!resolution.collided()) return;

        Vec3 target = transform.toWorld(resolution.position());
        Vec3 correction = target.subtract(entity.position());
        // Match frigatemod's predicted contact path: a network teleport here visibly rubber-bands
        // the local player even though the client has already resolved the same movement.
        entity.setPos(target.x, target.y, target.z);
        if (entity instanceof ServerPlayer player) {
            // Collision runs after packet movement; align the server listener's accepted baseline
            // without sending another position packet back to the already-predicted client.
            player.connection.resetPosition();
        }
        if (LOGGED_COLLISION.compareAndSet(false, true)) LOGGER.info(
                "Dominion Sword MTR collision resolved: type={}, car={}, correction=({}, {}, {})",
                entity.getType(), carIndex, correction.x, correction.y, correction.z);
        if (correction.lengthSqr() > 1.0E-10D) {
            Vec3 normal = correction.normalize();
            double intoHull = entity.getDeltaMovement().dot(normal);
            if (intoHull < 0) entity.setDeltaMovement(entity.getDeltaMovement().subtract(normal.scale(intoHull)));
        }
    }

    private static boolean carry(LivingEntity entity, UUID proxyId, int carIndex,
                                 MtrTrainCarTransform previousTransform, MtrTrainCarTransform currentTransform,
                                 MtrTrainSnapshot.CarPose car, long gameTime) {
        if (entity.isPassenger() || entity.isSpectator()) return false;
        ContactKey key = new ContactKey(proxyId, carIndex, entity.getUUID());
        Vec3 previousLocal = previousTransform.toLocal(entity.position());
        ContactState old = LAST_DECK_POSITIONS.get(key);
        Vec3 contact = MtrTrainRoofCarry.contact(previousLocal, old == null ? null : old.position,
                car.width(), car.length(), entity.getBbWidth(), entity.getDeltaMovement().y > 0.02D);
        if (contact == null) {
            LAST_DECK_POSITIONS.put(key, new ContactState(previousLocal, gameTime));
            return false;
        }
        Vec3 target = currentTransform.toWorld(contact);
        LAST_DECK_POSITIONS.put(key, new ContactState(contact, gameTime));
        entity.setPos(target.x, target.y, target.z);
        if (entity instanceof ServerPlayer player) player.connection.resetPosition();
        entity.fallDistance = 0.0F;
        entity.setOnGround(true);
        if (entity.getDeltaMovement().y < 0.0D) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
        }
        return true;
    }

    private record CarKey(UUID proxyId, int carIndex) {}
    private record ContactKey(UUID proxyId, int carIndex, UUID entityId) {}
    private record ContactState(Vec3 position, long lastSeenTick) {}
}
