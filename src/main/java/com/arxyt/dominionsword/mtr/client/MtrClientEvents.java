package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.registry.MtrCompatEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MtrClientEvents {
    private MtrClientEvents() {}
    @SubscribeEvent public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MtrCompatEntities.TRAIN_PROXY.get(), MtrTrainProxyRenderer::new);
    }
}
