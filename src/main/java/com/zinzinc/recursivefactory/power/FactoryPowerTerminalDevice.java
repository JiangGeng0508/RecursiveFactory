package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.BridgeCollector;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The simulated device of one power terminal: a port between the terminal's wire node (node 0) and its
 * hidden reference node (node 1), driven towards whatever FactoryPowerLinks has worked out for this end.
 *
 * <p>The device ticks whether or not its chunk is loaded, exactly as the electricity mod's own devices
 * do, which is what lets the outside end keep talking to a room that is standing open. What it must not
 * do is reach into an unloaded chunk to find out where it is: that would drag the room back into memory
 * every tick, which is the one thing the factory is careful not to do. A terminal in an unloaded chunk
 * simply answers nothing, and the link goes dormant with it.
 */
public final class FactoryPowerTerminalDevice extends SimpleElectricalDevice {
    /** The terminal's wire node, the one a player runs a wire into. */
    private static final int WIRE_NODE = 0;
    /** The terminal's own zero potential, which the port is driven against. */
    private static final int REFERENCE_NODE = 1;
    /**
     * How firmly the reference node is tied to earth, in siemens. The node stands inside the block where
     * nobody can wire it, so nothing else is going to hold it at a zero - and a reference that floats is a
     * reference that cannot be measured against: the port would read its own setpoint back as the grid's
     * voltage, both ends would sit at zero, and the link would never come up at all. Firm enough that the
     * earth this terminal offers the grid around it is the same earth either way.
     */
    private static final double REFERENCE_CONDUCTANCE = 1000.0D;

    /** Which end of the link this terminal is, as worked out on the last look that found one. */
    private int side = FactoryPowerLinks.OUTSIDE;

    public FactoryPowerTerminalDevice(Level level, BlockPos pos, DevicesSavedData deviceSD,
                                      SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    @Override
    public void preTick(BridgeCollector bridges) {
        FactoryPowerLinks.Link link = linkAt();
        if (link == null || !link.live) {
            return;
        }
        // The reference goes down first: the voltage the port measures is the difference between its wire
        // node and its reference, so the reference has to be a zero before that difference means anything.
        bridges.builder(pos).ground(REFERENCE_NODE, REFERENCE_CONDUCTANCE);
        // The mod stamps a source with its *second* node positive - its own battery reads back as minus
        // its voltage the other way round - so the wire node goes second for the setpoint to be the
        // voltage the grid is driven to rather than the negative of it. An end driving the negative of
        // its setpoint never agrees with the end that is reading it, and the two chase each other in
        // circles instead of settling.
        bridges.builder(pos).voltageSourceWithResistance(
                REFERENCE_NODE, WIRE_NODE,
                FactoryConfig.powerLinkResistance(),
                link.target[side]);
    }

    @Override
    public void postTick(SimulationResults results) {
        FactoryPowerLinks.Link link = linkAt();
        if (link == null) {
            return;
        }
        link.measured[side] = results.getVoltageAt(pos, WIRE_NODE, REFERENCE_NODE);
    }

    /** The link of this end, with this end's side marked as answering, or null when it is inert. */
    @Nullable
    private FactoryPowerLinks.Link linkAt() {
        if (!(level instanceof ServerLevel server) || !server.isLoaded(pos)) {
            return null;
        }
        FactoryPowerLinks.End end = FactoryPowerLinks.endOf(server, pos);
        if (end == null) {
            return null;
        }
        side = end.side();
        FactoryPowerLinks.Link link = FactoryPowerLinks.link(end.key());
        link.seen[side] = true;
        return link;
    }

    /** Turning the terminal on the spot keeps it: only a different block takes the device away. */
    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return oldState.getBlock().getClass() != newState.getBlock().getClass();
    }
}
