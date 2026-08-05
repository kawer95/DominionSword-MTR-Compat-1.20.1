package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.resource.VehicleResourceCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Periodically publishes nearby resource-pack doorway boxes; no model data leaves the client. */
@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, value = Dist.CLIENT)
public final class MtrDoorGeometryClient {
    private static final double CAPTURE_RADIUS_SQR = 256D * 256D;
    private static final Map<String, CachedDoors> DOORS = new ConcurrentHashMap<>();
    private static int cooldown;

    private MtrDoorGeometryClient() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++cooldown < 100) return;
        cooldown = 0;
        long now = System.currentTimeMillis();
        DOORS.entrySet().removeIf(entry -> now - entry.getValue().capturedAt > 30_000L);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) { DOORS.clear(); return; }
        int[] trains = {0};
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> {
            if (trains[0] >= 24 || vehicle.getVehicleCarsAndPositions().isEmpty()) return;
            Vector head = vehicle.getHeadPosition();
            if (minecraft.player.distanceToSqr(head.x, head.y, head.z) > CAPTURE_RADIUS_SQR) return;
            trains[0]++;
            int totalCars = vehicle.vehicleExtraData.immutableVehicleCars.size();
            for (int index = 0; index < totalCars; index++) {
                final int carIndex = index;
                VehicleCar car = vehicle.vehicleExtraData.immutableVehicleCars.get(index);
                CustomResourceLoader.getVehicleById(vehicle.getTransportMode(), car.getVehicleId(), resourceDetails -> {
                    VehicleResourceCache cache = resourceDetails.left().getCachedVehicleResource(carIndex, totalCars, false);
                    if (cache == null || cache.doorways.isEmpty()) return;
                    List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> doors = new ArrayList<>();
                    for (Box box : cache.doorways) {
                        if (doors.size() >= 64) break;
                        doors.add(new MtrCompatNetwork.DoorGeometryPacket.DoorBox(
                                box.getMinXMapped(), box.getMinYMapped(), box.getMinZMapped(),
                                box.getMaxXMapped(), box.getMaxYMapped(), box.getMaxZMapped()));
                    }
                    DOORS.put(key(vehicle.getId(), carIndex), new CachedDoors(now, List.copyOf(doors)));
                    MtrCompatNetwork.CHANNEL.sendToServer(new MtrCompatNetwork.DoorGeometryPacket(
                            vehicle.vehicleExtraData.getSidingId(), vehicle.getId(), carIndex, List.copyOf(doors)));
                });
            }
        });
    }

    public static List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> doors(long vehicleId, int carIndex) {
        CachedDoors cached = DOORS.get(key(vehicleId, carIndex));
        return cached == null ? List.of() : cached.doors;
    }

    /** Mirrors MTR's rider-offset doorway test for compatibility-held localized doors. */
    public static List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> heldDoors(VehicleExtraData extra, int carIndex,
            List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> doors) {
        List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> result = new ArrayList<>();
        extra.iterateRidingEntities(rider -> {
            if (rider.getRidingCar() != carIndex || !rider.getDoorOverride()) return;
            for (MtrCompatNetwork.DoorGeometryPacket.DoorBox door : doors) {
                if (rider.getX() > door.minX() - .3D && rider.getX() < door.maxX() + .3D
                        && rider.getY() >= door.minY() && rider.getY() <= door.maxY()
                        && rider.getZ() > door.minZ() - .3D && rider.getZ() < door.maxZ() + .3D
                        && !result.contains(door)) result.add(door);
            }
        });
        return List.copyOf(result);
    }

    private static String key(long vehicleId, int carIndex) { return vehicleId + ":" + carIndex; }
    private record CachedDoors(long capturedAt, List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> doors) {}
}
