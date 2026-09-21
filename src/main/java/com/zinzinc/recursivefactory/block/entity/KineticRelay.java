package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.block.FactoryBarrierBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Turns a factory's machinery from outside, and the outer machines from inside the factory.
 *
 * <p>The two ends of a factory's link are in different dimensions - the entrance block stands in the world
 * the factory was built in, the shell of its room in a dimension of its own - and Create's kinetic networks
 * never leave the level they were built in. The link is therefore carried across by hand: the entrance
 * block looks at both ends every tick and, while one of them is being turned by a generator, turns the
 * other one at the same speed and hands on what is left of the driving end's stress capacity.
 *
 * <p>A link has six channels, one per side of the room: the face of the entrance block the outside
 * machinery is built against answers on the wall of that same side of the room, the way items and redstone
 * already answer on the wall the thing came in through. Build a machine against the factory's north face
 * and the machinery along the room's north wall turns; the other walls are left where they are. The shell
 * is therefore six kinetic machines rather than one (see {@link #takesShaft}).
 *
 * <p>The room answers the same way: while the room's north wall turns the outside, only the north face of
 * the entrance block turns the machinery built against it, and machinery built against its other five
 * faces stands still. An entrance block is one Create block with one speed, so two sides of the room can
 * only turn the outside together while they run at the same speed; sides at different speeds have no way
 * of sharing the block, and the link says so rather than picking one of them.
 *
 * <p>Stress travels the way a shaft would carry it: the end that is being driven becomes a generator for
 * its own side with the capacity that is spare on the far side, and the end that is doing the driving
 * carries the far side's stress on its own network. Both ends then weigh their load against the same
 * capacity, so an overloaded factory stalls on both sides of the wall. A channel that is driven from both
 * ends at once, or several walls trying to turn the outside at different speeds, leave the link out of it:
 * there is no sensible way to add two speeds up.
 */
public final class KineticRelay {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * How long the room's end of a link keeps turning after the entrance block stopped looking at it. The
     * room's chunks stay loaded whether or not its entrance block does, so the room has to let go of a link
     * nobody is looking at any more.
     */
    private static final long STALE_TICKS = 5;
    /** How far a chain of sources is followed while working out which way the power is running. */
    private static final int MAX_SOURCE_STEPS = 1024;
    private static final Direction[] SIDES = Direction.values();

    private KineticRelay() {
    }

    /**
     * Whether a shaft put against {@code face} of an endpoint block reaches it.
     *
     * <p>A room's shell is six machines, one per side of the room: a shaft runs from one block of a wall
     * into the next block of that same wall, but not sideways into another wall, into the floor or into
     * the ceiling. A link answers on one side at a time (see {@link #pilot}), so the sides must not come
     * as one machine, or hooking the factory up on one face would turn the whole room. A shaft does not
     * reach into the shell of the factory next door either, which would tie two rooms together.
     *
     * <p>Room side a shaft also reaches in wherever the face opens into the room itself, which is where
     * the player builds. The entrance block outside has no room around it, and answers one face at a
     * time ({@link #outwardFaceOpen}): a face is only live while a generator is feeding power in through
     * it, so driving one face of the block does not drag machinery built against the other five along.
     * The row of entrance blocks laid down for one factory is still one machine - the face toward a
     * neighbour of the same factory answers on it - so the machinery outside can be hooked up to any
     * cell of the factory, and the anchor block carries the link for all of them.
     */
    public static boolean takesShaft(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        BlockPos neighbour = pos.relative(face);
        Block neighbourBlock = level.getBlockState(neighbour)
                .getBlock();
        if (neighbourBlock instanceof FactoryBarrierBlock || neighbourBlock instanceof RecursiveFactoryBlock) {
            return sameSide(level, pos, neighbour);
        }
        if (state.getBlock() instanceof RecursiveFactoryBlock) {
            return outwardFaceOpen(level, pos, face, neighbour);
        }
        return roomSpace(level, pos, neighbour);
    }

    /**
     * The face of an entrance block a shaft reaches, when the machinery beside it is ordinary Create
     * machinery rather than a factory's own block. The entrance block answers per face, not as one machine,
     * and it does so in both directions:
     *
     * <p>While a real generator is feeding a face - the outside driving the factory - only that face
     * answers, so hooking the west face up to a motor leaves machinery built against the other five faces
     * of the block standing still. While it is the room that drives the outside, a face answers only if
     * the wall of that same side of the room is one of the walls doing the driving; that is the mask of
     * faces {@link #pilot} hands over, and it is what leaves the machinery built against the other faces
     * of the block where it is. Both directions are one speed each way, because an entrance block is one
     * Create block with one speed: the walls that drive the outside together have to agree on a speed.
     */
    private static boolean outwardFaceOpen(LevelReader level, BlockPos pos, Direction face, BlockPos neighbour) {
        if (!(level instanceof ServerLevel) || !(level.getBlockEntity(pos) instanceof EndpointBlockEntity entrance)) {
            return true;
        }
        EndpointBlockEntity anchor = anchorOf(entrance);
        if (anchor != null && anchor.isSource()) {
            return anchor.isOutwardFaceLive(face);
        }
        if (level.getBlockEntity(neighbour) instanceof KineticBlockEntity machine
                && !(machine instanceof EndpointBlockEntity)) {
            FactoryData.FactoryRecord record = record(entrance);
            return record != null && carriesPowerIn(machine, record);
        }
        return false;
    }

    /**
     * The entrance block that carries the link for a factory. The row of entrance blocks laid down for one
     * factory is one machine, and only the anchor cell of it works the link out, so whichever cell of the
     * row a machine has been built against, the faces of the outside that answer are read off the anchor.
     */
    @Nullable
    private static EndpointBlockEntity anchorOf(EndpointBlockEntity entrance) {
        FactoryData.FactoryRecord record = record(entrance);
        if (record == null) {
            return null;
        }
        if (entrance.getBlockPos()
                .equals(record.entrancePos())) {
            return entrance;
        }
        Level level = entrance.getLevel();
        return level != null && level.getBlockEntity(record.entrancePos()) instanceof EndpointBlockEntity anchor
                ? anchor
                : null;
    }

    /**
     * Hands the entrance block what its link is carrying this tick: which faces of its outside answer to
     * machinery, and the speed, capacity and load of the bridge itself. Create walks over to a block's
     * neighbours when the bridge puts a speed on it, so the faces are handed over afterwards and the block
     * is made to walk over to those neighbours a second time when any of the faces moved.
     */
    private static void handOver(EndpointBlockEntity entrance, int outwardFaces, float speed, float capacity,
                                 float load) {
        entrance.setBridge(speed, capacity, load);
        if (entrance.setOutwardFaces(outwardFaces)) {
            entrance.reapplyLink();
        }
    }

    /** The faces of {@code sides}, as the bit mask {@link EndpointBlockEntity#setOutwardFaces} takes. */
    private static int maskOf(Collection<Direction> sides) {
        int mask = 0;
        for (Direction side : sides) {
            mask |= EndpointBlockEntity.faceMask(side);
        }
        return mask;
    }

    /**
     * True when both blocks are endpoints of the same factory and stand on the same side of its room, which
     * is what makes them one machine. Two entrance blocks stand on no side at all, and so does any block on
     * the client, where no kinetic network is built: those stay connected the way they always were.
     */
    private static boolean sameSide(BlockGetter level, BlockPos pos, BlockPos neighbour) {
        if (!sameFactory(level, pos, neighbour)) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.dimension()
                        .equals(FactoryDimension.LEVEL_KEY)) {
            return true;
        }
        FactoryData.FactoryRecord record = record(serverLevel, factoryIdAt(level, pos));
        if (record == null) {
            return true;
        }
        return FactoryDimension.shellSide(record, pos) == FactoryDimension.shellSide(record, neighbour);
    }

    /** True when both blocks are endpoints of the same factory, see {@link #takesShaft}. */
    private static boolean sameFactory(BlockGetter level, BlockPos pos, BlockPos neighbour) {
        int factoryId = factoryIdAt(level, pos);
        return factoryId > 0 && factoryId == factoryIdAt(level, neighbour);
    }

    /** True for a spot the player can build in: the free space of the room this block belongs to. */
    private static boolean roomSpace(BlockGetter level, BlockPos pos, BlockPos neighbour) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        MinecraftServer server = serverLevel.getServer();
        int factoryId = factoryIdAt(level, pos);
        if (server == null || factoryId <= 0) {
            return false;
        }
        FactoryData.FactoryRecord record = FactoryData.get(server)
                .factory(factoryId);
        return record != null && FactoryDimension.isFreeSpace(record, neighbour);
    }

    private static int factoryIdAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint && endpoint.hasFactoryId()
                ? endpoint.getFactoryId()
                : -1;
    }

    /** Looks at both ends of {@code local}'s link. Called once per tick, on the server. */
    public static void tick(EndpointBlockEntity local) {
        if (!(local.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (local.getBlockState().getBlock() instanceof FactoryBarrierBlock) {
            dropStale(local);
            return;
        }
        pilot(local);
    }

    /**
     * The entrance block is the end that knows both, so it is the one that works the link out, one side of
     * the room at a time: a side whose face outside has machinery turning on it drives the wall of that
     * side, a side whose wall is turned by machinery inside the room drives the outside, and a side with a
     * generator on both ends of it is left to itself.
     *
     * <p>The anchor entrance block of a factory carries the link for the whole factory: the row of
     * entrance blocks is one machine, so a machine outside can be built against any cell of the factory
     * and the face it sits on still picks the wall of the room that answers.
     *
     * <p>Which way the power runs is worked out one side at a time, and which faces of the outside answer
     * follows from it: a side whose wall is turned by machinery in the room turns the outside, and only
     * the face of that side of the entrance block answers out there, so the machinery built against the
     * other faces of the block is left where it is. The sides that turn the outside have to agree on a
     * speed - the entrance block is one Create block with one speed - so sides that run at different
     * speeds are left out of the link together rather than one of them being picked.
     */
    private static void pilot(EndpointBlockEntity entrance) {
        FactoryData.FactoryRecord record = record(entrance);
        ServerLevel room = roomLevel(entrance);
        if (record == null || room == null || record.cells()
                .isEmpty()) {
            handOver(entrance, 0, 0, 0, 0);
            return;
        }
        if (!entrance.getBlockPos()
                .equals(record.entrancePos())) {
            handOver(entrance, 0, 0, 0, 0);
            note(entrance, record.id(), "this cell of the factory does not carry the link; "
                    + record.entrancePos() + " does");
            return;
        }

        boolean outsideTurning = isDriven(entrance);
        Set<Direction> hookedUp = outsideTurning
                ? hookedUpSides(record, entrance)
                : EnumSet.noneOf(Direction.class);
        Map<Direction, EndpointBlockEntity> walls = new EnumMap<>(Direction.class);
        List<Direction> drivingRoom = new ArrayList<>();
        List<Direction> drivingOutside = new ArrayList<>();
        for (Direction side : SIDES) {
            EndpointBlockEntity wall = channelBarrier(room, record, side);
            if (wall == null) {
                continue;
            }
            walls.put(side, wall);
            boolean outside = hookedUp.contains(side);
            boolean inside = isDriven(wall);
            if (outside && !inside) {
                drivingRoom.add(side);
            } else if (!outside && inside) {
                drivingOutside.add(side);
            } else {
                wall.setBridge(0, 0, 0);
            }
        }

        if (!drivingRoom.isEmpty()) {
            // The outside turns the factory: every side it is hooked up to gets a share of what the
            // outside has to spare, and the outside network carries what the room is asking for.
            float share = spareCapacity(entrance) / drivingRoom.size();
            float load = 0;
            for (Direction side : drivingRoom) {
                EndpointBlockEntity wall = walls.get(side);
                wall.setBridge(turningSpeed(entrance), share, 0);
                load += stressOf(wall);
            }
            handOver(entrance, 0, 0, 0, load);
            note(entrance, record.id(), "the outside turns the room's " + name(drivingRoom) + " wall at "
                    + turningSpeed(entrance) + " RPM");
            return;
        }

        if (outsideTurning) {
            // The outside turns the factory, and the room turns the sides this one is not hooked up to on
            // its own: nothing is passed either way, the way a shaft driven from both ends is.
            handOver(entrance, 0, 0, 0, 0);
            note(entrance, record.id(), "the outside turns the factory, and "
                    + (drivingOutside.isEmpty()
                            ? "no wall of the room turns on its own"
                            : "the room's " + name(drivingOutside) + " wall turns on its own"));
            return;
        }

        if (drivingOutside.isEmpty()) {
            handOver(entrance, 0, 0, 0, 0);
            note(entrance, record.id(), "idle");
            return;
        }

        float speed = turningSpeed(walls.get(drivingOutside.get(0)));
        for (Direction side : drivingOutside) {
            if (Math.abs(turningSpeed(walls.get(side)) - speed) > 1.0E-4F) {
                // Two walls of the room turning at different speeds: there is no way to add them up.
                for (Direction other : drivingOutside) {
                    walls.get(other)
                            .setBridge(0, 0, 0);
                }
                handOver(entrance, 0, 0, 0, 0);
                note(entrance, record.id(), "the room's " + name(drivingOutside)
                        + " walls turn at different speeds, so the link passes nothing on: an entrance"
                        + " block has one speed of its own, and the outside faces it answers all take"
                        + " that speed");
                return;
            }
        }

        // The room turns the outside: hand it the capacity the walls have to spare, and let each wall
        // carry its share of what the outside is asking for. The faces of the walls doing the driving are
        // the only ones of the entrance block that answer out there, so the machinery built against its
        // other faces - with no drive of its own - stays where it is.
        float capacity = 0;
        for (Direction side : drivingOutside) {
            capacity += spareCapacity(walls.get(side));
        }
        float share = stressOf(entrance) / drivingOutside.size();
        for (Direction side : drivingOutside) {
            walls.get(side)
                    .setBridge(0, 0, share);
        }
        handOver(entrance, maskOf(drivingOutside), speed, capacity, 0);
        note(entrance, record.id(), "the room's " + name(drivingOutside) + " wall turns the outside at "
                + speed + " RPM");
    }

    /**
     * The sides of the room the machinery outside is hooked up to: the faces of the factory's entrance
     * blocks that power is being fed in through. The entrance blocks of one factory are one machine, so it
     * does not matter which cell of the factory the machine was built against.
     */
    private static Set<Direction> hookedUpSides(FactoryData.FactoryRecord record, EndpointBlockEntity entrance) {
        Set<Direction> sides = EnumSet.noneOf(Direction.class);
        Level level = entrance.getLevel();
        if (level == null) {
            return sides;
        }
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            for (Direction face : SIDES) {
                if (level.getBlockEntity(cell.entrance()
                        .relative(face)) instanceof KineticBlockEntity neighbour
                        && !(neighbour instanceof EndpointBlockEntity)
                        && carriesPowerIn(neighbour, record)) {
                    sides.add(face);
                }
            }
        }
        return sides;
    }

    /**
     * True when the power turning {@code neighbour} comes from outside the factory: the chain of sources it
     * hangs off ends at a generator, without one of the factory's own entrance blocks in between. Machinery
     * built against the face of a factory that is being driven from the room is turned by the very entrance
     * block it hangs off, and its power runs the other way: that face is an output rather than an input.
     */
    private static boolean carriesPowerIn(KineticBlockEntity neighbour, FactoryData.FactoryRecord record) {
        Level level = neighbour.getLevel();
        BlockEntity cursor = neighbour;
        for (int step = 0; step < MAX_SOURCE_STEPS; step++) {
            if (!(cursor instanceof KineticBlockEntity turning)) {
                return false;
            }
            if (turning instanceof EndpointBlockEntity endpoint && endpoint.hasFactoryId()
                    && endpoint.getFactoryId() == record.id()) {
                return false;
            }
            if (turning.isSource()) {
                return !isBridgeGenerator(turning);
            }
            if (level == null || !turning.hasSource()) {
                return false;
            }
            cursor = level.getBlockEntity(turning.source);
        }
        return false;
    }

    /**
     * The room's end of one side of a link: a block of the room's shell that stands on {@code side} of it,
     * by the factory's anchor cell. Any block of that side would do - the side is a kinetic network of its
     * own - and a block that is not there any more moves along to the next one.
     */
    @Nullable
    private static EndpointBlockEntity channelBarrier(ServerLevel room, FactoryData.FactoryRecord record,
                                                     Direction side) {
        FactoryData.FactoryRecord.Cell anchor = record.anchorCell();
        if (anchor == null) {
            return null;
        }
        for (BlockPos wall : FactoryDimension.wallLine(record, anchor, side)) {
            if (room.getBlockState(wall)
                    .getBlock() instanceof FactoryBarrierBlock
                    && room.getBlockEntity(wall) instanceof EndpointBlockEntity barrier
                    && FactoryDimension.shellSide(record, wall) == side) {
                return barrier;
            }
        }
        return null;
    }

    /** Lets the room go of a link the entrance block stopped looking at. */
    private static void dropStale(EndpointBlockEntity room) {
        long lastLook = room.getBridgeTick();
        if (lastLook == Long.MIN_VALUE || room.getLevel()
                .getGameTime() - lastLook <= STALE_TICKS) {
            return;
        }
        room.setBridge(0, 0, 0);
    }

    /**
     * True when a generator is turning {@code endpoint}: either through a shaft it is attached to, or - the
     * case of the shell of a room - because a generator is turning the network it is a member of. A link's
     * own generator does not count, or a link that is being driven would look like one that is driving.
     *
     * <p>An end that is only turning because the link drives it is never counted as driving, not even when
     * the player has put a machine against it as well: that machine joins the very network the link built,
     * so reading the network back would have the link mistake its own work for the player's, drop the end it
     * is driving, pick it up again on the next tick, and leave a room's wall attaching and detaching from
     * its network once per tick.
     */
    private static boolean isDriven(KineticBlockEntity endpoint) {
        if (endpoint.hasSource()) {
            return true;
        }
        if (isBridgeGenerator(endpoint)) {
            return false;
        }
        KineticNetwork network = endpoint.getOrCreateNetwork();
        if (network == null) {
            return false;
        }
        for (KineticBlockEntity source : network.sources.keySet()) {
            if (source != endpoint && !isBridgeGenerator(source)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What is left of the stress capacity of the network {@code endpoint} sits on: what its generators add
     * up to, less what its own machinery is already asking for. Handing on the rest is what makes an
     * overloaded factory stall on both sides of the wall instead of on one side only.
     */
    private static float spareCapacity(KineticBlockEntity endpoint) {
        KineticNetwork network = endpoint.getOrCreateNetwork();
        if (network == null) {
            return 0;
        }
        float capacity = 0;
        for (Map.Entry<KineticBlockEntity, Float> source : network.sources.entrySet()) {
            if (source.getKey() != endpoint && !isBridgeGenerator(source.getKey())) {
                capacity += source.getValue();
            }
        }
        return Math.max(0, capacity - stressOf(endpoint));
    }

    /** The stress everything on {@code endpoint}'s network but {@code endpoint} itself is asking for. */
    private static float stressOf(KineticBlockEntity endpoint) {
        KineticNetwork network = endpoint.getOrCreateNetwork();
        if (network == null) {
            return 0;
        }
        float stress = 0;
        for (Map.Entry<KineticBlockEntity, Float> member : network.members.entrySet()) {
            if (member.getKey() != endpoint) {
                stress += member.getValue();
            }
        }
        return stress;
    }

    /** The speed a link passes on, which is zero while the end that would drive it is stalling. */
    private static float turningSpeed(KineticBlockEntity endpoint) {
        return endpoint.getSpeed();
    }

    /** True for an end that is only turning because the link drives it, rather than a generator of its own. */
    private static boolean isBridgeGenerator(KineticBlockEntity blockEntity) {
        return blockEntity instanceof EndpointBlockEntity endpoint && endpoint.getGeneratedSpeed() != 0;
    }

    /** The sides of a room as they read in a sentence: "north", "north and east". */
    private static String name(List<Direction> sides) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sides.size(); i++) {
            if (i > 0) {
                text.append(i == sides.size() - 1 ? " and " : ", ");
            }
            text.append(sides.get(i)
                    .getName());
        }
        return text.toString();
    }

    /** Puts one line about a link in the log, once, whenever what it says stops being true. */
    private static void note(EndpointBlockEntity entrance, int factoryId, String link) {
        if (link.equals(entrance.getLinkNote())) {
            return;
        }
        entrance.setLinkNote(link);
        LOGGER.info("Factory #{} link at {}: {}", factoryId, entrance.getBlockPos(), link);
    }

    @Nullable
    private static FactoryData.FactoryRecord record(EndpointBlockEntity endpoint) {
        MinecraftServer server = server(endpoint);
        if (server == null || !endpoint.hasFactoryId()) {
            return null;
        }
        return FactoryData.get(server)
                .factory(endpoint.getFactoryId());
    }

    @Nullable
    private static FactoryData.FactoryRecord record(ServerLevel level, int factoryId) {
        MinecraftServer server = level.getServer();
        if (server == null || factoryId <= 0) {
            return null;
        }
        return FactoryData.get(server)
                .factory(factoryId);
    }

    @Nullable
    private static ServerLevel roomLevel(EndpointBlockEntity endpoint) {
        MinecraftServer server = server(endpoint);
        return server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
    }

    @Nullable
    private static MinecraftServer server(EndpointBlockEntity endpoint) {
        return endpoint.getLevel() instanceof ServerLevel level ? level.getServer() : null;
    }
}
