package com.arxyt.dominionsword.mtr.mixin;

import org.mtr.core.data.Vehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes MTR's native next-stop calculation when compatibility control is relinquished. */
@Mixin(value = Vehicle.class, remap = false)
public interface VehicleMethodInvoker {
    @Invoker("setNextStoppingIndex") void dominionSword$setNextStoppingIndex();
}
