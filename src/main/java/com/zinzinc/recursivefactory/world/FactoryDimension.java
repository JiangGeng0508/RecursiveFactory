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
import net.minecraft.world.level.block.Block;
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
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            ChunkPos chunk = new ChunkPos(cell.roomX() >> 4, cell.roomZ() >> 4);
            level.setChunkForced(chunk.x, chunk.z, true);
            generateFloor(level, record, cell);
            sealFloor(level, cell);
        }
        borderRoom(level, record);
    }

    /**
     * The palette a factory's border colour is picked from. One colour is chosen for the whole frame rather
     * than per block, and it is derived from the factory's chunk so it stays the same on every rebuild.
     */
    private static final Block[] BORDER_CONCRETE = {
            Blocks.WHITE_CONCRETE,
            Blocks.ORANGE_CONCRETE,
            Blocks.MAGENTA_CONCRETE,
            Blocks.LIGHT_BLUE_CONCRETE,
            Blocks.YELLOW_CONCRETE,
            Blocks.LIME_CONCRETE,
            Blocks.PINK_CONCRETE,
            Blocks.GRAY_CONCRETE,
            Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE,
            Blocks.PURPLE_CONCRETE,
            Blocks.BLUE_CONCRETE,
            Blocks.BROWN_CONCRETE,
            Blocks.GREEN_CONCRETE,
            Blocks.RED_CONCRETE,
            Blocks.BLACK_CONCRETE
    };

    /**
     * A frame of randomly coloured concrete around the room box: the ring around the platform edge, a ring
     * at the ceiling level and a post down each corner, so the box reads as one outlined volume.
     *
     * <p>It cannot be the faces themselves, because the four sides and the ceiling are the portal planes.
     * The frame therefore follows the box's edges, which is what outlines the openings, and it sits right on
     * them so the frame and the portals line up flush.
     */
    /**
     * The room's height in blocks, from which entrance block variant it was placed from: the short entrance
     * makes a half height room. Has to match what FactoryPortal derives, the doorway height times the scale.
     */
    public static int roomHeight(MinecraftServer server, FactoryData.FactoryRecord record) {
        if (record.entranceDimension() != null) {
            ServerLevel entranceLevel = server.getLevel(
                    ResourceKey.create(Registries.DIMENSION, record.entranceDimension())
            );
            if (entranceLevel != null
                    && entranceLevel.getBlockState(record.entrancePos()).is(ModBlocks.RECURSIVE_FACTORY_SHORT.get())) {
                return FactoryData.SHORT_ROOM_HEIGHT;
            }
        }
        return FactoryData.TALL_ROOM_HEIGHT;
    }

    /**
     * A frame of randomly coloured concrete around the room's outline: a ring on the floor, a ring at the
     * ceiling level and a post down each corner, so the room reads as one outlined volume.
     *
     * <p>It cannot be the faces themselves, because the sides and the ceiling are the portal planes. The
     * frame follows the outline of the whole cell union: an edge between two cells of the same factory is
     * interior and gets no frame, and collinear edges of neighbouring cells join into one unbroken run.
     */
    private static void borderRoom(ServerLevel level, FactoryData.FactoryRecord record) {
        int bottom = FactoryData.FLOOR_Y;
        int top = FactoryData.FLOOR_Y + roomHeight(level.getServer(), record) - 1;
        ChunkPos anchor = record.baseChunk();
        Block border = BORDER_CONCRETE[Math.floorMod(scatter(anchor.getMinBlockX(), anchor.getMinBlockZ()), BORDER_CONCRETE.length)];

        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            boolean north = record.cellAt(cell.entrance().north()) == null;
            boolean south = record.cellAt(cell.entrance().south()) == null;
            boolean west = record.cellAt(cell.entrance().west()) == null;
            boolean east = record.cellAt(cell.entrance().east()) == null;
            for (int i = 0; i < 16; i++) {
                if (north) {
                    place(level, new BlockPos(cell.roomX() + i, bottom, cell.roomZ()), border);
                    place(level, new BlockPos(cell.roomX() + i, top, cell.roomZ()), border);
                }
                if (south) {
                    place(level, new BlockPos(cell.roomX() + i, bottom, cell.roomZ() + 15), border);
                    place(level, new BlockPos(cell.roomX() + i, top, cell.roomZ() + 15), border);
                }
                if (west) {
                    place(level, new BlockPos(cell.roomX(), bottom, cell.roomZ() + i), border);
                    place(level, new BlockPos(cell.roomX(), top, cell.roomZ() + i), border);
                }
                if (east) {
                    place(level, new BlockPos(cell.roomX() + 15, bottom, cell.roomZ() + i), border);
                    place(level, new BlockPos(cell.roomX() + 15, top, cell.roomZ() + i), border);
                }
            }
            cornerPost(level, border, north && west, cell.roomX(), cell.roomZ(), bottom, top);
            cornerPost(level, border, north && east, cell.roomX() + 15, cell.roomZ(), bottom, top);
            cornerPost(level, border, south && west, cell.roomX(), cell.roomZ() + 15, bottom, top);
            cornerPost(level, border, south && east, cell.roomX() + 15, cell.roomZ() + 15, bottom, top);
        }
    }

    private static void cornerPost(ServerLevel level, Block border, boolean atCorner, int x, int z, int bottom, int top) {
        if (!atCorner) {
            return;
        }
        for (int y = bottom + 1; y < top; y++) {
            place(level, new BlockPos(x, y, z), border);
        }
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        if (!level.getBlockState(pos).is(block)) {
            level.setBlock(pos, block.defaultBlockState(), 3);
        }
    }

    /**
     * Stable spread of the border colours: derived from the position rather than drawn randomly so the
     * ring keeps the same colours every time the floor is rebuilt.
     */
    private static int scatter(int x, int z) {
        int value = x * 374761393 + z * 668265263;
        value = (value ^ (value >>> 13)) * 1274126177;
        return value ^ (value >>> 16);
    }

    /**
     * Bedrock under the platform. The floor is the one face of the room without a portal, so it has to be
     * sealed: otherwise the platform is a single floating layer over the void.
     */
    private static void sealFloor(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        int y = FactoryData.FLOOR_Y - 1;
        for (int x = cell.roomX(); x < cell.roomX() + 16; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + 16; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(Blocks.BEDROCK)) {
                    level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void generateFloor(ServerLevel level, FactoryData.FactoryRecord record,
                                      FactoryData.FactoryRecord.Cell cell) {
        BlockPos marker = new BlockPos(cell.roomX() + 8, FactoryData.FLOOR_Y, cell.roomZ() + 8);
        BlockState markerState = level.getBlockState(marker);
        if (markerState.is(Blocks.SNOW_BLOCK) || markerState.is(Blocks.WHITE_CONCRETE)) {
            return;
        }

        for (int x = cell.roomX(); x < cell.roomX() + 16; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + 16; z++) {
                boolean white = ((x ^ z) & 1) == 0;
                level.setBlock(
                        new BlockPos(x, FactoryData.FLOOR_Y, z),
                        white ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState(),
                        3
                );
            }
        }

        // One mirror per factory, in the anchor cell's centre.
        if (!cell.entrance().equals(record.entrancePos())) {
            return;
        }
        BlockPos mirrorPos = marker.above();
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
        if (pos.getY() < FactoryData.FLOOR_Y - 1 || pos.getY() > FactoryData.CEILING_Y + 1) {
            return false;
        }
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (pos.getX() >= cell.roomX() && pos.getX() < cell.roomX() + 16
                    && pos.getZ() >= cell.roomZ() && pos.getZ() < cell.roomZ() + 16) {
                return true;
            }
        }
        return false;
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
