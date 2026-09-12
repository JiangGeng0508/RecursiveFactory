package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

public final class MirrorFactoryRenderer implements BlockEntityRenderer<MirrorFactoryBlockEntity> {
    private static final float PREVIEW_SCALE = 0.08F;
    private static final float PREVIEW_Y_OFFSET = 1.05F;

    public MirrorFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MirrorFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity.getPreviewBlocks().isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, PREVIEW_Y_OFFSET, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(getRotation(blockEntity, partialTick)));
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
                    packedLight,
                    OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    @Override
    public boolean shouldRender(MirrorFactoryBlockEntity blockEntity, Vec3 cameraPos) {
        return BlockEntityRenderer.super.shouldRender(blockEntity, cameraPos);
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    private static float getRotation(MirrorFactoryBlockEntity blockEntity, float partialTick) {
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();
        return ((gameTime + partialTick) % 360.0F) * 0.5F;
    }
}
