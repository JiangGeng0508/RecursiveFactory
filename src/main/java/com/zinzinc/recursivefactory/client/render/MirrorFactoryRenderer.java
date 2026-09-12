package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class MirrorFactoryRenderer implements BlockEntityRenderer<MirrorFactoryBlockEntity> {
    private static final float PREVIEW_SIZE = 0.70F;
    private static final float PREVIEW_Y_OFFSET = 0.50F;
    private static final Map<EndpointBlockEntity, CachedProjection> PROJECTION_CACHE = new WeakHashMap<>();

    public MirrorFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MirrorFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        renderPreview(blockEntity, poseStack, bufferSource);
    }

    static void renderPreview(EndpointBlockEntity blockEntity, PoseStack poseStack, MultiBufferSource bufferSource) {
        FactoryProjectionCache cache = getProjectionCache(blockEntity);
        if (cache == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, PREVIEW_Y_OFFSET, 0.5D);
        float scale = (float) (PREVIEW_SIZE / Math.max(
                cache.getBounds().getXsize(),
                Math.max(cache.getBounds().getYsize(), cache.getBounds().getZsize())
        ));
        poseStack.scale(scale, scale, scale);
        poseStack.translate(
                -cache.getBounds().getCenter().x,
                -cache.getBounds().getCenter().y,
                -cache.getBounds().getCenter().z
        );
        cache.render(poseStack, bufferSource);
        poseStack.popPose();
    }

    private static FactoryProjectionCache getProjectionCache(EndpointBlockEntity blockEntity) {
        if (blockEntity.getLevel() == null || blockEntity.getPreviewBlocks().isEmpty()) {
            PROJECTION_CACHE.remove(blockEntity);
            return null;
        }

        int hash = blockEntity.getPreviewBlocks().hashCode();
        CachedProjection cached = PROJECTION_CACHE.get(blockEntity);
        if (cached != null && cached.hash() == hash) {
            return cached.cache();
        }

        FactoryProjectionCache rebuilt = new FactoryProjectionCache(
                blockEntity.getLevel(),
                blockEntity.getPreviewBlocks()
        );
        PROJECTION_CACHE.put(blockEntity, new CachedProjection(hash, rebuilt));
        return rebuilt;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    private record CachedProjection(int hash, FactoryProjectionCache cache) {
    }
}
