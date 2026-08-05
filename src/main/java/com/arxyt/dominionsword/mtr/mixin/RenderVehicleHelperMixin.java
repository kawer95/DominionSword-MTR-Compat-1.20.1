package com.arxyt.dominionsword.mtr.mixin;

import org.mtr.mapping.holder.Box;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Allows explicit MTR door requests to animate away from platform detection areas. */
@Mixin(value = RenderVehicleHelper.class, remap = false)
public abstract class RenderVehicleHelperMixin {
    private RenderVehicleHelperMixin() {}

    @Inject(method = "canOpenDoors", at = @At("RETURN"), cancellable = true, remap = false)
    private static void dominionSword$allowRequestedDepotDoor(Box doorway, PositionAndRotation positionAndRotation,
            double doorValue, CallbackInfoReturnable<Boolean> callback) {
        // RenderVehicles passes a positive value only when the train is deliberately opening
        // or a rider's localized doorOverride is holding this exact doorway. A zero value keeps
        // MTR's original platform/PSD/APG result and cannot open an otherwise closed door.
        if (!callback.getReturnValueZ() && doorValue > 0D) callback.setReturnValue(true);
    }
}
