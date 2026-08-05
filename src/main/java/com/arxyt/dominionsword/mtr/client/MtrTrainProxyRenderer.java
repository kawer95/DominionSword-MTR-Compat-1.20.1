package com.arxyt.dominionsword.mtr.client;

import com.arxyt.dominionsword.mtr.entity.MtrTrainProxyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class MtrTrainProxyRenderer extends EntityRenderer<MtrTrainProxyEntity> {
    public MtrTrainProxyRenderer(EntityRendererProvider.Context context) { super(context); }
    @Override public void render(MtrTrainProxyEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!MtrPortraitRenderState.active()) return;
        pose.pushPose();
        pose.translate(0, .15, 0);
        VertexConsumer out = buffers.getBuffer(RenderType.lines());
        Matrix4f matrix = pose.last().pose(); Matrix3f normal = pose.last().normal();
        // Front outline, windshield, destination sign and rails: a texture-free dedicated train glyph.
        line(out, matrix, normal, -.75F, 0, .75F, 0, 46, 180, 230);
        line(out, matrix, normal, .75F, 0, .75F, 1.55F, 46, 180, 230);
        line(out, matrix, normal, .75F, 1.55F, -.75F, 1.55F, 46, 180, 230);
        line(out, matrix, normal, -.75F, 1.55F, -.75F, 0, 46, 180, 230);
        line(out, matrix, normal, -.55F, .75F, .55F, .75F, 180, 235, 255);
        line(out, matrix, normal, -.55F, 1.3F, .55F, 1.3F, 180, 235, 255);
        line(out, matrix, normal, -.55F, .75F, -.55F, 1.3F, 180, 235, 255);
        line(out, matrix, normal, .55F, .75F, .55F, 1.3F, 180, 235, 255);
        line(out, matrix, normal, -.35F, .25F, -.18F, .25F, 255, 210, 70);
        line(out, matrix, normal, .18F, .25F, .35F, .25F, 255, 210, 70);
        line(out, matrix, normal, -.9F, -.12F, .9F, -.12F, 150, 160, 170);
        pose.popPose();
    }
    private static void line(VertexConsumer out, Matrix4f matrix, Matrix3f normal, float x1, float y1, float x2, float y2, int r, int g, int b) {
        out.vertex(matrix, x1, y1, 0).color(r, g, b, 255).normal(normal, 0, 0, 1).endVertex();
        out.vertex(matrix, x2, y2, 0).color(r, g, b, 255).normal(normal, 0, 0, 1).endVertex();
    }
    @Override public ResourceLocation getTextureLocation(MtrTrainProxyEntity entity) { return InventoryMenu.BLOCK_ATLAS; }
}
