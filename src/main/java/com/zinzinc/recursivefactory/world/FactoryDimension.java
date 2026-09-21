package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * When a room's shell is looked at again while it is still joined, counted from the server start: a
     * saved room is a few ticks away from having its block entities on Create's networks.
     */
    private static final int[] SPLIT_RETRIES = {2, 6, 20, 60, 200, 600, 1800};
    /** The factories whose shell is still joined, and the look at them that comes next. */
    private static final Map<Integer, Integer> PENDING_SPLITS = new HashMap<>();

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

    /**
     * Looks at the factories whose shell was still holding its sides together the last time it was looked
     * at. Reading a room's blocks back from a save hands them over before Create has put them on its
     * networks, so the first look at a saved room finds nothing to pull apart: it is taken again a few
     * ticks later, a handful of times, until the shell answers for the block entities that are really
     * there.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING_SPLITS.isEmpty()) {
            return;
        }
        ServerLevel level = server.getLevel(LEVEL_KEY);
        if (level == null) {
            PENDING_SPLITS.clear();
            return;
        }
        int tick = server.getTickCount();
        for (Map.Entry<Integer, Integer> pending : List.copyOf(PENDING_SPLITS.entrySet())) {
            int factoryId = pending.getKey();
            int look = pending.getValue();
            if (look >= SPLIT_RETRIES.length) {
                PENDING_SPLITS.remove(factoryId);
                LOGGER.debug("Factory #{}: the shell still answers for more than one side after {} looks"
                                + " at it; those sides are held together by machinery rather than by the shell",
                        factoryId, look);
                continue;
            }
            if (tick < SPLIT_RETRIES[look]) {
                continue;
            }
            FactoryData.FactoryRecord record = null;
            for (FactoryData.FactoryRecord candidate : FactoryData.get(server).factories()) {
                if (candidate.id() == factoryId) {
                    record = candidate;
                    break;
                }
            }
            if (record == null) {
                PENDING_SPLITS.remove(factoryId);
                continue;
            }
            PENDING_SPLITS.put(factoryId, look + 1);
            if (separateShellSides(level, record)) {
                PENDING_SPLITS.remove(factoryId);
                LOGGER.debug("Factory #{}: the shell came apart on look {}", factoryId, look + 1);
            }
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
        splitShellSides(level, record);
    }

    /**
     * Pulls a room's shell apart into one network per side, and keeps looking at it from the server tick
     * for as long as it does not come apart.
     */
    private static void splitShellSides(ServerLevel level, FactoryData.FactoryRecord record) {
        if (separateShellSides(level, record)) {
            PENDING_SPLITS.remove(record.id());
            return;
        }
        PENDING_SPLITS.putIfAbsent(record.id(), 0);
    }

    /**
     * The room's barriers that a link coming in through one face of an entrance block ends at: the shell
     * columns of {@code cell} on the side the thing came in through, at the height a player stands at.
     *
     * <p>Every column of the cell's edge is walked outwards until it leaves the room, so what comes back
     * is the wall of the union rather than of the cell alone: a room that has grown past its anchor cell
     * answers on its outer wall, and an L shaped room on the walls of its step as well.
     *
     * <p>Up and down answer on the ceiling and on the base layer instead, one row across the middle of the
     * cell, which are barriers there as well.
     *
     * <p>Columns with no room behind them are left out. A corner of the room is wall on two sides, so the
     * block behind a corner block of one wall is the other wall rather than the room: a link answering
     * there has nowhere to put what comes in - it would land inside the wall, in the other wall's own
     * endpoint, and be relayed back out of the entrance block on the wrong side (see {@link #inward}).
     *
     * <p>The whole side is answered on rather than a single block, so that a hopper or a dust line built
     * anywhere along that wall picks the link up instead of the player having to find one exact spot.
     */
    public static List<BlockPos> wallLine(FactoryData.FactoryRecord record, FactoryData.FactoryRecord.Cell cell,
                                          Direction direction) {
        BlockPos center = cell.center();
        if (direction.getAxis().isVertical()) {
            int y = direction == Direction.UP ? FactoryData.CEILING_Y : FactoryData.BASE_Y;
            List<BlockPos> row = new ArrayList<>();
            for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
                if (!record.roomContains(x, center.getZ())) {
                    continue;
                }
                BlockPos column = new BlockPos(x, y, center.getZ());
                if (inward(record, column, direction) != null) {
                    row.add(column);
                }
            }
            return row;
        }

        boolean alongX = direction.getAxis() == Direction.Axis.X;
        int start = direction == Direction.EAST || direction == Direction.SOUTH
                ? FactoryData.CELL_SIZE - 1
                : 0;
        List<BlockPos> wall = new ArrayList<>();
        for (int offset = 0; offset < FactoryData.CELL_SIZE; offset++) {
            BlockPos cursor = alongX
                    ? new BlockPos(cell.roomX() + start, FactoryData.FLOOR_Y + 1, cell.roomZ() + offset)
                    : new BlockPos(cell.roomX() + offset, FactoryData.FLOOR_Y + 1, cell.roomZ() + start);
            BlockPos last = null;
            while (record.roomContains(cursor)) {
                last = cursor;
                cursor = cursor.relative(direction);
            }
            if (last == null) {
                continue;
            }
            BlockPos landed = new BlockPos(last.getX(), FactoryData.FLOOR_Y + 1, last.getZ());
            if (inward(record, landed, direction) != null && !wall.contains(landed)) {
                wall.add(landed);
            }
        }
        return wall;
    }

    /**
     * The first free spot of the room behind a wall block, which is where something coming in through that
     * block lands: the room itself, one step in. Two cases need the walk rather than the single step - the
     * base layer, where the floor is what lies behind the wall, and the corners, where the other wall is
     * and there is no spot at all ({@code null}), so a link has nothing to answer on there.
     */
    @Nullable
    public static BlockPos inward(FactoryData.FactoryRecord record, BlockPos wall, Direction direction) {
        BlockPos.MutableBlockPos cursor = wall.mutable();
        for (int step = 0; step < FactoryData.ROOM_HEIGHT; step++) {
            cursor.move(direction.getOpposite());
            if (isFreeSpace(record, cursor)) {
                return cursor.immutable();
            }
            if (!record.roomContains(cursor.getX(), cursor.getZ())
                    || cursor.getY() < FactoryData.FLOOR_Y || cursor.getY() > FactoryData.CEILING_Y) {
                return null;
            }
        }
        return null;
    }

    /**
     * True for a spot that is free space of the room: room rather than wall, above the floor and below the
     * ceiling, so that a link can hand something over there instead of into a wall - and so that a shaft
     * reaches the shell from there (see {@code KineticRelay#takesShaft}).
     */
    public static boolean isFreeSpace(FactoryData.FactoryRecord record, BlockPos pos) {
        return record.roomContains(pos)
                && !isShellColumn(record, pos.getX(), pos.getZ())
                && pos.getY() > FactoryData.FLOOR_Y
                && pos.getY() < FactoryData.CEILING_Y;
    }

    /**
     * The side of the room a shell block stands on: the wall it is part of, or the base layer and the
     * ceiling, which are sides of a room as well. {@code null} for a block that is not part of the shell,
     * which includes the entrance blocks outside, as they stand on no side of the room at all.
     *
     * <p>This is what splits the shell into the six kinetic machines a factory's link works with (see
     * {@code KineticRelay#takesShaft}): a shaft runs along one side of a room and never from one wall into
     * another. A corner column is wall on two sides at once and answers on the first of them, next to the
     * way the item link leaves corners out of both walls (see {@link #wallLine}).
     */
    @Nullable
    public static Direction shellSide(FactoryData.FactoryRecord record, BlockPos pos) {
        if (!record.roomContains(pos.getX(), pos.getZ())) {
            return null;
        }
        if (pos.getY() == FactoryData.BASE_Y) {
            return Direction.DOWN;
        }
        if (pos.getY() == FactoryData.CEILING_Y) {
            return Direction.UP;
        }
        if (pos.getY() < FactoryData.BASE_Y || pos.getY() > FactoryData.CEILING_Y) {
            return null;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!record.roomContains(pos.getX() + side.getStepX(), pos.getZ() + side.getStepZ())) {
                return side;
            }
        }
        return null;
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
                            linkBarrier(level, pos, record);
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
     * Pulls the sides of a room's shell apart where a save still has them as one kinetic machine.
     *
     * <p>Create writes the network a kinetic block turns with into the block itself, and a network is never
     * taken apart while it is there: a room whose shell was laid down before the sides were split - which
     * is every room built before that change - comes back from a save with its six sides joined, and
     * driving one of them turns the whole shell.
     *
     * <p>The sides are therefore pulled apart on the way in: everything that was turning with such a
     * network - the shell and the machinery built against it, which carries the same id - lets go of it,
     * and whatever turns itself then starts again from the wall it stands against. What comes back is
     * every side turning on its own, with the machinery built against that side and nothing else.
     *
     * <p>A side whose machinery runs on into the room and reaches another side stays joined to it: that is
     * the player's own linkage rather than the shell's.
     *
     * @return true when the shell answers for one network per side, false while it is still joined and has
     *     to be looked at again, see {@link #tick}
     */
    private static boolean separateShellSides(ServerLevel level, FactoryData.FactoryRecord record) {
        List<EndpointBlockEntity> shell = new ArrayList<>();
        Set<Long> joinedIds = shellJoins(level, record, shell);
        if (joinedIds.isEmpty()) {
            return true;
        }

        Set<KineticBlockEntity> joined = joinedMembers(shell, joinedIds);
        LOGGER.debug("Factory #{}: {} shell blocks, {} networks holding more than one side, {} blocks"
                        + " turning with them", record.id(), shell.size(), joinedIds.size(), joined.size());
        for (KineticBlockEntity member : joined) {
            if (member instanceof EndpointBlockEntity endpoint) {
                endpoint.forgetLink();
            } else {
                member.removeSource();
            }
        }
        // What turns itself moves onto a network of its own, taking the wall it is built against - and
        // only that wall - with it. A side with nothing of its own driving it is left stopped.
        for (KineticBlockEntity member : joined) {
            if (member instanceof GeneratingKineticBlockEntity generator && generator.isSource()) {
                generator.updateGeneratedRotation();
            }
        }

        // One more look, which is what says whether the room is apart: the blocks of a saved room are
        // handed over before Create has put them on its networks, so the first look at one finds nothing
        // to pull apart even though the shell is still joined. That look is taken again from the server
        // tick until the shell answers for the block entities that are really there.
        return shellJoins(level, record, new ArrayList<>()).isEmpty();
    }

    /**
     * Everything turning with one of the networks that hold more than one side of the shell, the
     * machinery built against those walls included: that machinery is on the same network as the shell,
     * so pulling the shell off on its own would only put the sides back together again.
     */
    private static Set<KineticBlockEntity> joinedMembers(List<EndpointBlockEntity> shell, Set<Long> joinedIds) {
        Set<KineticBlockEntity> joined = new LinkedHashSet<>();
        for (EndpointBlockEntity endpoint : shell) {
            if (endpoint.hasNetwork() && joinedIds.contains(endpoint.network)) {
                joined.addAll(endpoint.getOrCreateNetwork()
                        .members.keySet());
            }
        }
        return joined;
    }

    /**
     * The blocks of a room's shell, added to {@code shell}, and the networks that hold more than one side
     * of it. Every side of the shell is a face of the room, so a network answering on two faces is one
     * that still has those sides joined.
     */
    private static Set<Long> shellJoins(ServerLevel level, FactoryData.FactoryRecord record,
                                        List<EndpointBlockEntity> shell) {
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
        if (minX > maxX || minZ > maxZ) {
            return Set.of();
        }

        Map<Long, Direction> firstSide = new HashMap<>();
        Set<Long> joinedIds = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean wall = isShellColumn(record, x, z);
                for (int y = FactoryData.BASE_Y; y <= FactoryData.CEILING_Y; y++) {
                    if (!wall && y != FactoryData.BASE_Y && y != FactoryData.CEILING_Y) {
                        continue;
                    }
                    BlockPos pos = cursor.set(x, y, z);
                    if (!(level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint)
                            || !endpoint.hasFactoryId()
                            || endpoint.getFactoryId() != record.id()) {
                        continue;
                    }
                    Direction side = shellSide(record, pos);
                    if (side == null) {
                        continue;
                    }
                    shell.add(endpoint);
                    if (endpoint.hasNetwork()) {
                        Direction first = firstSide.putIfAbsent(endpoint.network, side);
                        if (first != null && first != side) {
                            joinedIds.add(endpoint.network);
                        }
                    }
                }
            }
        }
        return joinedIds;
    }

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

    /**
     * Ties a freshly placed barrier block to its factory so the relay can find the entrance block, and
     * paints it: the shell of a room and every entrance block that leads into it are one colour.
     */
    private static void linkBarrier(ServerLevel level, BlockPos pos, FactoryData.FactoryRecord record) {
        if (level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity barrier) {
            barrier.setFactoryId(record.id());
            barrier.setColorIndex(record.colorIndex());
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
