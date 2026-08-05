package com.arxyt.dominionsword.mtr.mixin;

import com.arxyt.dominionsword.mtr.client.MtrLocalizedDoorAnimation;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicles;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.VehicleResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the optimized open-body model active until a localized closing animation has actually finished. */
@Mixin(value = RenderVehicles.class, remap = false)
public abstract class RenderVehiclesMixin {
    @Redirect(method = "lambda$render$14", at = @At(value = "INVOKE",
            target = "Lorg/mtr/mod/resource/VehicleResource;queue(Lorg/mtr/mod/render/StoredMatrixTransformations;Lorg/mtr/mod/data/VehicleExtension;IIIZ)V"),
            remap = false)
    private static void dominionSword$keepOpenBodyWhileLocalizedDoorCloses(VehicleResource resource,
            StoredMatrixTransformations transformations, VehicleExtension vehicle, int carNumber,
            int totalCars, int light, boolean noOpenDoorways) {
        resource.queue(transformations, vehicle, carNumber, totalCars, light,
                noOpenDoorways && !MtrLocalizedDoorAnimation.isClosing(vehicle.getId(), carNumber));
    }
}
