package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtr.mod.client.MinecraftClientData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Drives MTR's native client door interpolation and acknowledges only a visually completed opening. */
@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, value = Dist.CLIENT)
public final class MtrForcedDoorAnimationClient {
    private static final Map<Long, UUID> OPENING = new HashMap<>();
    private static final Set<Long> CLOSING = new HashSet<>();
    private static final Set<Long> ACKNOWLEDGED = new HashSet<>();

    private MtrForcedDoorAnimationClient() {}

    public static void set(UUID proxyId, long vehicleId, boolean open) {
        if (open) {
            OPENING.put(vehicleId, proxyId);
            CLOSING.remove(vehicleId);
            ACKNOWLEDGED.remove(vehicleId);
        } else {
            OPENING.remove(vehicleId);
            ACKNOWLEDGED.remove(vehicleId);
            CLOSING.add(vehicleId);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Set<Long> seen = new HashSet<>();
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> {
            long vehicleId = vehicle.getId();
            seen.add(vehicleId);
            UUID proxyId = OPENING.get(vehicleId);
            if (proxyId != null) {
                vehicle.persistentVehicleData.overrideDoorMultiplier(1);
                if (vehicle.persistentVehicleData.getDoorValue() >= .999D && ACKNOWLEDGED.add(vehicleId)) {
                    MtrCompatNetwork.CHANNEL.sendToServer(new MtrCompatNetwork.DoorsOpenedPacket(proxyId, vehicleId));
                }
            } else if (CLOSING.contains(vehicleId)) {
                vehicle.persistentVehicleData.overrideDoorMultiplier(-1);
                if (vehicle.persistentVehicleData.getDoorValue() <= 0D) CLOSING.remove(vehicleId);
            }
        });
        CLOSING.removeIf(vehicleId -> !seen.contains(vehicleId));
    }
}
