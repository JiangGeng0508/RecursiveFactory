package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class MirrorFactoryRenderer implements BlockEntityRenderer<MirrorFactoryBlockEntity> {
    private static final float PREVIEW_SCALE = 0.10F;
    private static final float PREVIEW_Y_OFFSET = 0.50F;

    public MirrorFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MirrorFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        renderPreview(blockEntity, poseStack, bufferSource);
    }

    static void renderPreview(EndpointBlockEntity blockEntity, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (blockEntity.getPreviewBlocks().isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, PREVIEW_Y_OFFSET, 0.5D);
        poseStack.scale(PREVIEW_SCALE, PREVIEW_SCALE, PREVIEW_SCALE);

        for (EndpointBlockEntity.PreviewBlock previewBlock : blockEntity.getPreviewBlocks()) {
            poseStack.pushPose();
            poseStack.translate(
                    previewBlock.x() - 0.5D,
                    previewBlock.y() + 1.0D,
                    previewBlock.z() - 0.5D
            );
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    previewBlock.state(),
                    poseStack,
                    bufferSource,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
