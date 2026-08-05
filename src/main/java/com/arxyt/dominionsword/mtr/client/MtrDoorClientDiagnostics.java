package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtr.mod.client.MinecraftClientData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded client evidence for native MTR door interpolation and rider overrides. */
@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, value = Dist.CLIENT)
public final class MtrDoorClientDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Long, Sample> SAMPLES = new HashMap<>();

    private MtrDoorClientDiagnostics() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        Set<Long> seen = new HashSet<>();
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> {
            long id = vehicle.getId();
            seen.add(id);
            int[] overrides = {0};
            vehicle.vehicleExtraData.iterateRidingEntities(rider -> {
                if (rider.getDoorOverride()) overrides[0]++;
            });
            int multiplier = vehicle.vehicleExtraData.getDoorMultiplier();
            double doorValue = vehicle.persistentVehicleData.getDoorValue();
            Sample old = SAMPLES.get(id);
            boolean active = multiplier > 0 || doorValue > 0D || overrides[0] > 0;
            boolean changed = old == null || old.multiplier != multiplier
                    || Math.abs(old.doorValue - doorValue) >= .08D || old.overrides != overrides[0];
            if ((active || old != null && old.active) && (changed || old == null || now - old.loggedAt >= 500L)) {
                LOGGER.info("MTR DOOR CLIENT TRACE phase=native vehicle={} multiplier={} nativeDoorValue={} overrides={} active={}",
                        id, multiplier, doorValue, overrides[0], active);
                SAMPLES.put(id, new Sample(multiplier, doorValue, overrides[0], active, now));
            } else if (old != null) {
                SAMPLES.put(id, new Sample(multiplier, doorValue, overrides[0], active, old.loggedAt));
            }
        });
        SAMPLES.keySet().retainAll(seen);
    }

    private record Sample(int multiplier, double doorValue, int overrides, boolean active, long loggedAt) {}
}
