package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.service.MtrPlayerDismountService;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mod.packet.PacketUpdateVehicleRidingEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks MTR's authoritative dismount packet, including normal doorway exits and forced Shift exits. */
@Mixin(value = PacketUpdateVehicleRidingEntities.class, remap = false)
public abstract class PacketUpdateVehicleRidingEntitiesMixin {
    @Shadow @Final private boolean dismount;

    @Inject(method = "runServerOutbound", at = @At("HEAD"), remap = false)
    private void dominionSword$beforeMtrDismount(ServerWorld world, ServerPlayerEntity player, CallbackInfo callback) {
        if (dismount && player != null) MtrPlayerDismountService.onMtrPacketDismount(player.data);
    }
}
