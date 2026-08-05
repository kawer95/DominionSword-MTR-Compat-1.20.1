package com.arxyt.dominionsword.mtr.network;

import com.arxyt.dominionsword.control.PlayerControl;
import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.arxyt.dominionsword.api.DominionVehicleAdapters;
import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainSnapshot;
import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.arxyt.dominionsword.mtr.service.MtrControlService;
import com.arxyt.dominionsword.mtr.service.MtrProxyManager;
import com.arxyt.dominionsword.mtr.service.MtrDoorGeometryService;
import com.arxyt.dominionsword.mtr.service.MtrPlayerDismountService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
import com.arxyt.dominionsword.network.C2SValidator;
import com.arxyt.dominionsword.network.DSNetwork;
import com.arxyt.dominionsword.network.packet.VehicleSeatMenuDataPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class MtrCompatNetwork {
    private static final String VERSION = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(DominionSwordMtrCompatMod.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private MtrCompatNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(StationListPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StationListPacket::encode).decoder(StationListPacket::decode).consumerMainThread(StationListPacket::handle).add();
        CHANNEL.messageBuilder(SelectStationPacket.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectStationPacket::encode).decoder(SelectStationPacket::decode).consumerMainThread(SelectStationPacket::handle).add();
        CHANNEL.messageBuilder(DoorGeometryPacket.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DoorGeometryPacket::encode).decoder(DoorGeometryPacket::decode).consumerMainThread(DoorGeometryPacket::handle).add();
        CHANNEL.messageBuilder(OpenTrainMenuPacket.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder(OpenTrainMenuPacket::encode).decoder(OpenTrainMenuPacket::decode).consumerMainThread(OpenTrainMenuPacket::handle).add();
        CHANNEL.messageBuilder(ForceDoorsPacket.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ForceDoorsPacket::encode).decoder(ForceDoorsPacket::decode).consumerMainThread(ForceDoorsPacket::handle).add();
        CHANNEL.messageBuilder(DoorsOpenedPacket.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DoorsOpenedPacket::encode).decoder(DoorsOpenedPacket::decode).consumerMainThread(DoorsOpenedPacket::handle).add();
        CHANNEL.messageBuilder(BoardingTargetPacket.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoardingTargetPacket::encode).decoder(BoardingTargetPacket::decode).consumerMainThread(BoardingTargetPacket::handle).add();
        CHANNEL.messageBuilder(DismountIntentPacket.class, 7, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DismountIntentPacket::encode).decoder(DismountIntentPacket::decode).consumerMainThread(DismountIntentPacket::handle).add();
    }

    public static void sendDismountIntent(long sidingId, long vehicleId, int carIndex, boolean forcedByShift) {
        CHANNEL.sendToServer(new DismountIntentPacket(sidingId, vehicleId, carIndex, forcedByShift));
    }

    /** Sent from the exact MTR sendUpdate(true) injection before MTR clears its local ride state. */
    public record DismountIntentPacket(long sidingId, long vehicleId, int carIndex, boolean forcedByShift) {
        static void encode(DismountIntentPacket packet, FriendlyByteBuf buffer) {
            buffer.writeLong(packet.sidingId); buffer.writeLong(packet.vehicleId);
            buffer.writeVarInt(packet.carIndex); buffer.writeBoolean(packet.forcedByShift);
        }
        static DismountIntentPacket decode(FriendlyByteBuf buffer) {
            return new DismountIntentPacket(buffer.readLong(), buffer.readLong(), buffer.readVarInt(), buffer.readBoolean());
        }
        static void handle(DismountIntentPacket packet, Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> MtrPlayerDismountService.onClientDismountIntent(
                    player, packet.sidingId, packet.vehicleId, packet.carIndex, packet.forcedByShift));
            context.get().setPacketHandled(true);
        }
    }

    public static void syncForcedDoors(MtrTrainProxyEntity proxy, boolean open) {
        if (proxy == null || proxy.getServer() == null) return;
        ForceDoorsPacket packet = new ForceDoorsPacket(proxy.getUUID(), proxy.vehicleId(), open);
        for (ServerPlayer player : proxy.getServer().getPlayerList().getPlayers()) {
            if (player.level().dimension().equals(proxy.level().dimension())) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    public record ForceDoorsPacket(UUID proxyId, long vehicleId, boolean open) {
        static void encode(ForceDoorsPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.proxyId); buffer.writeLong(packet.vehicleId); buffer.writeBoolean(packet.open);
        }
        static ForceDoorsPacket decode(FriendlyByteBuf buffer) {
            return new ForceDoorsPacket(buffer.readUUID(), buffer.readLong(), buffer.readBoolean());
        }
        static void handle(ForceDoorsPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.arxyt.dominionsword.mtr.client.MtrForcedDoorAnimationClient.set(
                            packet.proxyId, packet.vehicleId, packet.open)));
            context.get().setPacketHandled(true);
        }
    }

    public record DoorsOpenedPacket(UUID proxyId, long vehicleId) {
        static void encode(DoorsOpenedPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.proxyId); buffer.writeLong(packet.vehicleId);
        }
        static DoorsOpenedPacket decode(FriendlyByteBuf buffer) {
            return new DoorsOpenedPacket(buffer.readUUID(), buffer.readLong());
        }
        static void handle(DoorsOpenedPacket packet, Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> MtrControlService.acknowledgeDismountDoors(
                    player, packet.proxyId, packet.vehicleId));
            context.get().setPacketHandled(true);
        }
    }

    public static void syncBoardingTarget(MtrTrainProxyEntity proxy, UUID unitId, net.minecraft.world.phys.Vec3 position) {
        if (proxy == null || unitId == null || position == null || proxy.getServer() == null) return;
        BoardingTargetPacket packet = new BoardingTargetPacket(unitId, proxy.getUUID(), position.x, position.y, position.z);
        for (ServerPlayer player : proxy.getServer().getPlayerList().getPlayers()) {
            if (player.level().dimension().equals(proxy.level().dimension())) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    public record BoardingTargetPacket(UUID unitId, UUID proxyId, double x, double y, double z) {
        static void encode(BoardingTargetPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.unitId); buffer.writeUUID(packet.proxyId);
            buffer.writeDouble(packet.x); buffer.writeDouble(packet.y); buffer.writeDouble(packet.z);
        }
        static BoardingTargetPacket decode(FriendlyByteBuf buffer) {
            return new BoardingTargetPacket(buffer.readUUID(), buffer.readUUID(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        }
        static void handle(BoardingTargetPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.arxyt.dominionsword.mtr.client.MtrClientBoardingTargets.accept(
                            packet.unitId, packet.proxyId, new net.minecraft.world.phys.Vec3(packet.x, packet.y, packet.z))));
            context.get().setPacketHandled(true);
        }
    }

    public static void openStationScreen(ServerPlayer player, MtrTrainProxyEntity proxy) {
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        if (snapshot == null) return;
        List<StationLine> lines = new ArrayList<>();
        for (MtrTrainSnapshot.Stop stop : snapshot.stops()) if (stop.progress() > snapshot.railProgress() + .5)
            lines.add(new StationLine(stop.key(), stop.name(), Math.max(0, stop.progress() - snapshot.railProgress())));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StationListPacket(proxy.getUUID(), proxy.targetKey(), List.copyOf(lines)));
    }

    public record StationLine(String key, String name, double distance) {}

    /** Direct rendered-train click; the server binds ids back to a fresh MTR snapshot and proxy. */
    public record OpenTrainMenuPacket(long sidingId, long vehicleId) {
        public static void encode(OpenTrainMenuPacket packet, FriendlyByteBuf buffer) {
            buffer.writeLong(packet.sidingId); buffer.writeLong(packet.vehicleId);
        }

        public static OpenTrainMenuPacket decode(FriendlyByteBuf buffer) {
            return new OpenTrainMenuPacket(buffer.readLong(), buffer.readLong());
        }

        public static void handle(OpenTrainMenuPacket packet, Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> openTrainMenu(player, packet.sidingId, packet.vehicleId));
            context.get().setPacketHandled(true);
        }
    }

    private static void openTrainMenu(ServerPlayer player, long sidingId, long vehicleId) {
        MtrTrainProxyEntity proxy = MtrProxyManager.forInteraction(player, sidingId, vehicleId);
        if (proxy == null) {
            player.displayClientMessage(Component.translatable("message.dominionsword_mtr_compat.snapshot_missing"), true);
            return;
        }
        MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
        // An empty proxy has no Dominion operator and must be left entirely to native MTR.
        if (proxy.getPassengers().isEmpty() && !PlayerControl.canBoardSelected(player)) return;
        boolean validPosition = snapshot != null && snapshot.cars().stream().anyMatch(car ->
                C2SValidator.validPoint(player, new net.minecraft.world.phys.Vec3(car.x(), car.y(), car.z())));
        if (!validPosition) return;

        List<DominionVehicleAdapter.SeatView> seats = DominionVehicleAdapters.seats(proxy);
        List<DominionVehicleAdapter.ActionView> availableActions = DominionVehicleAdapters.actions(proxy);
        boolean manage = PlayerControl.canManageVehicle(player, proxy);
        boolean batch = !seats.isEmpty() && !manage && PlayerControl.selectedCount(player) > 1 && PlayerControl.canBoardSelected(player);
        boolean board = !seats.isEmpty() && !manage && PlayerControl.canBoardSelected(player);
        boolean operate = !availableActions.isEmpty() && PlayerControl.canBoardSelected(player);
        if (!manage && !batch && !board && !operate) {
            player.displayClientMessage(Component.translatable("message.dominionsword_mtr_compat.select_unit_first"), true);
            return;
        }

        List<VehicleSeatMenuDataPacket.SeatLine> lines = new ArrayList<>();
        Entity driver = proxy.passengerAt(0);
        lines.add(new VehicleSeatMenuDataPacket.SeatLine(0, "@screen.dominionsword.vehicle.driver_position",
                driver == null ? "" : driver.getDisplayName().getString()));
        int passengerCapacity = Math.max(0, proxy.seatCount() - 1);
        int occupiedPassengers = 0;
        for (Entity passenger : proxy.getPassengers()) if (proxy.seatFor(passenger) > 0) occupiedPassengers++;
        lines.add(new VehicleSeatMenuDataPacket.SeatLine(-1,
                "@passenger_summary:" + occupiedPassengers + ':' + passengerCapacity, ""));
        List<VehicleSeatMenuDataPacket.ActionLine> actions = new ArrayList<>();
        for (DominionVehicleAdapter.ActionView action : availableActions) if (action != null && action.id() != null && !action.id().isBlank())
            actions.add(new VehicleSeatMenuDataPacket.ActionLine(action.id(), action.label() == null ? action.id() : action.label(),
                    action.toggle(), action.checked(), action.numberInput()));
        DSNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new VehicleSeatMenuDataPacket(
                proxy.getUUID(), proxy.getDisplayName().getString(), manage, batch, board, List.copyOf(lines), List.copyOf(actions)));
    }

    public record StationListPacket(UUID proxyId, String current, List<StationLine> stations) {
        static void encode(StationListPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.proxyId); buffer.writeUtf(packet.current, 128); buffer.writeVarInt(packet.stations.size());
            for (StationLine line : packet.stations) { buffer.writeUtf(line.key, 128); buffer.writeUtf(line.name, 256); buffer.writeDouble(line.distance); }
        }
        static StationListPacket decode(FriendlyByteBuf buffer) {
            UUID id = buffer.readUUID(); String current = buffer.readUtf(128); int size = Math.min(512, buffer.readVarInt());
            List<StationLine> lines = new ArrayList<>(size);
            for (int i = 0; i < size; i++) lines.add(new StationLine(buffer.readUtf(128), buffer.readUtf(256), buffer.readDouble()));
            return new StationListPacket(id, current, List.copyOf(lines));
        }
        static void handle(StationListPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.arxyt.dominionsword.mtr.client.MtrClientScreens.openStations(packet)));
            context.get().setPacketHandled(true);
        }
    }

    public record SelectStationPacket(UUID proxyId, String key) {
        static void encode(SelectStationPacket packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.proxyId); buffer.writeUtf(packet.key, 128); }
        static SelectStationPacket decode(FriendlyByteBuf buffer) { return new SelectStationPacket(buffer.readUUID(), buffer.readUtf(128)); }
        static void handle(SelectStationPacket packet, Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> {
                Entity entity = player.serverLevel().getEntity(packet.proxyId);
                if (!(entity instanceof MtrTrainProxyEntity proxy) || proxy.level() != player.level() || !PlayerControl.canOperateVehicle(player, proxy)) return;
                MtrTrainSnapshot snapshot = MtrProxyManager.snapshot(proxy);
                MtrTrainSnapshot.Stop stop = snapshot == null ? null : MtrControlService.findStop(snapshot, packet.key);
                if (stop == null || stop.progress() <= snapshot.railProgress() + .5 || MtrControlService.hasRealPlayerDriver(player.server, snapshot)) return;
                MtrControlService.target(proxy, stop);
                if (Math.abs(snapshot.speed()) < .00002) MtrControlService.setMode(player, proxy, MtrControlService.Mode.STARTING);
            });
            context.get().setPacketHandled(true);
        }
    }

    /** Bounded client resource data; the server validates it against the live MTR car dimensions. */
    public record DoorGeometryPacket(long sidingId, long vehicleId, int carIndex, List<DoorBox> doors) {
        private static final int MAX_DOORS = 64;

        public record DoorBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

        public static void encode(DoorGeometryPacket packet, FriendlyByteBuf buffer) {
            buffer.writeLong(packet.sidingId);
            buffer.writeLong(packet.vehicleId);
            buffer.writeVarInt(packet.carIndex);
            buffer.writeVarInt(packet.doors.size());
            for (DoorBox door : packet.doors) {
                buffer.writeDouble(door.minX); buffer.writeDouble(door.minY); buffer.writeDouble(door.minZ);
                buffer.writeDouble(door.maxX); buffer.writeDouble(door.maxY); buffer.writeDouble(door.maxZ);
            }
        }

        public static DoorGeometryPacket decode(FriendlyByteBuf buffer) {
            long sidingId = buffer.readLong(), vehicleId = buffer.readLong();
            int carIndex = buffer.readVarInt(), size = buffer.readVarInt();
            if (size < 0 || size > MAX_DOORS) throw new IllegalArgumentException("Invalid MTR doorway count: " + size);
            List<DoorBox> doors = new ArrayList<>(size);
            for (int index = 0; index < size; index++) doors.add(new DoorBox(
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
            return new DoorGeometryPacket(sidingId, vehicleId, carIndex, List.copyOf(doors));
        }

        public static void handle(DoorGeometryPacket packet, Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> MtrDoorGeometryService.accept(player, packet));
            context.get().setPacketHandled(true);
        }
    }
}
