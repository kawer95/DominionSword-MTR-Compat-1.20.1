package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.bridge.MtrSnapshotBridge;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps compatibility-started ATO vehicles in their owning siding for the duration of the trip. */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingSimulationMixin {
    /**
     * MTR normally removes every non-manual on-route vehicle whose departure index is negative.
     * Compatibility departures intentionally use -1, so suppress only removal-set additions for
     * the exact marked vehicle; all ordinary timetable duplicate/depot cleanup remains unchanged.
     */
    @Redirect(method = "simulateTrain", at = @At(value = "INVOKE",
            target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;add(Ljava/lang/Object;)Z"), remap = false)
    private boolean dominionSword$keepCompatibilityTrip(ObjectArraySet<Object> set, Object value) {
        if (value instanceof Vehicle vehicle && MtrSnapshotBridge.isCompatibilityTrip(vehicle.getId())) return false;
        return set.add(value);
    }
}
