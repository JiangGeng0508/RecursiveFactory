package com.zinzinc.recursivefactory.power;

import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

/**
 * The bookkeeping behind the factory's electrical links: one entry per factory face that has a terminal
 * on both sides of it, holding the state the two ends need to agree on.
 *
 * <p>The two ends sit in two different dimensions, and the electricity mod runs one simulation per
 * dimension, so the two grids cannot be solved as one: a node outside is not a node inside. Each end is
 * therefore an ordinary voltage source of its own - a port - and the two ports are run as an intertie the
 * way a power bridge is: the port holding the higher voltage is the master, the other port mirrors that
 * voltage, and the master then biases its own source so that it absorbs exactly the power the other port
 * delivers. Power is conserved and the link bootstraps from whichever side is live, with no notion of a
 * direction the power is meant to flow in.
 *
 * <p>Which end is which is worked out from where the terminal stands, every tick, rather than being
 * written down: a terminal against an entrance block is the outside end of that face, and a terminal
 * against the wall of a room is the room's end of the side it stands on. That also means a room that is
 * copied or printed comes back with its terminals wired up by itself.
 */
public final class FactoryPowerLinks {
    /** The end outside the room, in whatever dimension the entrance block stands in. */
    public static final int OUTSIDE = 0;
    /** The end inside the room, in the factory dimension. */
    public static final int INSIDE = 1;

    private static final Map<LinkKey, Link> LINKS = new HashMap<>();

    private FactoryPowerLinks() {
    }

    /** The face of a factory an end belongs to. */
    record LinkKey(int factoryId, Direction face) {
    }

    /** One end of a link: which link, and which of its two ends this is. */
    record End(LinkKey key, int side) {
    }

    /** What the two ends of one link share. */
    static final class Link {
        /** The voltage each end's port is driving towards, worked out on the tick before. */
        final double[] target = new double[2];
        /** The voltage each end's port measured across itself on the tick before. */
        final double[] measured = new double[2];
        /** Whether each end answered this tick; the flags are read and cleared by tick(). */
        final boolean[] seen = new boolean[2];
        /** True while both ends answered on the tick before: an end whose partner is gone is inert. */
        boolean live;
    }

    static Link link(LinkKey key) {
        return LINKS.computeIfAbsent(key, ignored -> new Link());
    }

    /**
     * Which end of which link the terminal at this position is, or null when it is not one: a terminal
     * that is not against an entrance block and not against the wall of a room does nothing.
     */
    @Nullable
    static End endOf(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        FactoryData data = FactoryData.get(server);
        for (Direction side : Direction.values()) {
            BlockPos entrancePos = pos.relative(side);
            if (!level.isLoaded(entrancePos)) {
                continue;
            }
            int roomId = FactoryDimension.entranceRoomOf(level, data, entrancePos);
            if (roomId > 0) {
                // side points from the terminal at the entrance block, so the face of the entrance the
                // terminal hangs on is the other way round - and it is that face, not the direction the
                // terminal was found in, that the room's own end answers on.
                return new End(new LinkKey(roomId, side.getOpposite()), OUTSIDE);
            }
        }
        if (!level.dimension().equals(FactoryDimension.LEVEL_KEY)) {
            return null;
        }
        FactoryData.FactoryRecord record = data.factoryAt(pos);
        if (record == null) {
            return null;
        }
        Direction face = wallFace(record, pos);
        return face == null ? null : new End(new LinkKey(record.id(), face), INSIDE);
    }

    /**
     * The side of the room a terminal inside it answers on: the one side of the shell it stands against.
     *
     * <p>A terminal can touch two sides at once - standing on the floor in a corner touches the floor and
     * a wall - and a wall is what a terminal is put against, so a wall is preferred over the floor or the
     * ceiling. Two walls at once is a corner proper and answers on neither, so a corner never picks a
     * side for the player by accident; shellSide answers a corner column the way the item link's wall
     * does.
     */
    @Nullable
    static Direction wallFace(FactoryData.FactoryRecord record, BlockPos pos) {
        Direction wall = null;
        int walls = 0;
        Direction floorOrCeiling = null;
        int floorsAndCeilings = 0;
        for (Direction side : Direction.values()) {
            if (FactoryDimension.shellSide(record, pos.relative(side)) != side) {
                continue;
            }
            if (side.getAxis().isHorizontal()) {
                wall = side;
                walls++;
            } else {
                floorOrCeiling = side;
                floorsAndCeilings++;
            }
        }
        if (walls == 1) {
            return wall;
        }
        if (walls == 0 && floorsAndCeilings == 1) {
            return floorOrCeiling;
        }
        return null;
    }

