package com.arxyt.dominionsword.mtr.registry;

import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class MtrCompatEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DominionSwordMtrCompatMod.MOD_ID);
    public static final RegistryObject<EntityType<MtrTrainProxyEntity>> TRAIN_PROXY = TYPES.register("train_proxy", () ->
            EntityType.Builder.<MtrTrainProxyEntity>of(MtrTrainProxyEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(256).updateInterval(1).fireImmune()
                    .build(DominionSwordMtrCompatMod.MOD_ID + ":train_proxy"));
    private MtrCompatEntities() {}
    public static void register(IEventBus bus) { TYPES.register(bus); }
}
