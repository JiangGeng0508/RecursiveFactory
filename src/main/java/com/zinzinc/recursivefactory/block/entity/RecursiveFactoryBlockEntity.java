package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
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
    /** How often the room is sampled again. A second, so the preview keeps up with what is being built. */
    private static final int REFRESH_INTERVAL = 20;

    private long lastRefreshTick = Long.MIN_VALUE;

    public RecursiveFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECURSIVE_FACTORY.get(), pos, state);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        long gameTime = serverLevel.getGameTime();
        if (lastRefreshTick != Long.MIN_VALUE && gameTime - lastRefreshTick < REFRESH_INTERVAL) {
            return;
        }

        refreshPreviewSnapshot();
    }

    /** Samples the middle of this entrance block's own cell, which is what its face preview shows. */
    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel) || !hasFactoryId() || serverLevel.getServer() == null) {
            return;
        }

        lastRefreshTick = serverLevel.getGameTime();
        FactoryData data = FactoryData.get(serverLevel.getServer());
        FactoryData.FactoryRecord record = data.factory(getFactoryId());
        ServerLevel factoryLevel = serverLevel.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        // Every entrance block shows its own cell. A factory that has grown covers several cells, and an
        // entrance block that is not the anchor would otherwise keep showing the anchor's cell however
        // much is built in its own one.
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.cellAt(worldPosition);
        if (cell == null && record != null) {
            cell = record.anchorCell();
        }
        if (factoryLevel == null || cell == null) {
            updatePreview(List.of());
            return;
        }

        // The barrier shell is not part of the preview: only the room's free space is drawn.
        updatePreview(samplePreview(
                factoryLevel,
                cell.center(),
                PREVIEW_SIZE,
                PREVIEW_HEIGHT
        ).stream()
                .filter(block -> !block.state().is(ModBlocks.FACTORY_BARRIER.get()))
                .toList());
    }
}
