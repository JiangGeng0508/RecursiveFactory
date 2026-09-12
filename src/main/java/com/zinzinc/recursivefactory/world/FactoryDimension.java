package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class FactoryDimension {
    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "recursive_factory")
    );

    private FactoryDimension() {
    }

    public static void initialize(MinecraftServer server) {
        ServerLevel level = server.getLevel(LEVEL_KEY);
        if (level == null) {
            return;
        }
        for (FactoryData.FactoryRecord record : FactoryData.get(server).factories()) {
            prepare(level, record);
        }
    }

    public static void prepare(ServerLevel level, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        level.setChunkForced(chunk.x, chunk.z, true);
        generateFloor(level, record);
    }

    private static void generateFloor(ServerLevel level, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        BlockPos marker = new BlockPos(chunk.getMinBlockX() + 8, FactoryData.FLOOR_Y, chunk.getMinBlockZ() + 8);
        BlockState markerState = level.getBlockState(marker);
        if (markerState.is(Blocks.SNOW_BLOCK) || markerState.is(Blocks.WHITE_CONCRETE)) {
            return;
        }

        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                boolean white = ((x ^ z) & 1) == 0;
                level.setBlock(
                        new BlockPos(x, FactoryData.FLOOR_Y, z),
                        white ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState(),
                        3
                );
            }
        }

        BlockPos mirrorPos = new BlockPos(chunk.getMinBlockX() + 8, FactoryData.FLOOR_Y + 1, chunk.getMinBlockZ() + 8);
        if (level.getBlockState(mirrorPos).isAir()) {
            BlockState mirrorState = ModBlocks.MIRROR_FACTORY.get().defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, false);
            level.setBlock(mirrorPos, mirrorState, 3);
            if (level.getBlockEntity(mirrorPos) instanceof MirrorFactoryBlockEntity mirror
                    && level.getServer() != null) {
                mirror.setFactoryId(record.id());
                FactoryData.get(level.getServer()).bindMirror(
                        record.id(),
                        level.dimension().location(),
                        mirrorPos
                );
            }
        }
    }

    public static boolean contains(FactoryData.FactoryRecord record, BlockPos pos) {
        ChunkPos chunk = record.baseChunk();
        return (pos.getX() >> 4) == chunk.x
                && (pos.getZ() >> 4) == chunk.z
                && pos.getY() >= FactoryData.FLOOR_Y - 1
                && pos.getY() <= FactoryData.CEILING_Y + 1;
    }

    @Nullable
    public static FactoryData.FactoryRecord nearestFactory(FactoryData data, BlockPos pos) {
        FactoryData.FactoryRecord nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (FactoryData.FactoryRecord record : data.factories()) {
            ChunkPos chunk = record.baseChunk();
            double distance = pos.distToCenterSqr(
                    chunk.getMiddleBlockX(),
                    pos.getY(),
                    chunk.getMiddleBlockZ()
            );
            if (distance < nearestDistance) {
                nearest = record;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public static BlockPos entryPosition(FactoryData.FactoryRecord record, Direction approachDirection) {
        ChunkPos chunk = record.baseChunk();
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        switch (approachDirection) {
            case NORTH -> z = chunk.getMinBlockZ();
            case SOUTH -> z = chunk.getMaxBlockZ();
            case WEST -> x = chunk.getMinBlockX();
            case EAST -> x = chunk.getMaxBlockX();
            default -> {
            }
        }
        return new BlockPos(x, FactoryData.FLOOR_Y + 1, z);
    }

    public static BlockPos exitPosition(FactoryData.FactoryRecord record, Direction exitDirection) {
        BlockPos entrance = record.entrancePos();
        return switch (exitDirection) {
            case NORTH -> entrance.relative(Direction.NORTH);
            case SOUTH -> entrance.relative(Direction.SOUTH);
            case WEST -> entrance.relative(Direction.WEST);
            case EAST -> entrance.relative(Direction.EAST);
            default -> entrance.above();
        };
    }

    public static Direction horizontalDirectionFrom(BlockPos origin, double x, double z) {
        double deltaX = x - (origin.getX() + 0.5D);
        double deltaZ = z - (origin.getZ() + 0.5D);
        if (Math.abs(deltaX) > Math.abs(deltaZ)) {
            return deltaX >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return deltaZ >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }
}
