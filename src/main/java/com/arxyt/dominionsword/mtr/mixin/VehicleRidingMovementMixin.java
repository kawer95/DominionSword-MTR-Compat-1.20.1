package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.client.MtrTrainCollisionClient;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import org.mtr.mod.client.VehicleRidingMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the single MTR client method used by Shift, doorway, gangway and invalid-floor dismounts. */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class VehicleRidingMovementMixin {
    @Shadow private static long ridingSidingId;
    @Shadow private static long ridingVehicleId;
    @Shadow private static int ridingVehicleCarNumber;
    @Shadow private static float shiftHoldingTicks;

    @Inject(method = "sendUpdate", at = @At("HEAD"), remap = false)
    private static void dominionSword$beforeMtrDismount(boolean dismount, CallbackInfo callback) {
        if (!dismount || ridingVehicleId == 0) return;
        MtrTrainCollisionClient.grantDismountImmunity(20);
        MtrCompatNetwork.sendDismountIntent(ridingSidingId, ridingVehicleId, ridingVehicleCarNumber,
                shiftHoldingTicks >= 30F);
    }
}
