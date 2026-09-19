package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws an endpoint block's face preview: the sampled blocks of the far side, scaled down and laid on
 * the block's surface. Shared by every endpoint block, so the block entity renderers stay thin.
 */
public final class EndpointPreviewRenderer {
    /**
     * One room cell is drawn one block across, so a preview covers the whole face of the block it stands
     * for. The sample is centred on the cell, and a cell is sixteen blocks across, so this mapping puts
     * the cell's footprint exactly on the block's face and neighbouring cells line up block for block.
     */
    private static final float PREVIEW_SCALE = 1.0F / FactoryData.CELL_SIZE;
    /**
     * Height is not part of that mapping: a room is much taller than it is wide, so the sample is scaled
     * the same way and simply reaches up out of the block. This is where the room's floor ends up: on top
     * of the block's own model, which stands 4 pixels high, so the miniature sits on the block instead of
     * cutting into it.
     */
    private static final float PREVIEW_Y_OFFSET = 0.25F;
    /**
     * How often the client asks again when nothing was pushed to it. The server pushes every change, so
     * this is only a safety net for a broadcast that was missed while the block's chunk was unloaded.
     */
    private static final int REQUEST_INTERVAL_TICKS = 40;
    private static final Map<EndpointBlockEntity, CachedProjection> PROJECTION_CACHE = new WeakHashMap<>();
    private static final Map<EndpointBlockEntity, Long> LAST_REQUEST_TICKS = new WeakHashMap<>();

    private EndpointPreviewRenderer() {
    }

    /**
     * Asks the server for a fresh snapshot on a slow interval, so outside changes reach clients even
     * when a broadcast was missed, for example while the block's chunk was unloaded.
     */
    public static void requestPreview(EndpointBlockEntity blockEntity) {
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

    public static void renderPreview(EndpointBlockEntity blockEntity, PoseStack poseStack,
                                     MultiBufferSource bufferSource) {
        FactoryProjectionCache cache = getProjectionCache(blockEntity);
        if (cache == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, PREVIEW_Y_OFFSET, 0.5D);
        poseStack.scale(PREVIEW_SCALE, PREVIEW_SCALE, PREVIEW_SCALE);
        // Sample coordinates are offsets from the centre of the cell, so local (0, y, 0) is the middle of
        // the cell's footprint and the preview comes out centred on the block no matter how lopsided the
        // sampled room is. Only the height is taken from the bounds, to sit the floor on the block.
        poseStack.translate(0.0D, -cache.getBounds().minY, 0.0D);
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

    private record CachedProjection(int hash, FactoryProjectionCache cache) {
    }
}
