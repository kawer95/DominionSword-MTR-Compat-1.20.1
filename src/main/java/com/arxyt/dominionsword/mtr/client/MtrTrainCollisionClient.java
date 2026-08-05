package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.client.ClientServerSettings;
import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCarTransform;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCollisionGeometry;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainEntityCollision;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainRoofCarry;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frigatemod-style local-player prediction for the same hull solver used by the server. */
@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, value = Dist.CLIENT)
public final class MtrTrainCollisionClient {
    private static final Map<String, Vec3> LAST_LOCAL_POSITIONS = new HashMap<>();
    private static final Map<String, Vec3> LAST_DECK_POSITIONS = new HashMap<>();
    private static final Map<String, MtrTrainCarTransform> LAST_CAR_TRANSFORMS = new HashMap<>();
    private static int dismountImmunityTicks;

    private MtrTrainCollisionClient() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (dismountImmunityTicks > 0) {
            dismountImmunityTicks--;
            LAST_LOCAL_POSITIONS.clear();
            LAST_DECK_POSITIONS.clear();
            LAST_CAR_TRANSFORMS.clear();
            return;
        }
        if (minecraft.level == null || player == null || player.isPassenger() || player.isSpectator()
                || player.noPhysics || !ClientServerSettings.mtrTrainCollision()) {
            LAST_LOCAL_POSITIONS.clear();
            LAST_DECK_POSITIONS.clear();
            LAST_CAR_TRANSFORMS.clear();
            return;
        }

        Set<String> seen = new HashSet<>();
        boolean[] carried = {false};
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> {
            int[] carIndex = {0};
            vehicle.getVehicleCarsAndPositions().forEach(carAndBogies -> {
                int index = carIndex[0]++;
                VehicleCar car = carAndBogies.left();
                List<Vector> centers = new ArrayList<>();
                carAndBogies.right().forEach(pair -> centers.add(Vector.getAverage(pair.left(), pair.right())));
                if (centers.isEmpty()) return;
                Vector first = centers.get(0), last = centers.get(centers.size() - 1);
                double x = centers.stream().mapToDouble(point -> point.x).average().orElse(first.x);
                double y = centers.stream().mapToDouble(point -> point.y).average().orElse(first.y);
                double z = centers.stream().mapToDouble(point -> point.z).average().orElse(first.z);
                double deltaX = last.x - first.x, deltaY = last.y - first.y, deltaZ = last.z - first.z;
                MtrTrainCarTransform transform = new MtrTrainCarTransform(x, y, z,
                        Math.atan2(deltaX, deltaZ), Math.atan2(deltaY, Math.hypot(deltaX, deltaZ)),
                        car.getLength(), car.getWidth());
                String key = vehicle.getId() + ":" + index;
                seen.add(key);
                MtrTrainCarTransform previousTransform = LAST_CAR_TRANSFORMS.put(key, transform);
                AABB interactionBounds = previousTransform == null ? transform.worldBounds()
                        : transform.worldBounds().minmax(previousTransform.worldBounds());
                if (!interactionBounds.contains(player.position())) return;

                if (!carried[0] && previousTransform != null) {
                    Vec3 previousLocal = previousTransform.toLocal(player.position());
                    Vec3 contact = MtrTrainRoofCarry.contact(previousLocal, LAST_DECK_POSITIONS.get(key),
                            car.getWidth(), car.getLength(), player.getBbWidth(), player.getDeltaMovement().y > 0.02D);
                    if (contact == null) {
                        LAST_DECK_POSITIONS.put(key, previousLocal);
                    } else {
                        Vec3 target = transform.toWorld(contact);
                        LAST_DECK_POSITIONS.put(key, contact);
                        player.setPos(target.x, target.y, target.z);
                        player.fallDistance = 0.0F;
                        player.setOnGround(true);
                        if (player.getDeltaMovement().y < 0.0D) {
                            player.setDeltaMovement(player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
                        }
                        carried[0] = true;
                    }
                }

                List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> doorBoxes = MtrDoorGeometryClient.doors(vehicle.getId(), index);
                List<MtrCompatNetwork.DoorGeometryPacket.DoorBox> openDoorBoxes = vehicle.vehicleExtraData.getDoorMultiplier() > 0
                        ? doorBoxes : MtrDoorGeometryClient.heldDoors(vehicle.vehicleExtraData, index, doorBoxes);
                List<MtrTrainCollisionGeometry.DoorOpening> openings = openDoorBoxes.stream().map(door ->
                        new MtrTrainCollisionGeometry.DoorOpening((door.minX() + door.maxX()) * .5D < 0 ? -1 : 1,
                                Math.min(door.minZ(), door.maxZ()), Math.max(door.minZ(), door.maxZ()))).toList();
                List<AABB> shell = MtrTrainCollisionGeometry.shell(car.getWidth(), car.getLength(),
                        vehicle.vehicleExtraData.getDoorMultiplier() > 0 || !openings.isEmpty(), openings);

                Vec3 current = transform.toLocal(player.position());
                Vec3 previous = LAST_LOCAL_POSITIONS.getOrDefault(key,
                        transform.toLocal(new Vec3(player.xo, player.yo, player.zo)));
                MtrTrainEntityCollision.Resolution resolution = MtrTrainEntityCollision.resolve(
                        previous, current, player.getBbWidth(), player.getBbHeight(), shell);
                LAST_LOCAL_POSITIONS.put(key, resolution.position());
                if (!resolution.collided()) return;

                Vec3 target = transform.toWorld(resolution.position());
                Vec3 correction = target.subtract(player.position());
                player.setPos(target.x, target.y, target.z);
                if (correction.lengthSqr() > 1.0E-10D) {
                    Vec3 normal = correction.normalize();
                    double intoHull = player.getDeltaMovement().dot(normal);
                    if (intoHull < 0) player.setDeltaMovement(player.getDeltaMovement().subtract(normal.scale(intoHull)));
                }
                player.fallDistance = 0;
            });
        });
        LAST_LOCAL_POSITIONS.keySet().retainAll(seen);
        LAST_DECK_POSITIONS.keySet().retainAll(seen);
        LAST_CAR_TRANSFORMS.keySet().retainAll(seen);
    }

    public static void grantDismountImmunity(int ticks) {
        dismountImmunityTicks = Math.max(dismountImmunityTicks, ticks);
        LAST_LOCAL_POSITIONS.clear();
        LAST_DECK_POSITIONS.clear();
        LAST_CAR_TRANSFORMS.clear();
    }
}
