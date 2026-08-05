package com.arxyt.dominionsword.mtr.service;

import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned, validated mirror of client-only MTR resource-pack doorway boxes. */
public final class MtrDoorGeometryService {
    private static final long GEOMETRY_TTL_MS = 30_000L;
    private static final double REPORT_RADIUS_SQR = 256D * 256D;
    private static final Map<UUID, Geometry> GEOMETRIES = new ConcurrentHashMap<>();

    private MtrDoorGeometryService() {}

    public static void accept(ServerPlayer player, MtrCompatNetwork.DoorGeometryPacket packet) {
        String dimension = player.level().dimension().location().toString();
        UUID proxyId = MtrProxyManager.proxyUuid(dimension, packet.sidingId(), packet.vehicleId());
        if (!(player.serverLevel().getEntity(proxyId) instanceof MtrTrainProxyEntity proxy)
                || proxy.sidingId() != packet.sidingId() || proxy.vehicleId() != packet.vehicleId()) return;
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null || packet.carIndex() < 0 || packet.carIndex() >= snapshot.cars().size()) return;
        MtrTrainSnapshot.CarPose car = snapshot.cars().get(packet.carIndex());
        if (player.distanceToSqr(car.x(), car.y(), car.z()) > REPORT_RADIUS_SQR) return;

        long now = System.currentTimeMillis();
        Geometry current = GEOMETRIES.get(proxyId);
        if (current != null && current.expiresAt > now && !current.source.equals(player.getUUID())) return;
        List<DoorBox> validated = packet.doors().stream().map(door -> validate(car, door)).filter(java.util.Objects::nonNull).toList();
        if (validated.isEmpty()) return;
        Geometry geometry = current == null || current.expiresAt <= now || !current.source.equals(player.getUUID())
                ? new Geometry(player.getUUID()) : current;
        geometry.cars.put(packet.carIndex(), List.copyOf(validated));
        geometry.expiresAt = now + GEOMETRY_TTL_MS;
        GEOMETRIES.put(proxyId, geometry);
    }

    public static List<DoorBox> doors(MtrTrainProxyEntity proxy, int carIndex) {
        Geometry geometry = GEOMETRIES.get(proxy.getUUID());
        if (geometry == null) return List.of();
        if (geometry.expiresAt <= System.currentTimeMillis()) {
            GEOMETRIES.remove(proxy.getUUID(), geometry);
            return List.of();
        }
        return geometry.cars.getOrDefault(carIndex, List.of());
    }

    public static void clear(MtrTrainProxyEntity proxy) { GEOMETRIES.remove(proxy.getUUID()); }
    public static void clearAll() { GEOMETRIES.clear(); }

    private static DoorBox validate(MtrTrainSnapshot.CarPose car, MtrCompatNetwork.DoorGeometryPacket.DoorBox raw) {
        double[] values = {raw.minX(), raw.minY(), raw.minZ(), raw.maxX(), raw.maxY(), raw.maxZ()};
        for (double value : values) if (!Double.isFinite(value)) return null;
        double minX = Math.min(raw.minX(), raw.maxX()), maxX = Math.max(raw.minX(), raw.maxX());
        double minY = Math.min(raw.minY(), raw.maxY()), maxY = Math.max(raw.minY(), raw.maxY());
        double minZ = Math.min(raw.minZ(), raw.maxZ()), maxZ = Math.max(raw.minZ(), raw.maxZ());
        double halfWidth = car.width() * 0.5D, halfLength = car.length() * 0.5D;
        if (Math.max(Math.abs(minX), Math.abs(maxX)) > halfWidth + 2D || minY < -1D || maxY > 5D
                || minZ < -halfLength - 1D || maxZ > halfLength + 1D || maxZ - minZ < 0.15D
                || maxZ - minZ > 3.5D || maxX - minX > 2D || maxY - minY > 3.5D) return null;
        double centerX = (minX + maxX) * 0.5D;
        if (Math.abs(centerX) < Math.max(0.2D, halfWidth * 0.45D)) return null;
        return new DoorBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public record DoorBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public double centerX() { return (minX + maxX) * 0.5D; }
        public double centerZ() { return (minZ + maxZ) * 0.5D; }
        public int side() { return centerX() < 0 ? -1 : 1; }
    }

    private static final class Geometry {
        private final UUID source;
        private final Map<Integer, List<DoorBox>> cars = new HashMap<>();
        private long expiresAt;

        private Geometry(UUID source) { this.source = source; }
    }
}
