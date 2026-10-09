package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.data.FactoryColors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    /**
     * The tallest a room's shell has ever been. A room used to be thirty two blocks tall, so the walls and
     * ceiling of an older room stand above the ceiling of a new one; the leftover is taken down at server
     * start (see {@link #trimShellAbove}), once, rather than on every look at the room.
     */
    private static final int TALLEST_SHELL_EVER = 32;
    /** The factories whose shell is still joined, and the look at them that comes next. */
    private static final Map<Integer, Integer> PENDING_SPLITS = new HashMap<>();
    /**
     * How far around a room's chunk its ticket reaches. Two chunks is the level a player's own chunk has,
     * so a room that is held open for its entrance block ticks exactly as it would with a player in it.
     */
    private static final int ROOM_TICKET_DISTANCE = 2;
    /**
     * The ticket that holds a room's chunks open. Tickets like this one are not saved, so a room that is
     * left behind by a server that stops is not loaded again until something asks for it - which is the
     * whole point: a factory is kept running by the block leading into it, not by having been made once.
     */
    private static final TicketType<ChunkPos> ROOM_TICKET =
            TicketType.create("recursivefactory_room", Comparator.comparingLong(ChunkPos::toLong));
    /** Temporary entrance tickets derived from players inside rooms, never saved as forced chunks. */
    private static final TicketType<ChunkPos> ENTRANCE_TICKET =
            TicketType.create("recursivefactory_occupied_entrance", Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<ResourceKey<Level>, Set<Long>> ENTRANCE_TICKETS = new HashMap<>();
    /** Rooms something other than their entrance block is still working on, asked again every tick. */
    private static final Set<Integer> KEEP_LOADED = new HashSet<>();
    /** The room chunks this class is holding open, per factory, so they can be let go again. */
    private static final Map<Integer, Set<Long>> ROOM_TICKETS = new HashMap<>();

    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "recursive_factory")
    );

    /**
     * The single biome the factory dimension is generated with, from its flat level generator. Every room
     * stands in it, so it is also the biome a preview of a room has to be coloured with: grass, leaves and
     * water all take their tint from the biome under them.
     */
    public static final ResourceKey<Biome> BIOME_KEY = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "recursive_factory")
    );

    private FactoryDimension() {
    }

    /** The biome a preview of a room is drawn with, out of whichever registries the client holds. */
    public static Holder<Biome> previewBiome(RegistryAccess registries) {
        return biome(registries.registryOrThrow(Registries.BIOME), BIOME_KEY);
    }

    /**
     * Looks a biome up and falls back to plains where the registry does not carry it, so a client that
     * somehow never received the factory dimension's biome still draws the preview in one flat colour.
     */
    public static Holder<Biome> biome(Registry<Biome> biomes, ResourceKey<Biome> key) {
        return biomes.getHolder(key).<Holder<Biome>>map(holder -> holder)
                .orElseGet(() -> biomes.getHolderOrThrow(Biomes.PLAINS));
    }

    public static void initialize(MinecraftServer server) {
        // A process can run more than one server - a single player client leaves a world and enters the next
        // one without shutting down - and the rooms of the server that stopped are still written down here.
        // Those tickets belong to a chunk source that is gone, and a room the notes call "already held" would
        // never be held on the new one (see holdRoomsOpen): the notes are dropped so that the first tick of
        // the new server works every room out again from the block leading into it.
        ROOM_TICKETS.clear();
        ENTRANCE_TICKETS.clear();
        PENDING_SPLITS.clear();
        KEEP_LOADED.clear();
        ServerLevel level = server.getLevel(LEVEL_KEY);
        if (level == null) {
            return;
        }
        for (FactoryData.FactoryRecord record : FactoryData.get(server).factories()) {
            prepare(level, record);
            trimShellAbove(level, record);
        }
    }

    /**
     * Takes down the part of a room's shell that stands above its ceiling. A room that comes back from a
     * save written before rooms were sixteen blocks tall still has the walls and the ceiling of its old
     * height standing up there, and those leftovers would hang over the new room as a floating copy of
     * it. Only barrier blocks that answer for this same factory are taken down, so nothing a player built
     * over a room is touched.
     *
     * <p>Run once per server start rather than on every look at a room: a room built at the current
     * height has nothing up there, and this walks the whole footprint.
     */
    private static void trimShellAbove(ServerLevel level, FactoryData.FactoryRecord record) {
        if (record.cells().isEmpty()) {
            return;
        }
        Block barrier = ModBlocks.FACTORY_BARRIER.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos column : roomColumns(record)) {
            int x = column.getX();
            int z = column.getZ();
            int ceiling = record.columnCeilingY(x, z);
            if (ceiling == Integer.MIN_VALUE) {
                continue;
            }
            int firstY = ceiling + 1;
            int lastY = firstY + (TALLEST_SHELL_EVER - FactoryData.ROOM_HEIGHT);
            for (int y = firstY; y <= lastY; y++) {
                BlockPos pos = cursor.set(x, y, z);
                if (!level.getBlockState(pos).is(barrier)) {
                    continue;
                }
                if (level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity leftover
                        && leftover.hasFactoryId()
                        && leftover.getFactoryId() == record.id()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
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
        holdPlayerEntrances(server);
        holdRoomsOpen(server);
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

    /**
     * Asks for a factory's room to stay loaded for the rest of this tick even though its entrance block is
     * not loaded - or does not exist yet. A room is normally kept running by the block leading into it (see
     * {@link #holdRoomsOpen}), which is what a player standing at that block has loaded; a machine that is
     * building a room of its own, like the blueprint cannon, has no entrance block to lean on and asks
     * here instead.
     *
     * <p>Nothing is asked for a room that is not a factory at all, and the request has to be repeated every
     * tick: it is let go of as soon as whoever asked stops asking.
     */
    public static void keepLoaded(int factoryId) {
        if (factoryId > 0) {
            KEEP_LOADED.add(factoryId);
        }
    }

    /** Keeps occupied factories connected to every enclosing factory, up to their outside entrances. */
    private static void holdPlayerEntrances(MinecraftServer server) {
        FactoryData data = FactoryData.get(server);
        ServerLevel roomLevel = server.getLevel(LEVEL_KEY);
        var pending = new ArrayDeque<FactoryData.FactoryRecord>();
        if (roomLevel != null) {
            for (var player : roomLevel.players()) {
                var record = data.factoryAt(player.blockPosition());
                if (record != null) pending.add(record);
            }
        }

        Set<Integer> visited = new HashSet<>();
        Map<ResourceKey<Level>, Set<Long>> wanted = new HashMap<>();
        while (!pending.isEmpty()) {
            var record = pending.removeFirst();
            if (!visited.add(record.id())) continue;
            // Keep rooms running while their entrance tickets are still bringing chunks online.
            keepLoaded(record.id());
            if (record.entranceDimension() == null) continue;
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, record.entranceDimension());
            if (server.getLevel(dimension) == null) continue;
            for (var cell : record.cells()) {
                if (cell.entrance().equals(FactoryData.UNBOUND_ENTRANCE)) continue;
                BlockPos entrance = BlockPos.containing(SablePhysicsBodies.worldPosition(server.getLevel(dimension),
                        Vec3.atCenterOf(cell.entrance())));
                wanted.computeIfAbsent(dimension, key -> new HashSet<>()).add(new ChunkPos(entrance).toLong());
                if (dimension.equals(LEVEL_KEY)) {
                    var parent = data.factoryAt(entrance);
                    if (parent != null) pending.add(parent);
                }
            }
        }

        // Deriving a union for all players prevents one player leaving from releasing another's chain.
        for (var entry : ENTRANCE_TICKETS.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) continue;
            Set<Long> needed = wanted.getOrDefault(entry.getKey(), Set.of());
            for (long chunk : entry.getValue()) {
                if (!needed.contains(chunk)) {
                    ChunkPos pos = new ChunkPos(chunk);
                    level.getChunkSource().removeRegionTicket(ENTRANCE_TICKET, pos, ROOM_TICKET_DISTANCE, pos);
                }
            }
        }
        for (var entry : wanted.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) continue;
            Set<Long> held = ENTRANCE_TICKETS.getOrDefault(entry.getKey(), Set.of());
            for (long chunk : entry.getValue()) {
                if (!held.contains(chunk)) {
                    ChunkPos pos = new ChunkPos(chunk);
                    level.getChunkSource().addRegionTicket(ENTRANCE_TICKET, pos, ROOM_TICKET_DISTANCE, pos);
                }
            }
            // The outer dimension may have no players of its own; its entrance machinery must still tick.
            level.resetEmptyTime();
        }
        ENTRANCE_TICKETS.clear();
        ENTRANCE_TICKETS.putAll(wanted);
    }

    /**
     * Holds every factory's room open for as long as the factory is being used, and lets it go when it is
     * not. A room is loaded while the block that leads into it is loaded, so a factory nobody is near is
     * dormant rather than turning in the background: the machinery inside it stops, and the server is not
     * made to keep a room per factory running for ever. A room whose shell has not come apart yet, and one
     * a machine is still building, are held open as well, and while any room is held the level is told it
     * is not empty, so that its machinery is not left unticked (see below).
     */
    private static void holdRoomsOpen(MinecraftServer server) {
        ServerLevel roomLevel = server.getLevel(LEVEL_KEY);
        // Where the physics bodies stand, worked out once for the tick: a body keeps the room it is
        // standing in loaded (see wantsRoomLoaded), and asking Sable once for every room would walk the
        // same list of bodies over and over.
        List<AABB> bodies = roomLevel == null ? List.of() : SablePhysicsBodies.bodiesIn(roomLevel);
        Set<Integer> alive = new HashSet<>();
        boolean holding = false;
        for (FactoryData.FactoryRecord record : FactoryData.get(server).factories()) {
            alive.add(record.id());
            Set<Long> held = ROOM_TICKETS.computeIfAbsent(record.id(), id -> new HashSet<>());
            Set<Long> wanted = roomLevel != null && wantsRoomLoaded(server, record, bodies)
                    ? roomChunks(record)
                    : Set.of();
            holding |= !wanted.isEmpty();
            if (held.equals(wanted)) {
                continue;
            }
            for (long chunk : held) {
                if (!wanted.contains(chunk)) {
                    release(roomLevel, chunk);
                }
            }
            for (long chunk : wanted) {
                if (!held.contains(chunk)) {
                    hold(roomLevel, chunk);
                }
            }
            held.clear();
            held.addAll(wanted);
        }
        // A factory that is gone - broken, or a print that was thrown away - lets its room go with it.
        ROOM_TICKETS.entrySet().removeIf(entry -> {
            if (alive.contains(entry.getKey())) {
                return false;
            }
            for (long chunk : entry.getValue()) {
                release(roomLevel, chunk);
            }
            return true;
        });
        if (holding) {
            // A level whose only company is the rooms this class holds open is treated as empty after a
            // while, and an empty level stops ticking entities and block entities altogether (vanilla
            // ServerLevel#tick) - which would stop every machine in every room, since the factory
            // dimension is exactly such a level: nobody stands in it, and nobody puts a chunk of it on
            // /forceload. Saying it is not empty is what the block leading into a room used to say by
            // being force loaded; the room is still only held for as long as that block is loaded.
            roomLevel.resetEmptyTime();
        }
        KEEP_LOADED.clear();
    }

    /** Whether a factory's room is wanted right now, which is what the block leading into it decides. */
    private static boolean wantsRoomLoaded(MinecraftServer server, FactoryData.FactoryRecord record,
                                           List<AABB> bodies) {
        if (KEEP_LOADED.contains(record.id()) || PENDING_SPLITS.containsKey(record.id())) {
            return true;
        }
        ResourceLocation dimension = record.entranceDimension();
        if (dimension != null) {
            ServerLevel entranceLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            // Only a chunk the entrance block is still ticked in counts. A chunk merely left in memory - a FULL
            // chunk kept by vanilla's one-tick unknown ticket, which the room's own machinery asks for again
            // every tick while it looks at the entrance - must not hold the room open by itself, or a factory
            // that was used once would never go dormant again.
            if (entranceLevel != null) {
                for (var cell : record.cells()) {
                    if (cell.entrance().equals(FactoryData.UNBOUND_ENTRANCE)) continue;
                    BlockPos worldPos = BlockPos.containing(SablePhysicsBodies.worldPosition(entranceLevel,
                            Vec3.atCenterOf(cell.entrance())));
                    if (entranceLevel.shouldTickBlocksAt(worldPos)) return true;
                }
            }
        }
        // A room a physics body is standing in is held open too, even with nobody at its entrance. Sable
        // saves a sub-level away - and stops ticking it - the moment the chunks its world box stands over
        // stop ticking (see SablePhysicsBodies), so a room that let go while a body still stood in it would
        // take the body with it: the player still inside the body would be left in a level that no longer
        // has the body, and could not step back out of it.
        return physicsBodyStandsIn(bodies, record);
    }

    /** Whether a physics body's world box overlaps any cell of this factory's room. */
    private static boolean physicsBodyStandsIn(List<AABB> bodies, FactoryData.FactoryRecord record) {
        if (bodies.isEmpty() || record.cells().isEmpty()) {
            return false;
        }
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            AABB room = new AABB(
                    cell.roomX(), cell.baseY(), cell.roomZ(),
                    cell.roomX() + FactoryData.CELL_SIZE, cell.ceilingY() + 1, cell.roomZ() + FactoryData.CELL_SIZE
            );
            for (AABB body : bodies) {
                if (body.intersects(room)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The chunks of a factory's room, one per cell: a cell is exactly one chunk across. */
    private static Set<Long> roomChunks(FactoryData.FactoryRecord record) {
        Set<Long> chunks = new HashSet<>();
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            chunks.add(ChunkPos.asLong(cell.roomX() >> 4, cell.roomZ() >> 4));
        }
        return chunks;
    }

    /** Iterate actual room columns, not the potentially enormous empty rectangle between cells. */
    private static Iterable<BlockPos> roomColumns(FactoryData.FactoryRecord record) {
        var chunks = record.cells().stream()
                .map(cell -> new ChunkPos(cell.roomX() >> 4, cell.roomZ() >> 4)).distinct().toList();
        return () -> chunks.stream().flatMap(chunk -> BlockPos.betweenClosedStream(
                chunk.getMinBlockX(), 0, chunk.getMinBlockZ(), chunk.getMaxBlockX(), 0, chunk.getMaxBlockZ())
                .map(BlockPos::immutable)).iterator();
    }

    private static void hold(@Nullable ServerLevel roomLevel, long chunk) {
        if (roomLevel != null) {
            ChunkPos pos = new ChunkPos(chunk);
            roomLevel.getChunkSource().addRegionTicket(ROOM_TICKET, pos, ROOM_TICKET_DISTANCE, pos);
        }
    }

    private static void release(@Nullable ServerLevel roomLevel, long chunk) {
        if (roomLevel != null) {
            ChunkPos pos = new ChunkPos(chunk);
            roomLevel.getChunkSource().removeRegionTicket(ROOM_TICKET, pos, ROOM_TICKET_DISTANCE, pos);
        }
    }

    /** Builds (or rebuilds) everything a factory's room is made of. */
    public static void prepare(ServerLevel level, FactoryData.FactoryRecord record) {
        if (record.cells().isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        FactoryData data = server == null ? null : FactoryData.get(server);
        boolean grewFloor = false;
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (record.hasCellBelow(cell)) {
                // A cell that was stacked on keeps neither a floor nor a seal of its own: the layer under
                // it is now inside the room, and the only floor is the one at the bottom of the column.
                clearFloorLayer(level, cell);
                clearSealLayer(level, cell);
                if (data != null) {
                    data.markFloorLaid(record.id(), cell.roomX(), cell.roomY(), cell.roomZ(), false);
                }
                continue;
            }
            if (generateFloor(level, cell)) {
                // The floor went down for the first time: remember it, so it is not laid again.
                grewFloor = true;
                LOGGER.debug("Factory #{}: laid the floor of the room cell at {} for the first time",
                        record.id(), cell.entrance().toShortString());
                if (data != null) {
                    data.markFloorLaid(record.id(), cell.roomX(), cell.roomY(), cell.roomZ(), true);
                }
            }
            sealFloor(level, cell);
        }
        buildShell(level, record, grewFloor);
        splitShellSides(level, record);
    }

    /**
     * Pulls a room's shell apart into one network per cell side, and keeps looking from the server tick
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
     * <p>Only this cell's own exposed edge answers. An edge opened by expansion has no endpoint;
     * it must never fall through to another cell's outer wall.
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
            int y = direction == Direction.UP ? cell.ceilingY() : cell.baseY();
            List<BlockPos> row = new ArrayList<>();
            for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
                if (!record.roomContains(x, y, center.getZ())) {
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
                    ? new BlockPos(cell.roomX() + start, cell.floorY() + 1, cell.roomZ() + offset)
                    : new BlockPos(cell.roomX() + offset, cell.floorY() + 1, cell.roomZ() + start);
            if (shellSide(record, cursor) == direction && inward(record, cursor, direction) != null) {
                wall.add(cursor);
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
            if (!record.roomContains(cursor)) {
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
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return record.roomContains(x, y, z)
                && !isShellPosition(record, x, y, z)
                && record.roomContains(x, y - 1, z)
                && record.roomContains(x, y + 1, z);
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
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (!record.roomContains(x, y, z)) {
            return null;
        }
        if (!record.roomContains(x, y - 1, z)) {
            return Direction.DOWN;
        }
        if (!record.roomContains(x, y + 1, z)) {
            return Direction.UP;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!record.roomContains(x + side.getStepX(), y, z + side.getStepZ())) {
                return side;
            }
        }
        return null;
    }

    /** Where a player arriving through this entrance cell lands: the middle of its room cell. */
    public static BlockPos entryTarget(FactoryData.FactoryRecord.Cell cell) {
        return cell.center();
    }

    /**
     * A brand new room of its own, with its floor and its shell built and no entrance block leading into it
     * yet - {@link #linkEntrance} is what puts one down and joins the two up. This is how a copy of a
     * factory gets a room for every room the original had, including the ones nested inside it.
     */
    public static int newRoom(ServerLevel level, FactoryData data, @Nullable UUID owner, int colorIndex) {
        return newRoom(level, data, owner, colorIndex, List.of(BlockPos.ZERO));
    }

    public static int newRoom(ServerLevel level, FactoryData data, @Nullable UUID owner, int colorIndex,
                              List<BlockPos> cells) {
        FactoryData.FactoryRecord record = data.create(owner, colorIndex, cells,
                FactoryRoomLayout.baseRoomY(level, cells));
        FactoryData.FactoryRecord room = data.factory(record.id());
        if (room != null) {
            prepare(level, room);
        }
        LOGGER.info("Factory #{}: a room was built for it", record.id());
        return record.id();
    }

    /**
     * Makes a room as wide as the room of a blueprint it is being built from: the cells the blueprint's room
     * stood on are put up beside the room's own cell, and the room is built again so the new cells get their
     * floor and their shell. A room that is already as wide - a print that is being carried on, or a copy of
     * a factory that has not grown - is left as it is, so asking twice builds nothing twice.
     */
    public static void sizeRoom(ServerLevel level, FactoryData data, int roomId, List<BlockPos> offsets) {
        FactoryData.FactoryRecord record = data.factory(roomId);
        if (record == null) {
            return;
        }
        FactoryData.FactoryRecord.Cell anchor = record.anchorCell();
        if (anchor == null) {
            return;
        }
        FactoryRoomLayout.validate(offsets);
        for (BlockPos offset : offsets) {
            int x = anchor.roomX() + offset.getX(), y = anchor.roomY() + offset.getY(), z = anchor.roomZ() + offset.getZ();
            if (!FactoryRoomLayout.fits(level, x, y, z) || !data.canOccupy(roomId, x, z)) {
                throw FactoryRoomLayout.invalid();
            }
        }
        Set<Long> laid = new HashSet<>();
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            laid.add(cellKey(cell.roomX() - anchor.roomX(), cell.roomY() - anchor.roomY(),
                    cell.roomZ() - anchor.roomZ()));
        }
        boolean grew = false;
        for (BlockPos offset : offsets) {
            if (!laid.add(cellKey(offset.getX(), offset.getY(), offset.getZ()))) {
                continue;
            }
            data.addRoomCell(roomId, anchor.roomX() + offset.getX(), anchor.roomY() + offset.getY(),
                    anchor.roomZ() + offset.getZ());
            grew = true;
        }
        FactoryData.FactoryRecord sized = data.factory(roomId);
        if (grew && sized != null) {
            prepare(level, sized);
        }
    }

    /**
     * Points the entrance block standing at {@code pos} at the room {@code roomId}, which is already
     * standing: the block is told which factory it leads into and is drawn in that factory's colour, the
     * room takes the block as its own cell - moving the stand-in entrance it was built with to where the
     * block actually stands - and the block is asked again which of its sides join up with the blocks
     * beside it, which for a block that has just arrived is a question nobody could answer yet.
     */
    public static void linkEntrance(Level level, BlockPos pos, int roomId, int colorIndex) {
        MinecraftServer server = level.getServer();
        if (server == null || roomId <= 0) {
            return;
        }
        // A room is bound to a block, not to a place: asked for a spot with no entrance block standing on
        // it - a print whose block could not go down - the room is left unbound rather than tied to a place,
        // which would leave it standing with nobody able to walk in and held open by a chunk instead of by
        // a block.
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)) {
            LOGGER.warn("Factory #{}: no entrance block stands at {}; the room is left unbound",
                    roomId, pos.toShortString());
            return;
        }
        FactoryData data = FactoryData.get(server);
        data.bindRoomEntrance(roomId, level.dimension().location(), pos);
        entrance.setFactoryId(roomId);
        entrance.setColorIndex(colorIndex);
        RecursiveFactoryBlock.refreshConnections(level, pos);
        FactoryRelay.updateFromNeighbours(entrance);
    }

    /** Links a printed entrance to its own cell; the allocation origin remains stable across reloads. */
    public static boolean linkEntranceCell(Level level, BlockPos pos, int roomId, BlockPos offset) {
        if (level.getServer() == null || !(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)) return false;
        FactoryData data = FactoryData.get(level.getServer());
        FactoryData.FactoryRecord room = data.factory(roomId);
        FactoryData.FactoryRecord.Cell anchor = room == null ? null : room.anchorCell();
        if (room == null || anchor == null || !data.bindRoomEntranceCell(roomId, level.dimension().location(), pos,
                anchor.roomX() + offset.getX(), anchor.roomY() + offset.getY(),
                anchor.roomZ() + offset.getZ())) return false;
        entrance.clearDoor();
        entrance.setFactoryId(roomId);
        entrance.setColorIndex(room.colorIndex());
        RecursiveFactoryBlock.refreshConnections(level, pos);
        FactoryRelay.updateFromNeighbours(entrance);
        return true;
    }

    /**
     * The room an entrance block standing at {@code pos} already leads into, or {@code -1} when it leads
     * into none - a block that was never wired up, or one whose room has since gone.
     */
    public static int entranceRoomOf(Level level, FactoryData data, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)
                || !entrance.hasFactoryId()) {
            return -1;
        }
        FactoryData.FactoryRecord record = data.factory(entrance.getFactoryId());
        return record != null && record.cellAt(pos) != null ? record.id() : -1;
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
        sweepBarriers(level, cell);
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
     *
     * @param grewFloor true when a cell's floor was laid by this same look at the room, which puts the
     *     room in the middle of growing.
     */
    private static void buildShell(ServerLevel level, FactoryData.FactoryRecord record, boolean grewFloor) {
        Block barrier = ModBlocks.FACTORY_BARRIER.get();
        // The shell is drawn in the factory's colour, and that colour rides on the block's own state, so the
        // state it is laid with is already the finished one: a wall is never shown in the plain colour first
        // and repainted a frame later. A wall from a save older than that carries the property's default,
        // which is why this compares states rather than just asking whether the block is a barrier - the
        // first look at an older room repaints its walls, once.
        BlockState shellState = barrier.defaultBlockState().setValue(
                FactoryColors.COLOR_PROPERTY,
                FactoryColors.stateValue(FactoryColors.kindOfFactory(record.colorIndex(), record.id()))
        );
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos column : roomColumns(record)) {
            int x = column.getX();
            int z = column.getZ();
            int columnBase = record.columnBaseY(x, z);
            int columnCeiling = record.columnCeilingY(x, z);
            int floorY = columnBase + (FactoryData.FLOOR_Y - FactoryData.BASE_Y);
            for (int y = columnBase; y <= columnCeiling; y++) {
                if (!record.roomContains(x, y, z)) {
                    // A gap between two cells of the same column, left by a cell that was broken in
                    // between: nothing of the room stands here.
                    continue;
                }
                BlockPos pos = cursor.set(x, y, z);
                boolean wanted = isShellPosition(record, x, y, z)
                        || !record.roomContains(x, y - 1, z)
                        || !record.roomContains(x, y + 1, z);
                BlockState state = level.getBlockState(pos);
                if (wanted) {
                    if (state != shellState) {
                        level.setBlock(pos, shellState, 3);
                        linkBarrier(level, pos, record);
                    }
                } else if (y == floorY) {
                    // Growing a room takes the wall that used to stand between two cells away again,
                    // and that wall stood on the floor layer: lay the checkerboard back down along the
                    // join, or the seam between the two cells shows as a one block wide hole in the
                    // floor. A room that is growing gets its seam back even where the player had
                    // already opened the wall up themselves; a hole the player dug anywhere else in
                    // the floor only comes back when the config asks for that.
                    boolean wallHere = state.is(barrier);
                    boolean seamHole = state.isAir() && grewFloor && onCellRim(record, x, z);
                    boolean brokenHole = state.isAir() && FactoryConfig.repairBrokenFloor();
                    if (wallHere || seamHole || brokenHole) {
                        level.setBlock(pos, floorState(x, z), 3);
                    }
                } else if (state.is(barrier)
                        && (ownedBy(level, pos, record.id()) || nearCellEdge(record, x, y, z))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                } else if (state.is(Blocks.BEDROCK)) {
                    // The seal of a cell that has since been stacked on lies inside the room now.
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                } else if (isStaleFloor(record, x, y, z, state)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Whether a barrier block belongs to the factory it is being rebuilt for. */
    private static boolean ownedBy(ServerLevel level, BlockPos pos, int factoryId) {
        return level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity barrier
                && barrier.hasFactoryId()
                && barrier.getFactoryId() == factoryId;
    }

    /**
     * True when a room position is the checkerboard floor of a cell that has since been stacked on: that
     * floor lies inside the room now and is taken away, so only the floor at the bottom of a column stays.
     */
    private static boolean isStaleFloor(FactoryData.FactoryRecord record, int x, int y, int z, BlockState state) {
        if (!state.equals(floorState(x, z))) {
            return false;
        }
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (cell.floorY() == y && record.hasCellBelow(cell) && cell.contains(x, y, z)) {
                return true;
            }
        }
        return false;
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
     * The shell blocks and networks holding more than one cell face, including same-facing sections
     * belonging to different cells in an expanded room.
     */
    private static Set<Long> shellJoins(ServerLevel level, FactoryData.FactoryRecord record,
                                        List<EndpointBlockEntity> shell) {
        if (record.cells().isEmpty()) return Set.of();
        int minY = record.cells().stream().mapToInt(FactoryData.FactoryRecord.Cell::baseY).min().orElseThrow();
        int maxY = record.cells().stream().mapToInt(FactoryData.FactoryRecord.Cell::ceilingY).max().orElseThrow();

        Map<Long, ShellChannel> firstSide = new HashMap<>();
        Set<Long> joinedIds = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos column : roomColumns(record)) {
            int x = column.getX();
            int z = column.getZ();
            for (int y = minY; y <= maxY; y++) {
                BlockPos pos = cursor.set(x, y, z);
                Direction side = shellSide(record, pos);
                if (side == null) {
                    continue;
                }
                if (!(level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint)
                        || !endpoint.hasFactoryId()
                        || endpoint.getFactoryId() != record.id()) {
                    continue;
                }
                shell.add(endpoint);
                FactoryData.FactoryRecord.Cell cell = record.cellContaining(pos);
                if (endpoint.hasNetwork() && cell != null) {
                    ShellChannel channel = new ShellChannel(cell.roomX(), cell.roomY(), cell.roomZ(), side);
                    ShellChannel first = firstSide.putIfAbsent(endpoint.network, channel);
                    if (first != null && !first.equals(channel)) {
                        joinedIds.add(endpoint.network);
                    }
                }
            }
        }
        return joinedIds;
    }

    private record ShellChannel(int roomX, int roomY, int roomZ, Direction side) {}

    /** Packs a cell's room offset into one number, for the set of cells a room already stands on. */
    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0x7FF) << 31) | (z & 0x3FFFFF);
    }

    private static boolean isShellPosition(FactoryData.FactoryRecord record, int x, int y, int z) {
        if (!record.roomContains(x, y, z)) {
            return false;
        }
        return !record.roomContains(x - 1, y, z)
                || !record.roomContains(x + 1, y, z)
                || !record.roomContains(x, y, z - 1)
                || !record.roomContains(x, y, z + 1);
    }

    /**
     * True when the column sits on or just outside a cell's edge, which is where a shell wall can be.
     * Clearing is limited to those columns so a rebuild does not delete barrier blocks a player placed
     * deeper inside the room.
     */
    private static boolean nearCellEdge(FactoryData.FactoryRecord record, int x, int y, int z) {
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (x < cell.roomX() - 1 || x > cell.roomX() + FactoryData.CELL_SIZE
                    || z < cell.roomZ() - 1 || z > cell.roomZ() + FactoryData.CELL_SIZE
                    || y < cell.baseY() - 1 || y > cell.ceilingY() + 1) {
                continue;
            }
            if (x <= cell.roomX() || x >= cell.roomX() + FactoryData.CELL_SIZE - 1
                    || z <= cell.roomZ() || z >= cell.roomZ() + FactoryData.CELL_SIZE - 1
                    || y <= cell.baseY() || y >= cell.ceilingY()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the column sits on the rim of a cell that covers it: the ring of columns a cell shares
     * with its neighbours once a room has grown, which is where the wall between two cells used to
     * stand. {@link #nearCellEdge(FactoryData.FactoryRecord, int, int)} is the looser question of
     * whether a column is anywhere near a cell's edge, which is what clearing barriers wants.
     */
    private static boolean onCellRim(FactoryData.FactoryRecord record, int x, int z) {
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (x < cell.roomX() || x >= cell.roomX() + FactoryData.CELL_SIZE
                    || z < cell.roomZ() || z >= cell.roomZ() + FactoryData.CELL_SIZE) {
                continue;
            }
            if (x == cell.roomX() || x == cell.roomX() + FactoryData.CELL_SIZE - 1
                    || z == cell.roomZ() || z == cell.roomZ() + FactoryData.CELL_SIZE - 1) {
                return true;
            }
        }
        return false;
    }

    private static void sweepBarriers(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        Block barrier = ModBlocks.FACTORY_BARRIER.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                for (int y = cell.baseY(); y <= cell.ceilingY(); y++) {
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

    /**
     * Lays the checkerboard floor of one cell. The answer says whether this call laid it: a cell that
     * had no floor answers yes so the caller can remember the floor is down, a cell that already had one
     * answers no.
     *
     * <p>A cell's floor is laid the first time the cell is built. A cell whose floor is already down is
     * left alone - a hole a player digs in it stays a hole - unless {@code room.repairBrokenFloor} is
     * on, in which case the floor blocks that are missing are put back. A repair only fills what is gone:
     * whatever else a player has put on the floor layer stays where it is.
     */
    /**
     * Takes the checkerboard floor of a cell away again. A cell that was stacked on keeps no floor of its
     * own: the layer it stood on is inside the room now, and the room's floor is the one at the bottom of
     * the column. Only blocks that are exactly the checkerboard this cell was laid with are taken, so
     * anything the player has since put on that layer stays.
     */
    private static void clearFloorLayer(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                BlockPos pos = new BlockPos(x, cell.floorY(), z);
                if (level.getBlockState(pos).equals(floorState(x, z))) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * Takes the bedrock seal under a cell away again, for the same reason: a cell that was stacked on has
     * that layer inside the room now. Bedrock is never anything but this seal in the factory dimension.
     */
    private static void clearSealLayer(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        int y = cell.baseY() - 1;
        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static boolean generateFloor(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        boolean alreadyLaid = cell.floorLaid();
        if (alreadyLaid && !FactoryConfig.repairBrokenFloor()) {
            return false;
        }
        for (int x = cell.roomX(); x < cell.roomX() + FactoryData.CELL_SIZE; x++) {
            for (int z = cell.roomZ(); z < cell.roomZ() + FactoryData.CELL_SIZE; z++) {
                BlockPos pos = new BlockPos(x, cell.floorY(), z);
                if (alreadyLaid && !level.getBlockState(pos).isAir()) {
                    continue;
                }
                level.setBlock(pos, floorState(x, z), 3);
            }
        }
        return !alreadyLaid;
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
        int y = cell.baseY() - 1;
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
