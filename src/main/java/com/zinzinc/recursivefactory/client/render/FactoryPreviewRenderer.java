package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.FactoryPreviewBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

public final class FactoryPreviewRenderer implements BlockEntityRenderer<FactoryPreviewBlockEntity> {
    public FactoryPreviewRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(FactoryPreviewBlockEntity display, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light, int overlay) {
        if (display.getLevel() == null) return;
        EndpointPreviewRenderer.requestPreview(display);
        poseStack.pushPose();
        try {
            // Keep the source's world orientation. The miniature is visible from every side.
            poseStack.translate(0, 1, 0);
            EndpointPreviewRenderer.renderPreview(display, poseStack, buffers, partialTick);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(FactoryPreviewBlockEntity display) {
        return new AABB(display.getBlockPos()).expandTowards(0, 1, 0);
    }

    @Override
    public int getViewDistance() { return 128; }
}
