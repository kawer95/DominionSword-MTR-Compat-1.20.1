package com.arxyt.dominionsword.mtr;

import com.arxyt.dominionsword.api.DominionVehicleAdapters;
import com.arxyt.dominionsword.mtr.compat.MtrVehicleAdapter;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import com.arxyt.dominionsword.mtr.registry.MtrCompatEntities;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(DominionSwordMtrCompatMod.MOD_ID)
public final class DominionSwordMtrCompatMod {
    public static final String MOD_ID = "dominionsword_mtr_compat";

    public DominionSwordMtrCompatMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        MtrCompatEntities.register(modBus);
        MtrCompatNetwork.register();
        DominionVehicleAdapters.register(new MtrVehicleAdapter());
    }
}
