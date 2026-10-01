package com.zinzinc.recursivefactory.power;

import com.zinzinc.recursivefactory.config.FactoryConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * The bookkeeping behind the factory's electrical links: one entry per factory face that carries a node
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
 * <p>The nodes and wires are saved by CEE. FactoryPowerNodes identifies the player-installed ends
 * by their positions; copied or printed rooms need their own nodes and wiring.
 */
public final class FactoryPowerLinks {
    /** The end outside the room, in whatever dimension the entrance block stands in. */
    public static final int OUTSIDE = 0;
    /** The end inside the room, in the factory dimension. */
    public static final int INSIDE = 1;

    private static final Map<LinkKey, Link> LINKS = new HashMap<>();

    private FactoryPowerLinks() {
    }

    /** The face of a factory a link belongs to. */
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

    /** Drops a link: the face it belongs to carries no node any more, so there is nothing to settle. */
    static void forget(LinkKey key) {
        LINKS.remove(key);
    }

    /** Forgets every link: a server does not inherit the links of the one before it. */
    static void reset() {
        LINKS.clear();
    }

    /**
     * One look at every link: the ends that answered the tick before are settled against each other, and
     * every answer is cleared again for the tick to come. A link whose ends both stopped answering is
     * kept rather than forgotten - its factory is still standing, and the ends answer again the moment
     * the chunks they stand in come back (see {@link FactoryPowerNodes#tick}).
     */
    static void tick() {
        for (Link link : LINKS.values()) {
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
