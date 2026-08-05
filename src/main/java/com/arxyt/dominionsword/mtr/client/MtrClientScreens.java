package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.client.Minecraft;

public final class MtrClientScreens {
    private MtrClientScreens() {}
    public static void openStations(MtrCompatNetwork.StationListPacket packet) {
        Minecraft.getInstance().setScreen(new MtrStationScreen(packet));
    }
}
