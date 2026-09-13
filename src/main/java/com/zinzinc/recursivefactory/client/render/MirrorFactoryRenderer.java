package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MirrorFactoryRenderer implements BlockEntityRenderer<MirrorFactoryBlockEntity> {
    private static final float PREVIEW_SIZE = 0.70F;
    private static final float PREVIEW_Y_OFFSET = 0.50F;
    private static final int REQUEST_INTERVAL_TICKS = 40;
    private static final Map<EndpointBlockEntity, CachedProjection> PROJECTION_CACHE = new WeakHashMap<>();
    private static final Map<EndpointBlockEntity, Long> LAST_REQUEST_TICKS = new WeakHashMap<>();

    public MirrorFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MirrorFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        requestPreview(blockEntity);
        renderPreview(blockEntity, poseStack, bufferSource);
    }

    /**
     * Asks the server for a fresh snapshot on a slow interval, so outside changes reach clients even
     * when a broadcast was missed, for example while the block's chunk was unloaded.
     */
    static void requestPreview(EndpointBlockEntity blockEntity) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            LAST_REQUEST_TICKS.remove(blockEntity);
            return;
        }
        long gameTime = level.getGameTime();
        Long lastRequest = LAST_REQUEST_TICKS.get(blockEntity);
        if (lastRequest != null && gameTime - lastRequest < REQUEST_INTERVAL_TICKS) {
            return;
        }
        LAST_REQUEST_TICKS.put(blockEntity, gameTime);
        PacketDistributor.sendToServer(new EndpointPreviewPackets.Request(blockEntity.getBlockPos()));
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
