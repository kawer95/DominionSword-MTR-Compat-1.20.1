package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCarTransform;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCollisionGeometry;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Handles the exact MTR dismount packet before the synthetic train hull can trap the player. */
public final class MtrPlayerDismountService {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int COLLISION_IMMUNITY_TICKS = 20;

    private MtrPlayerDismountService() {}

    public static void onMtrPacketDismount(ServerPlayer player) {
        if (player == null) return;
        MtrTrainCollisionService.grantDismountImmunity(player, COLLISION_IMMUNITY_TICKS);
        LOGGER.info("MTR DISMOUNT TRACE stage=mtr-packet player={} shift={} immunityTicks={}",
                player.getUUID(), player.isShiftKeyDown(), COLLISION_IMMUNITY_TICKS);
        // Fallback for clients where the compatibility intent packet is delayed or unavailable.
        if (player.isShiftKeyDown()) findRiddenSnapshot(player).ifPresent(match -> forceOutside(player, match.snapshot, match.rider));
    }

    public static void onClientDismountIntent(ServerPlayer player, long sidingId, long vehicleId,
                                               int carIndex, boolean forcedByShift) {
        if (player == null) return;
        MtrTrainCollisionService.grantDismountImmunity(player, COLLISION_IMMUNITY_TICKS);
        if (!forcedByShift || !player.isShiftKeyDown()) return;
        MtrTrainSnapshot snapshot = MtrSnapshotBridge.get(player.level().dimension().location().toString(), sidingId, vehicleId);
        if (snapshot == null || System.currentTimeMillis() - snapshot.capturedAt() > 10_000L) return;
        MtrTrainSnapshot.Rider rider = snapshot.riders().stream()
                .filter(value -> value.uuid().equals(player.getUUID()))
                .findFirst().orElse(null);
        LOGGER.info("MTR DISMOUNT TRACE stage=client-intent player={} siding={} vehicle={} car={} forcedShift={} serverShift={} snapshot={} rider={}",
                player.getUUID(), sidingId, vehicleId, carIndex, forcedByShift, player.isShiftKeyDown(), snapshot != null, rider != null);
        if (rider == null || rider.car() != carIndex) return;
        forceOutside(player, snapshot, rider);
    }

    private static java.util.Optional<RiderMatch> findRiddenSnapshot(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        return MtrSnapshotBridge.latest().values().stream()
                .filter(snapshot -> MtrSnapshotBridge.normalizeDimension(snapshot.dimension()).equals(dimension))
                .filter(snapshot -> System.currentTimeMillis() - snapshot.capturedAt() <= 10_000L)
                .flatMap(snapshot -> snapshot.riders().stream()
                        .filter(rider -> rider.uuid().equals(player.getUUID()))
                        .map(rider -> new RiderMatch(snapshot, rider)))
                .findFirst();
    }

    private static void forceOutside(ServerPlayer player, MtrTrainSnapshot snapshot, MtrTrainSnapshot.Rider rider) {
        int carIndex = (int) rider.car();
        if (carIndex < 0 || carIndex >= snapshot.cars().size()) return;
        MtrTrainSnapshot.CarPose car = snapshot.cars().get(carIndex);
        MtrTrainCarTransform transform = MtrTrainCarTransform.of(car);
        Vec3 local = transform.toLocal(player.position());
        double radius = player.getBbWidth() * .5D;
        double halfWidth = Math.max(.6D, car.width() * .5D);
        double halfLength = Math.max(1D, car.length() * .5D);
        double localZ = Mth.clamp(local.z, -halfLength + radius + .2D, halfLength - radius - .2D);

        List<Vec3> candidates = new ArrayList<>();
        int preferredSide = local.x < 0 ? -1 : 1;
        for (int side : new int[]{preferredSide, -preferredSide}) {
            for (double extra : new double[]{.35D, .75D, 1.25D, 2D}) {
                for (double yOffset : new double[]{0D, .5D, 1D, -.5D}) {
                    candidates.add(transform.toWorld(new Vec3(side * (halfWidth + radius + extra), local.y + yOffset, localZ)));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(player.position()::distanceToSqr));
        Vec3 exit = candidates.stream().filter(candidate -> outsideEntireTrain(candidate, player, snapshot))
                .filter(candidate -> player.level().noCollision(player, player.getBoundingBox().move(candidate.subtract(player.position()))))
                .findFirst().orElseGet(() -> transform.toWorld(new Vec3(
                        preferredSide * (halfWidth + radius + 2D), Math.max(local.y, MtrTrainCollisionGeometry.FLOOR_TOP), localZ)));

        Vec3 before = player.position();
        player.connection.teleport(exit.x, exit.y, exit.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        LOGGER.info("MTR DISMOUNT TRACE forcedShift=true player={} siding={} vehicle={} car={} from=({}, {}, {}) exit=({}, {}, {})",
                player.getUUID(), snapshot.sidingId(), snapshot.vehicleId(), carIndex,
                before.x, before.y, before.z, exit.x, exit.y, exit.z);
    }

    private static boolean outsideEntireTrain(Vec3 feet, ServerPlayer player, MtrTrainSnapshot snapshot) {
        double radius = player.getBbWidth() * .5D;
        for (MtrTrainSnapshot.CarPose car : snapshot.cars()) {
            Vec3 local = MtrTrainCarTransform.of(car).toLocal(feet);
            double halfWidth = Math.max(.6D, car.width() * .5D) + radius;
            double halfLength = Math.max(1D, car.length() * .5D) + radius;
            boolean verticalOverlap = local.y + player.getBbHeight() > MtrTrainCollisionGeometry.BOTTOM
                    && local.y < MtrTrainCollisionGeometry.TOP;
            if (verticalOverlap && Math.abs(local.x) < halfWidth && Math.abs(local.z) < halfLength) return false;
        }
        return true;
    }

    private record RiderMatch(MtrTrainSnapshot snapshot, MtrTrainSnapshot.Rider rider) {}
}
