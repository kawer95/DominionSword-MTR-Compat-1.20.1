package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.client.MtrLocalizedDoorAnimation;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectDoubleImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.ModelPropertiesPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds a time-interpolated localized override into MTR's own door animation renderer. */
@Mixin(value = ModelPropertiesPart.class, remap = false)
public abstract class ModelPropertiesPartMixin {
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void dominionSword$smoothLocalizedDoor(Identifier texture,
            StoredMatrixTransformations transformations, VehicleExtension vehicle, int carNumber,
            int[] scrollingDisplayIndexTracker, int light,
            ObjectArrayList<ObjectDoubleImmutablePair<Box>> openDoorways,
            boolean fromResourcePackCreator, CallbackInfo callback) {
        MtrLocalizedDoorAnimation.apply(vehicle, carNumber, openDoorways);
    }
}
