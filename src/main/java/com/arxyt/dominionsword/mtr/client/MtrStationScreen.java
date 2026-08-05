package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class MtrStationScreen extends Screen {
    private static final int VISIBLE = 9;
    private final MtrCompatNetwork.StationListPacket data;
    private final List<Button> rows = new ArrayList<>();
    private int scroll;

    public MtrStationScreen(MtrCompatNetwork.StationListPacket data) {
        super(Component.translatable("screen.dominionsword_mtr_compat.stations"));
        this.data = data;
    }

    @Override protected void init() { rebuild(); }

    private void rebuild() {
        clearWidgets(); rows.clear();
        int x = width / 2 - 130, y = height / 2 - 102;
        for (int i = 0; i < VISIBLE; i++) {
            int index = scroll + i;
            if (index >= data.stations().size()) break;
            MtrCompatNetwork.StationLine line = data.stations().get(index);
            String mark = line.key().equals(data.current()) ? "● " : "";
            Button button = Button.builder(Component.literal(mark + line.name() + "  " + Math.round(line.distance()) + "m"), b -> select(line))
                    .bounds(x, y + i * 22, 260, 20).build();
            rows.add(addRenderableWidget(button));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 50, height / 2 + 105, 100, 20).build());
    }

    private void select(MtrCompatNetwork.StationLine line) {
        MtrCompatNetwork.CHANNEL.sendToServer(new MtrCompatNetwork.SelectStationPacket(data.proxyId(), line.key()));
        onClose();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = Math.max(0, Math.min(Math.max(0, data.stations().size() - VISIBLE), scroll - (int) Math.signum(delta)));
        if (next != scroll) { scroll = next; rebuild(); }
        return true;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 122, 0xFFFFFF);
        if (data.stations().isEmpty()) graphics.drawCenteredString(font,
                Component.translatable("screen.dominionsword_mtr_compat.no_stations"), width / 2, height / 2 - 5, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
