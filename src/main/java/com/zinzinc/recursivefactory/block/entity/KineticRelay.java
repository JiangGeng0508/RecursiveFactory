package com.zinzinc.recursivefactory.block.entity;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.block.FactoryBarrierBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Turns a factory's machinery from outside, and the outer machines from inside the factory.
 *
 * <p>The two ends of a factory's link are in different dimensions - the entrance block stands in the world
 * the factory was built in, the shell of its room in a dimension of its own - and Create's kinetic networks
 * never leave the level they were built in. The link is therefore carried across by hand: the entrance
 * block looks at both ends every tick and, while one of them is being turned by a generator, turns the
 * other one at the same speed and hands on what is left of the driving end's stress capacity.
 *
 * <p>Stress travels the way a shaft would carry it: the end that is being driven becomes a generator for
 * its own side with the capacity that is spare on the far side, and the end that is doing the driving
 * carries the far side's stress on its own network. Both ends then weigh their load against the same
 * capacity, so an overloaded factory stalls on both sides of the wall.
 *
 * <p>When both ends have a generator of their own the link stays out of the way and each side runs on its
 * own one: there is no sensible way to add two speeds up.
 */
public final class KineticRelay {
    /**
     * How long the room's end of a link keeps turning after the entrance block stopped looking at it. The
     * room's chunks stay loaded whether or not its entrance block does, so the room has to let go of a link
     * nobody is looking at any more.
     */
    private static final long STALE_TICKS = 5;

    private KineticRelay() {
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
     * The entrance block is the end that knows both, so it is the one that works the link out: it hands the
     * end that is being driven the speed and the spare capacity of the end that is driving, and hands the
     * driving end the stress the driven one is asking for. When neither end has a generator, or both do, the
     * link carries nothing and each end is left to itself.
     */
    private static void pilot(EndpointBlockEntity entrance) {
        EndpointBlockEntity room = anchorBarrier(entrance);
        if (room == null) {
            entrance.setBridge(0, 0, 0);
            return;
        }

        boolean entranceTurned = isDriven(entrance);
        boolean roomTurned = isDriven(room);
        if (entranceTurned && !roomTurned) {
            room.setBridge(turningSpeed(entrance), spareCapacity(entrance), 0);
            entrance.setBridge(0, 0, stressOf(room));
        } else if (roomTurned && !entranceTurned) {
            entrance.setBridge(turningSpeed(room), spareCapacity(room), 0);
            room.setBridge(0, 0, stressOf(entrance));
        } else {
            entrance.setBridge(0, 0, 0);
            room.setBridge(0, 0, 0);
        }
    }

    /**
     * The room's end of a factory's link: a block of the shell's north wall on the factory's anchor cell.
     * Any block of the shell would do - the whole shell is one kinetic network - so the one its own anchor
     * cell answers on is picked, and a wall block that is not there any more moves along to the next one.
     */
    @Nullable
    private static EndpointBlockEntity anchorBarrier(EndpointBlockEntity entrance) {
        FactoryData.FactoryRecord record = record(entrance);
        ServerLevel room = roomLevel(entrance);
        if (record == null || room == null || record.cells().isEmpty()) {
            return null;
        }
        // A room built out of several entrance blocks answers on the link of the one that started it,
        // rather than carrying one link per entrance block.
        if (!entrance.getBlockPos().equals(record.entrancePos())) {
            return null;
        }
        for (BlockPos wall : FactoryDimension.wallLine(record, record.anchorCell(), Direction.NORTH)) {
            if (room.getBlockState(wall).getBlock() instanceof FactoryBarrierBlock
                    && room.getBlockEntity(wall) instanceof EndpointBlockEntity barrier) {
                return barrier;
            }
        }
        return null;
    }

    /** Lets the room go of a link the entrance block stopped looking at. */
    private static void dropStale(EndpointBlockEntity room) {
        long lastLook = room.getBridgeTick();
        if (lastLook == Long.MIN_VALUE || room.getLevel().getGameTime() - lastLook <= STALE_TICKS) {
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

    @Nullable
    private static FactoryData.FactoryRecord record(EndpointBlockEntity endpoint) {
        MinecraftServer server = server(endpoint);
        if (server == null || !endpoint.hasFactoryId()) {
            return null;
        }
        return FactoryData.get(server).factory(endpoint.getFactoryId());
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
