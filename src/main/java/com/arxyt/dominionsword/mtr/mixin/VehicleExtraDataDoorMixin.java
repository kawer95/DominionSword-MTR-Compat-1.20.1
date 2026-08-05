package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.bridge.MtrForcedDoorAccess;
import org.mtr.core.data.VehicleExtraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns a per-vehicle force-open latch on MTR's simulation object. */
@Mixin(value = VehicleExtraData.class, remap = false)
public abstract class VehicleExtraDataDoorMixin implements MtrForcedDoorAccess {
    @Unique private boolean dominionSword$forcedOpen;
    @Shadow protected abstract void openDoors();

    @Override
    public void dominionSword$setForcedOpen(boolean forcedOpen) {
        dominionSword$forcedOpen = forcedOpen;
        if (forcedOpen) openDoors();
    }

    @Inject(method = "closeDoors", at = @At("HEAD"), cancellable = true, remap = false)
    private void dominionSword$keepForcedDoorsOpen(CallbackInfo callback) {
        if (dominionSword$forcedOpen) callback.cancel();
    }
}
