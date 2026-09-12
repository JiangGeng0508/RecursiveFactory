package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class RecursiveFactoryRenderer implements BlockEntityRenderer<RecursiveFactoryBlockEntity> {
    private static final Map<RecursiveFactoryBlockEntity, Long> LAST_REQUEST_TICKS = new WeakHashMap<>();

    public RecursiveFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RecursiveFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (blockEntity.getLevel() == null) {
            return;
        }
        if (blockEntity.getPreviewBlocks().isEmpty()) {
            long gameTime = blockEntity.getLevel().getGameTime();
            Long lastRequestTick = LAST_REQUEST_TICKS.get(blockEntity);
            if (lastRequestTick == null || gameTime - lastRequestTick >= 20L) {
                LAST_REQUEST_TICKS.put(blockEntity, gameTime);
                PacketDistributor.sendToServer(new EndpointPreviewPackets.Request(blockEntity.getBlockPos()));
            }
            return;
        }
        LAST_REQUEST_TICKS.remove(blockEntity);
        MirrorFactoryRenderer.renderPreview(blockEntity, poseStack, bufferSource);
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
