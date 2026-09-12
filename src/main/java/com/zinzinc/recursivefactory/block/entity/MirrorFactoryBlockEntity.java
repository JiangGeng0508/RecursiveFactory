package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class MirrorFactoryBlockEntity extends EndpointBlockEntity {
    private static final int PREVIEW_RADIUS = 4;
    private static final int PREVIEW_HEIGHT = 5;
    private static final int REFRESH_INTERVAL = 100;

    private long lastRefreshTick = Long.MIN_VALUE;

    public MirrorFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MIRROR_FACTORY.get(), pos, state);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (!(level instanceof ServerLevel serverLevel) || !hasFactoryId() || serverLevel.getServer() == null) {
            return;
        }

        long gameTime = serverLevel.getGameTime();
        if (lastRefreshTick != Long.MIN_VALUE && gameTime - lastRefreshTick < REFRESH_INTERVAL) {
            return;
        }

        lastRefreshTick = gameTime;
        FactoryData data = FactoryData.get(serverLevel.getServer());
        FactoryData.FactoryRecord record = data.factory(getFactoryId());
        if (record == null || record.entranceDimension() == null) {
            updatePreview(List.of());
            return;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, record.entranceDimension());
        ServerLevel externalLevel = serverLevel.getServer().getLevel(dimensionKey);
        if (externalLevel == null) {
            updatePreview(List.of());
            return;
        }

        updatePreview(samplePreview(
                externalLevel,
                record.entrancePos(),
                PREVIEW_RADIUS,
                PREVIEW_HEIGHT
        ));
    }
}
