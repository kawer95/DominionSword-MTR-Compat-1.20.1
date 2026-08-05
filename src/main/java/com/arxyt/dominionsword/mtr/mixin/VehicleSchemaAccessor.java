package com.arxyt.dominionsword.mtr.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleSchemaAccessor {
    @Accessor("speed") double dominionSword$getSpeed();
    @Accessor("railProgress") double dominionSword$getRailProgress();
    @Accessor("nextStoppingIndexAto") void dominionSword$setNextStoppingIndexAto(long value);
}
