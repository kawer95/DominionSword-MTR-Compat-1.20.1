package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.client.ClientSpirit;
import com.arxyt.dominionsword.registry.DSItems;
import com.arxyt.dominionsword.mtr.DominionSwordMtrCompatMod;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCarTransform;
import com.arxyt.dominionsword.mtr.bridge.MtrTrainCollisionGeometry;
import com.arxyt.dominionsword.mtr.network.MtrCompatNetwork;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.List;

/** Directly picks MTR's rendered, non-Entity car OBBs for a short Dominion Sword right click. */
@Mod.EventBusSubscriber(modid = DominionSwordMtrCompatMod.MOD_ID, value = Dist.CLIENT)
public final class MtrTrainRightClickHandler {
    private static Press press;

    private MtrTrainRightClickHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || minecraft.player == null
                || minecraft.level == null || minecraft.screen != null
                || minecraft.player.isShiftKeyDown()
                || !minecraft.player.getMainHandItem().is(DSItems.DOMINION_SWORD.get())) return;

        if (event.getAction() == GLFW.GLFW_PRESS) {
            TrainHit hit = pick(minecraft, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
            press = hit == null ? null : new Press(hit.sidingId, hit.vehicleId,
                    minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
            // Do not cancel the press. The core handler keeps its normal drag-command state.
            return;
        }
        if (event.getAction() != GLFW.GLFW_RELEASE || press == null) return;

        Press started = press;
        press = null;
        double dx = minecraft.mouseHandler.xpos() - started.mouseX;
        double dy = minecraft.mouseHandler.ypos() - started.mouseY;
        if (dx * dx + dy * dy > 16D) return;
        TrainHit released = pick(minecraft, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
        if (released == null || released.sidingId != started.sidingId || released.vehicleId != started.vehicleId) return;

        event.setCanceled(true);
        MtrCompatNetwork.CHANNEL.sendToServer(new MtrCompatNetwork.OpenTrainMenuPacket(
                released.sidingId, released.vehicleId));
    }

    private static TrainHit pick(Minecraft minecraft, double mouseX, double mouseY) {
        Ray ray = ray(minecraft, mouseX, mouseY);
        if (ray == null) return null;
        TrainHit[] best = {null};
        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> {
            int[] carIndex = {0};
            vehicle.getVehicleCarsAndPositions().forEach(carAndBogies -> {
                carIndex[0]++;
                VehicleCar car = carAndBogies.left();
                List<Vector> centers = new ArrayList<>();
                carAndBogies.right().forEach(pair -> centers.add(Vector.getAverage(pair.left(), pair.right())));
                if (centers.isEmpty()) return;
                Vector first = centers.get(0), last = centers.get(centers.size() - 1);
                double x = centers.stream().mapToDouble(point -> point.x).average().orElse(first.x);
                double y = centers.stream().mapToDouble(point -> point.y).average().orElse(first.y);
                double z = centers.stream().mapToDouble(point -> point.z).average().orElse(first.z);
                double deltaX = last.x - first.x, deltaY = last.y - first.y, deltaZ = last.z - first.z;
                MtrTrainCarTransform transform = new MtrTrainCarTransform(x, y, z,
                        Math.atan2(deltaX, deltaZ), Math.atan2(deltaY, Math.hypot(deltaX, deltaZ)),
                        car.getLength(), car.getWidth());
                Vec3 localStart = transform.toLocal(ray.start);
                Vec3 localEnd = transform.toLocal(ray.end);
                double halfWidth = Math.max(.6D, car.getWidth() * .5D);
                double halfLength = Math.max(1D, car.getLength() * .5D);
                AABB body = new AABB(-halfWidth, MtrTrainCollisionGeometry.BOTTOM, -halfLength,
                        halfWidth, MtrTrainCollisionGeometry.TOP, halfLength);
                body.clip(localStart, localEnd).ifPresent(localHit -> {
                    double distance = localStart.distanceToSqr(localHit);
                    if (best[0] == null || distance < best[0].distanceSqr) {
                        best[0] = new TrainHit(vehicle.vehicleExtraData.getSidingId(), vehicle.getId(), distance);
                    }
                });
            });
        });
        return best[0];
    }

    private static Ray ray(Minecraft minecraft, double mouseX, double mouseY) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 start = camera.getPosition();
        double nx = 0D, ny = 0D;
        if (ClientSpirit.active()) {
            double width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
            nx = Math.max(-1D, Math.min(1D, mouseX / width * 2D - 1D));
            ny = Math.max(-1D, Math.min(1D, 1D - mouseY / height * 2D));
        }
        double tangent = Math.tan(Math.toRadians(minecraft.options.fov().get()) * .5D);
        double aspect = (double) minecraft.getWindow().getScreenWidth() / minecraft.getWindow().getScreenHeight();
        org.joml.Vector3f look = camera.getLookVector(), left = camera.getLeftVector(), up = camera.getUpVector();
        Vec3 direction = new Vec3(look.x(), look.y(), look.z())
                .add(new Vec3(left.x(), left.y(), left.z()).scale(-nx * aspect * tangent))
                .add(new Vec3(up.x(), up.y(), up.z()).scale(ny * tangent)).normalize();
        Vec3 maximum = start.add(direction.scale(160D));
        BlockHitResult block = minecraft.level.clip(new ClipContext(start, maximum,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.getCameraEntity()));
        Vec3 end = block.getType() == HitResult.Type.MISS ? maximum : block.getLocation();
        return new Ray(start, end);
    }

    private record Ray(Vec3 start, Vec3 end) {}
    private record Press(long sidingId, long vehicleId, double mouseX, double mouseY) {}
    private record TrainHit(long sidingId, long vehicleId, double distanceSqr) {}
}
