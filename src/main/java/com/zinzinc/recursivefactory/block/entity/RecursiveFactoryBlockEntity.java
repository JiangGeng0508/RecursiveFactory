package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class RecursiveFactoryBlockEntity extends EndpointBlockEntity {
    private static final int PREVIEW_RADIUS = 4;
    private static final int PREVIEW_HEIGHT = 5;
    private static final int REFRESH_INTERVAL = 100;

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

    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel) || !hasFactoryId() || serverLevel.getServer() == null) {
            return;
        }

        lastRefreshTick = serverLevel.getGameTime();
        FactoryData data = FactoryData.get(serverLevel.getServer());
        FactoryData.FactoryRecord record = data.factory(getFactoryId());
        ServerLevel factoryLevel = serverLevel.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (record == null || factoryLevel == null) {
            updatePreview(List.of());
            return;
        }

        updatePreview(samplePreview(
                factoryLevel,
                record.mirrorPos(),
                PREVIEW_RADIUS,
                PREVIEW_HEIGHT
        ));
    }
}