    /**
     * One look at every link: the ends that answered the tick before are settled against each other, and
     * a link neither of whose ends answered is forgotten.
     */
    static void tick(MinecraftServer server) {
        Iterator<Map.Entry<LinkKey, Link>> entries = LINKS.entrySet().iterator();
        while (entries.hasNext()) {
            Link link = entries.next().getValue();
            boolean live = link.seen[OUTSIDE] && link.seen[INSIDE];
            if (live && !link.live) {
                // The link is coming up: neither end was driving on the last look - that is what not
                // being live meant - so what each end measured is the voltage its own grid holds with no
                // port of ours standing on it. Both ends therefore start there, and bringing a link up is
                // a no-op rather than a short across either grid. Starting them at zero instead is what a
                // short is, and the current it draws is enough to take a player's wire before the two
                // ends have settled between them.
                link.target[OUTSIDE] = finiteOrZero(link.measured[OUTSIDE]);
                link.target[INSIDE] = finiteOrZero(link.measured[INSIDE]);
            }
            link.live = live;
            if (live) {
                settle(link);
            } else {
                link.target[OUTSIDE] = 0;
                link.target[INSIDE] = 0;
            }
            if (!link.seen[OUTSIDE] && !link.seen[INSIDE]) {
                entries.remove();
                continue;
            }
            link.seen[OUTSIDE] = false;
            link.seen[INSIDE] = false;
        }
    }

    /**
     * The intertie proper: the master end mirrors the other end's voltage, and its own source is biased so
     * that the power the other port delivers is exactly the power this one takes.
     */
    private static void settle(Link link) {
        double resistance = FactoryConfig.powerLinkResistance();
        double response = FactoryConfig.powerLinkResponse();
        double maxCurrent = FactoryConfig.powerLinkMaxCurrent();

        double outside = finiteOrZero(link.measured[OUTSIDE]);
        double inside = finiteOrZero(link.measured[INSIDE]);
        double outsideCurrent = (link.target[OUTSIDE] - outside) / resistance;
        double insideCurrent = (link.target[INSIDE] - inside) / resistance;
        double outsidePower = outside * outsideCurrent;
        double insidePower = inside * insideCurrent;

        double outsideSet;
        double insideSet;
        if (Math.abs(inside) >= Math.abs(outside)) {
            outsideSet = inside;
            double masterCurrent = -outsidePower / Math.max(Math.abs(inside), 0.01D);
            insideSet = inside + Mth.clamp(masterCurrent, -maxCurrent, maxCurrent) * resistance;
        } else {
            insideSet = outside;
            double masterCurrent = -insidePower / Math.max(Math.abs(outside), 0.01D);
            outsideSet = outside + Mth.clamp(masterCurrent, -maxCurrent, maxCurrent) * resistance;
        }

        double swing = maxCurrent * resistance;
        link.target[OUTSIDE] = limited(
                link.target[OUTSIDE] + (outsideSet - link.target[OUTSIDE]) * response, outside, swing);
        link.target[INSIDE] = limited(
                link.target[INSIDE] + (insideSet - link.target[INSIDE]) * response, inside, swing);
    }

    /**
     * A setpoint held within reach of what the port last measured. The current through a port is the
     * difference between its setpoint and its own voltage over its resistance, so keeping that difference
     * down keeps the current down: a grid that steps - a machine switching on, a battery going in - cannot
     * have the whole step pushed through the link's resistance in one tick, which is a great many amperes
     * through whatever wire the player has run. The master end is already inside this bound (its bias is
     * clamped to {@code power.linkMaxCurrent} before it is turned into a voltage), so in practice this
     * holds the mirroring end back until the grid it drives has come up with it.
     *
     * <p>Below the measured voltage the bound gives way rather than clamps. A setpoint that far under the
     * grid it is wired to is not a control demand - the master's own bias never reaches past the bound -
     * it is a setpoint carried over from before that grid was energised, and pinning it to the bound
     * would still take {@code power.linkMaxCurrent} out of a grid that has only just come up, which is
     * more than the player's wire can carry. Sitting on the measured voltage asks for nothing instead,
     * and the next look derives the setpoint again from both ends together. Above the measured voltage
     * there is no such hazard, because driving a voltage up is answered by the load rather than by a
     * source, so the rate limit stands on that side and brings a room up to voltage one step at a time.
     */
    private static double limited(double target, double measured, double swing) {
        double reach = Math.max(Math.abs(swing), 1.0E-9D);
        if (target < measured - reach) {
            return measured;
        }
        return Math.min(target, measured + reach);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
