package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class RecursiveFactoryRenderer implements BlockEntityRenderer<RecursiveFactoryBlockEntity> {
    public RecursiveFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RecursiveFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // The entrance block is invisible now, its faces being the portal planes, so the miniature preview
        // would just float inside those planes. Kept, commented out, in case the preview is wanted again.
        // if (blockEntity.getLevel() == null) {
        //     return;
        // }
        // MirrorFactoryRenderer.requestPreview(blockEntity);
        // MirrorFactoryRenderer.renderPreview(blockEntity, poseStack, bufferSource);
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
