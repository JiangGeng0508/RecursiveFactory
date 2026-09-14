package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.network.FactoryEnclosurePackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryEnclosure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public final class MirrorFactoryBlockEntity extends EndpointBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PREVIEW_RADIUS = 8;
    private static final int PREVIEW_HEIGHT = FactoryData.CEILING_Y - FactoryData.FLOOR_Y + 1;
    private static final int REFRESH_INTERVAL = 100;
    private static final int ENCLOSURE_INTERVAL = 100;

    private long lastRefreshTick = Long.MIN_VALUE;
    private long lastEnclosureTick = Long.MIN_VALUE;
    private int lastEnclosureHash;
    private boolean enclosureCached;

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
        tickPreview(serverLevel, gameTime);
        tickEnclosure(serverLevel, gameTime);
    }

    @Override
    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel) || !hasFactoryId() || serverLevel.getServer() == null) {
            return;
        }
        updatePreview(sampleExternal(serverLevel));
    }

    private void tickPreview(ServerLevel serverLevel, long gameTime) {
        if (lastRefreshTick != Long.MIN_VALUE && gameTime - lastRefreshTick < REFRESH_INTERVAL) {
            return;
        }
        lastRefreshTick = gameTime;
        updatePreview(sampleExternal(serverLevel));
    }

    private List<PreviewBlock> sampleExternal(ServerLevel serverLevel) {
        FactoryData.FactoryRecord record = factoryRecord(serverLevel);
        ServerLevel externalLevel = record == null ? null : externalLevel(serverLevel, record);
        if (externalLevel == null) {
            return List.of();
        }
        return samplePreview(externalLevel, record.entrancePos(), PREVIEW_RADIUS, PREVIEW_HEIGHT);
    }

    private void tickEnclosure(ServerLevel serverLevel, long gameTime) {
        if (lastEnclosureTick != Long.MIN_VALUE && gameTime - lastEnclosureTick < ENCLOSURE_INTERVAL) {
            return;
        }
        lastEnclosureTick = gameTime;

        FactoryData.FactoryRecord record = factoryRecord(serverLevel);
        if (record == null) {
            return;
        }
        List<ServerPlayer> audience = playersInFactory(serverLevel, record);
        if (audience.isEmpty()) {
            return;
        }

        List<PreviewBlock> blocks = buildEnclosure(serverLevel, record);
        if (blocks == null) {
            return;
        }
        if (enclosureCached && blocks.hashCode() == lastEnclosureHash) {
            return;
        }
        sendEnclosure(record, blocks, audience);
    }

    /** Resamples the surrounding world and pushes it to a single player, used for on demand requests. */
    public void sendEnclosureTo(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel) || !hasFactoryId() || serverLevel.getServer() == null) {
            return;
        }
        FactoryData.FactoryRecord record = factoryRecord(serverLevel);
        if (record == null) {
            return;
        }
        List<PreviewBlock> blocks = buildEnclosure(serverLevel, record);
        if (blocks == null) {
            return;
        }
        sendEnclosure(record, blocks, List.of(player));
    }

    private List<PreviewBlock> buildEnclosure(ServerLevel serverLevel, FactoryData.FactoryRecord record) {
        ServerLevel externalLevel = externalLevel(serverLevel, record);
        if (externalLevel == null) {
            return null;
        }
        return FactoryEnclosure.sample(externalLevel, record);
    }

    private void sendEnclosure(FactoryData.FactoryRecord record, List<PreviewBlock> blocks, List<ServerPlayer> audience) {
        lastEnclosureHash = blocks.hashCode();
        enclosureCached = true;

        ChunkPos chunk = record.baseChunk();

        LOGGER.info("Sending factory enclosure for chunk [{}, {}] with {} blocks", chunk.x, chunk.z, blocks.size());
        FactoryEnclosurePackets.Sync packet = new FactoryEnclosurePackets.Sync(chunk.x, chunk.z, blocks);
        for (ServerPlayer player : audience) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private List<ServerPlayer> playersInFactory(ServerLevel serverLevel, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : serverLevel.players()) {
            if (!player.chunkPosition().equals(chunk)) {
                continue;
            }
            double y = player.getY();
            if (y < FactoryData.FLOOR_Y - 1.0D || y > FactoryData.CEILING_Y + 1.0D) {
                continue;
            }
            players.add(player);
        }
        return players;
    }

    private FactoryData.FactoryRecord factoryRecord(ServerLevel serverLevel) {
        FactoryData.FactoryRecord record = FactoryData.get(serverLevel.getServer()).factory(getFactoryId());
        if (record == null || record.entranceDimension() == null) {
            return null;
        }
        return record;
    }

    private ServerLevel externalLevel(ServerLevel serverLevel, FactoryData.FactoryRecord record) {
        return serverLevel.getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, record.entranceDimension())
        );
    }
}