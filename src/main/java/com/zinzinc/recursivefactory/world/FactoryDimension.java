package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
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

    /** Horizontal reach of the room, measured from the middle of the mirror block. */
    public static final double ROOM_HALF_EXTENT = 7.0D;
    /** Horizontal reach of the landing area beside an endpoint block. */
    public static final double BLOCK_HALF_EXTENT = 1.0D;

    private FactoryDimension() {
    }

    public static void initialize(MinecraftServer server) {
        ServerLevel level = server.getLevel(LEVEL_KEY);
        if (level == null) {
            return;
        }
        for (FactoryData.FactoryRecord record : FactoryData.get(server).factories()) {
            prepare(level, record);
            FactoryPortal.ensure(level, record.id());
        }
    }

    public static void prepare(ServerLevel level, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        level.setChunkForced(chunk.x, chunk.z, true);
        generateFloor(level, record);
        sealFloor(level, record);
    }

    /**
     * Bedrock under the platform. The floor is the one face of the room without a portal, so it has to be
     * sealed: otherwise the platform is a single floating layer over the void.
     */
    private static void sealFloor(ServerLevel level, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        int y = FactoryData.FLOOR_Y - 1;
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(Blocks.BEDROCK)) {
                    level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
                }
            }
        }
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

    /**
     * Landing spot inside the room for a player walking in from the given position outside. The room
     * reaches {@link #ROOM_HALF_EXTENT} blocks out from the mirror block while the area around an
     * endpoint only reaches {@link #BLOCK_HALF_EXTENT}, so stepping in from the centre of a block's
     * east side arrives at the centre of the room's east edge.
     */
    public static BlockPos entryTarget(FactoryData.FactoryRecord record, double playerX, double playerZ) {
        BlockPos entrance = record.entrancePos();
        double[] offset = rescaleOffset(
                playerX - (entrance.getX() + 0.5D),
                playerZ - (entrance.getZ() + 0.5D),
                BLOCK_HALF_EXTENT,
                ROOM_HALF_EXTENT
        );
        ChunkPos chunk = record.baseChunk();
        return BlockPos.containing(
                chunk.getMiddleBlockX() + 0.5D + offset[0],
                FactoryData.FLOOR_Y + 1,
                chunk.getMiddleBlockZ() + 0.5D + offset[1]
        );
    }

    /**
     * Preferred landing spot beside the entrance block for a player leaving the room from the given
     * position inside, the reverse of {@link #entryTarget}.
     */
    public static BlockPos exitTarget(FactoryData.FactoryRecord record, double playerX, double playerZ) {
        ChunkPos chunk = record.baseChunk();
        double[] offset = rescaleOffset(
                playerX - (chunk.getMiddleBlockX() + 0.5D),
                playerZ - (chunk.getMiddleBlockZ() + 0.5D),
                ROOM_HALF_EXTENT,
                BLOCK_HALF_EXTENT
        );
        BlockPos entrance = record.entrancePos();
        return BlockPos.containing(
                entrance.getX() + 0.5D + offset[0],
                entrance.getY(),
                entrance.getZ() + 0.5D + offset[1]
        );
    }

    /** Rescales an offset gathered around one endpoint to the same relative offset around the other. */
    private static double[] rescaleOffset(double offsetX, double offsetZ,
                                          double fromHalfExtent, double toHalfExtent) {
        double scale = toHalfExtent / fromHalfExtent;
        return new double[] {
                clamp(offsetX * scale, toHalfExtent),
                clamp(offsetZ * scale, toHalfExtent)
        };
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }
}
