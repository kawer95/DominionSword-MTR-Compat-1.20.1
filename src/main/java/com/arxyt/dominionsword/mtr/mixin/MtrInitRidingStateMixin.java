package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.bridge.MtrNativeRidingState;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mod.Init;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors MTR's private RIDING_PLAYERS mutations without exposing a Mixin accessor to ordinary classes. */
@Mixin(value = Init.class, remap = false)
public abstract class MtrInitRidingStateMixin {
    @Inject(method = "updateRidingEntity", at = @At("TAIL"), remap = false)
    private static void dominionSword$trackNativeRiding(ServerPlayerEntity player, boolean dismount, CallbackInfo callback) {
        if (player != null) MtrNativeRidingState.update(player.getUuid(), !dismount);
    }
}
