package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class RecursiveFactoryBlockEntity extends EndpointBlockEntity {
    /**
     * One whole cell, edge to edge. The sixteen columns of the cell are the sixteen sixteenths of the
     * block's face, so the preview of one entrance block meets the preview of the entrance block next to
     * it exactly: nothing of either cell is cut off at the seam between them.
     */
    private static final int PREVIEW_SIZE = FactoryData.CELL_SIZE;
    private static final int PREVIEW_HEIGHT = FactoryData.INNER_HEIGHT + 2;
    /**
     * How often the room is sampled again. Every tick, so the preview keeps up with what is built; the
     * sample is only taken while a player is near enough to see the preview, see {@link #PREVIEW_RANGE}.
     */
    private static final int REFRESH_INTERVAL = 1;
    /**
     * Sampling a whole cell costs a few thousand block lookups, so an entrance block nobody is looking at
     * stops sampling entirely. Walking up to it samples on that very tick, so the preview is never stale.
     */
    private static final double PREVIEW_RANGE = 160.0D;

    private long lastRefreshTick = Long.MIN_VALUE;
    private boolean hadAudience;

    public RecursiveFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECURSIVE_FACTORY.get(), pos, state);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (!hasAudience(serverLevel)) {
            hadAudience = false;
            return;
        }

        long gameTime = serverLevel.getGameTime();
        if (hadAudience && gameTime - lastRefreshTick < REFRESH_INTERVAL) {
            return;
        }

        hadAudience = true;
        refreshPreviewSnapshot();
    }

    private boolean hasAudience(ServerLevel serverLevel) {
        for (ServerPlayer player : serverLevel.players()) {
            if (player.blockPosition().closerThan(worldPosition, PREVIEW_RANGE)) {
                return true;
            }
        }
        return false;
    }

    /** Samples the middle of this entrance block's own cell, which is what its face preview shows. */
    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // Stamped before anything can bail out, so the interval check never sees a stale, unset tick.
        lastRefreshTick = serverLevel.getGameTime();
        FactoryData data = serverLevel.getServer() == null ? null : FactoryData.get(serverLevel.getServer());
        FactoryData.FactoryRecord record = data == null || !hasFactoryId() ? null : data.factory(getFactoryId());
        ServerLevel factoryLevel = serverLevel.getServer() == null
                ? null
                : serverLevel.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        // Every entrance block shows its own cell. A factory that has grown covers several cells, and an
        // entrance block that is not the anchor would otherwise keep showing the anchor's cell however
        // much is built in its own one.
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.cellAt(worldPosition);
        if (cell == null && record != null) {
            cell = record.anchorCell();
        }
        if (factoryLevel == null || cell == null) {
            updatePreview(List.of(), List.of(), List.of());
            return;
        }

        // The barrier shell is not part of the preview: only the room's free space is drawn. Its block
        // entities are dropped with it, so the shell's own block entities never travel either.
        List<PreviewBlock> blocks = samplePreview(factoryLevel, cell.center(), PREVIEW_SIZE, PREVIEW_HEIGHT)
                .stream()
                .filter(block -> !block.state().is(ModBlocks.FACTORY_BARRIER.get()))
                .toList();
        updatePreview(
                blocks,
                samplePreviewEntities(factoryLevel, cell.center(), PREVIEW_SIZE, PREVIEW_HEIGHT),
                samplePreviewBlockEntities(factoryLevel, cell.center(), blocks)
        );
    }
}
