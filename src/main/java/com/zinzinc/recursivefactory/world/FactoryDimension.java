package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
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

/**
 * The factory dimension and the rooms inside it.
 *
 * <p>A factory's room is the union of its entrance cells: every entrance block stands for one sixteen by
 * sixteen cell, and the room layout is the entrance layout translated. Placing entrance blocks next to
 * each other therefore grows one room instead of making several, which is what lets a factory be built
 * out of as many cells as the player cares to place.
 *
 * <p>The room is a closed box: a checkerboard floor with bedrock under it, and a
 * {@code factory_barrier} shell around the sides and over the top.
 */
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

    /** Builds (or rebuilds) everything a factory's room is made of. */
    public static void prepare(ServerLevel level, FactoryData.FactoryRecord record) {
        if (record.cells().isEmpty()) {
            return;
        }
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            ChunkPos chunk = new ChunkPos(cell.roomX() >> 4, cell.roomZ() >> 4);
            level.setChunkForced(chunk.x, chunk.z, true);
            generateFloor(level, cell);
            sealFloor(level, cell);
        }
        buildShell(level, record);
    }

    /**
     * The one barrier block of a factory that carries the return half of its link to the outside: items
     * put into the entrance block come out here, and its face is what the entrance block's redstone
     * output faces. Every other barrier block carries the other direction (see FactoryRelay).
     *
     * <p>It is derived rather than saved, from the anchor cell's x and the north edge of the union, so
     * it moves with the room instead of going stale.
     */
    @Nullable
    public static BlockPos portPos(FactoryData.FactoryRecord record) {
        FactoryData.FactoryRecord.Cell anchor = record.anchorCell();
        if (anchor == null) {
            return null;
        }
        int minZ = Integer.MAX_VALUE;
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            minZ = Math.min(minZ, cell.roomZ());
        }
        // The port stands on a column of the room that lies on its north edge. The anchor's column is one,
        // unless the room holds cells that do not touch - losing a cell in the middle leaves the anchor's
        // column outside the room - in which case the middle of a cell that is on that edge is used, so
        // the port never ends up floating in the void with no block to stand on.
        int portX = anchor.roomX() + FactoryData.CELL_SIZE / 2;
        if (!record.roomContains(portX, minZ)) {
            for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
                if (cell.roomZ() == minZ) {
                    portX = cell.roomX() + FactoryData.CELL_SIZE / 2;
                    break;
                }
            }
        }
        return new BlockPos(
                portX,
                FactoryData.FLOOR_Y + 1,
                minZ
        );
    }

    /** Where a player arriving through this entrance cell lands: the middle of its room cell. */
    public static BlockPos entryTarget(FactoryData.FactoryRecord.Cell cell) {
        return cell.center();
    }

    @Nullable
    public static FactoryData.FactoryRecord nearestFactory(FactoryData data, BlockPos pos) {
        FactoryData.FactoryRecord nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (FactoryData.FactoryRecord record : data.factories()) {
            for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
                BlockPos center = cell.center();
                double distance = pos.distToCenterSqr(center.getX(), pos.getY(), center.getZ());
                if (distance < nearestDistance) {
                    nearest = record;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    /**
     * Drops the shell of one cell, so a factory that lost a cell does not keep a wall standing in what
     * used to be its edge. The checkerboard floor and the bedrock seal below it are left alone.
     */
    public static void clearShellAround(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        sweepBarriers(
                level,
                cell.roomX(), cell.roomZ(),
                cell.roomX() + FactoryData.CELL_SIZE - 1, cell.roomZ() + FactoryData.CELL_SIZE - 1
        );
    }

    /**
     * The room's shell, built on the room's own footprint: a column that is part of the union of the
     * cells but has a neighbour outside it is solid from the base layer to the ceiling, and the base and
     * ceiling layers are solid all the way across. What is left in between is the free space of the room.
     *
     * <p>Taking the wall from the outline of the union rather than from the box around it matters for
     * factories whose cells do not form a rectangle: an L shaped room keeps a wall along the step, where
     * a box shaped shell would leave the room open to the outside at the concave corner.
     *
     * <p>Anything inside that footprint which is neither wall nor base nor ceiling is cleared, so growing
     * a factory takes the wall between the old room and the new cell away again.
     */
    private static void buildShell(ServerLevel level, FactoryData.FactoryRecord record) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            minX = Math.min(minX, cell.roomX());
            maxX = Math.max(maxX, cell.roomX() + FactoryData.CELL_SIZE - 1);
            minZ = Math.min(minZ, cell.roomZ());
            maxZ = Math.max(maxZ, cell.roomZ() + FactoryData.CELL_SIZE - 1);
        }

        Block barrier = ModBlocks.FACTORY_BARRIER.get();
        int bottom = FactoryData.BASE_Y;
        int top = FactoryData.CEILING_Y;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean perimeter = isShellColumn(record, x, z);
                boolean insideRoom = record.roomContains(cursor.set(x, FactoryData.FLOOR_Y, z));
                for (int y = bottom; y <= top; y++) {
                    BlockPos pos = cursor.set(x, y, z);
                    boolean wanted = perimeter || y == bottom || y == top;
                    BlockState state = level.getBlockState(pos);
                    if (wanted) {
                        if (!state.is(barrier)) {
                            level.setBlock(pos, barrier.defaultBlockState(), 3);
                            linkBarrier(level, pos, record.id());
                        }
                    } else if (y == FactoryData.FLOOR_Y && insideRoom) {
                        // Growing a room takes the wall that used to stand between two cells away again,
                        // and that wall stood on the floor layer: lay the checkerboard back down, or the
                        // seam between the two cells shows as a one block wide hole in the floor.
                        if (state.is(barrier) || state.isAir()) {
                            level.setBlock(pos, floorState(x, z), 3);
                        }
                    } else if (state.is(barrier) && nearCellEdge(record, x, z)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /**
     * True when a column is on the outline of the union of the cells, which is where the room's wall
     * stands: it is part of the room, and at least one of its four neighbours is not.
     */
    private static boolean isShellColumn(FactoryData.FactoryRecord record, int x, int z) {
        if (!record.roomContains(x, z)) {
            return false;
        }
        return !record.roomContains(x - 1, z)
                || !record.roomContains(x + 1, z)
                || !record.roomContains(x, z - 1)
                || !record.roomContains(x, z + 1);
    }

    /**
     * True when the column sits on or just outside a cell's edge, which is where a shell wall can be.
     * Clearing is limited to those columns so a rebuild does not delete barrier blocks a player placed
     * deeper inside the room.
     */
    private static boolean nearCellEdge(FactoryData.FactoryRecord record, int x, int z) {
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (x <= cell.roomX() || x >= cell.roomX() + FactoryData.CELL_SIZE - 1
                    || z <= cell.roomZ() || z >= cell.roomZ() + FactoryData.CELL_SIZE - 1) {
                return true;
            }
        }
        return false;
    }
    private static void sweepBarriers(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        Block barrier = ModBlocks.FACTORY_BARRIER.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = FactoryData.BASE_Y; y <= FactoryData.CEILING_Y; y++) {
                    BlockPos pos = cursor.set(x, y, z);
                    if (level.getBlockState(pos).is(barrier)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    /** Ties a freshly placed barrier block to its factory so the relay can find the entrance block. */
    private static void linkBarrier(ServerLevel level, BlockPos pos, int factoryId) {
        if (level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity barrier) {
            barrier.setFactoryId(factoryId);
        }
    }

    private static void generateFloor(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        BlockPos marker = new BlockPos(cell.roomX() + FactoryData.CELL_SIZE / 2, FactoryData.FLOOR_Y,
                cell.roomZ() + FactoryData.CELL_SIZE / 2);
        BlockState markerState = level.getBlockState(marker);
        if (markerState.is(Blocks.SNOW_BLOCK) || markerState.is(Blocks.WHITE_CONCRETE)) {
            return;
        }

        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                level.setBlock(new BlockPos(x, FactoryData.FLOOR_Y, z), floorState(x, z), 3);
            }
        }
    }

    /**
     * The checkerboard the floor is made of, on a fixed parity of the absolute coordinates so the rows of
     * one cell carry on into the next one instead of meeting in two matching colours.
     */
    private static BlockState floorState(int x, int z) {
        return ((x ^ z) & 1) == 0
                ? Blocks.WHITE_CONCRETE.defaultBlockState()
                : Blocks.SNOW_BLOCK.defaultBlockState();
    }

    /**
     * Bedrock under the platform. The barrier base already seals the room, so this is belt and braces:
     * it keeps the platform from being a single floating layer over the void even if the base is mined
     * out.
     */
    private static void sealFloor(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        int y = FactoryData.BASE_Y - 1;
        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).is(Blocks.BEDROCK)) {
                    level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
                }
            }
        }
    }
}
