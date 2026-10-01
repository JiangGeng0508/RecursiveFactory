package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.content.connector.ConnectorBlock;
import com.george_vi.electroenergetics.events.AddToElectricGraphEvent;
import com.george_vi.electroenergetics.events.FinishElectricSimulationEvent;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.george_vi.electroenergetics.simulation.infrastructure.InWorldNodeData;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeType;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Player-installed CEE nodes on factory faces. Each face joins one outside node to one inside node.
 * CEE saves the nodes and wires; we recover their factory links from their positions after a restart.
 * This class must only be loaded while CEE is installed.
 */
public final class FactoryPowerNodes {
    /**
     * How far a node stands off the surface it belongs to, in blocks. Half a block is the surface itself,
     * and the little more puts the node's marker on the surface rather than in the middle of it - the
     * marker being a small model the electricity mod draws at the node.
     */
    private static final double STAND_OFF = 0.52D;
    /** Two spots closer together than this are the same one, when a node is looked for again. */
    private static final double SAME_SPOT = 1.0E-3D;

    /**
     * How firmly a port's reference node is tied to earth, in siemens. The node stands nowhere and nobody
     * can wire it, so nothing else is going to hold it at a zero - and a reference that floats is a
     * reference that cannot be measured against.
     */
    private static final double REFERENCE_CONDUCTANCE = 1000.0D;

    /** The node each end of each link stands on, as the last look at that factory found it. */
    private static final Map<FactoryPowerLinks.End, Held> NODES = new HashMap<>();
    private static final List<Placement> PENDING = new ArrayList<>();

    private record Placement(BlockEvent.EntityPlaceEvent event, Terminal terminal) {
    }

    private FactoryPowerNodes() {
    }

    /** One end's node: the level it lives in, the spot it stands on, the node, and the wall that has to be up. */
    private record Held(ServerLevel level, Vec3 pos, InWorldNode node, BlockPos room) {
    }

    /** A possible terminal and its distance from the anchor, used to select legacy duplicates consistently. */
    private record Terminal(FactoryPowerLinks.End end, ServerLevel level, Vec3 pos, BlockPos room, double rank) {
    }

    /** Forgets every node: a server does not inherit the nodes the last one was holding. */
    public static void reset() {
        NODES.clear();
        PENDING.clear();
    }

    /**
     * One look at every terminal the factories are carrying: a node standing on a face a player has put a
     * terminal on is bound to that face's end - the one it already was, or one an earlier run left in the
     * save - and every node whose face has gone, or that a player has taken away again, is let go, wires
     * and all.
     */
    static void tick(MinecraftServer server) {
        finishPlacements(server);
        Map<FactoryPowerLinks.End, Held> standing = look(server);
        ServerLevel room = server.getLevel(FactoryDimension.LEVEL_KEY);
        for (Map.Entry<FactoryPowerLinks.End, Held> entry : standing.entrySet()) {
            FactoryPowerLinks.End end = entry.getKey();
            Held held = entry.getValue();
            Held before = NODES.get(end);
            if (before != null && (before.level() != held.level()
                    || before.pos().distanceToSqr(held.pos()) > SAME_SPOT * SAME_SPOT
                    || !before.node().equals(held.node()))) {
                // Keep an existing terminal if another valid node wins the deterministic selection.
                // Only a node whose supporting factory surface is gone is removed.
                if (match(server, before.level(), before.pos()) == null) {
                    letGo(before);
                }
                FactoryPowerLinks.forget(end.key());
            }
            NODES.put(end, held);
            // The end answers while the room behind its face is up. A room is held open by the entrance
            // block standing at it, so this is what a player at the factory has; a room nobody is looking
            // at lets the link go dormant without the nodes going anywhere.
            if (room != null && held.room() != null && room.isLoaded(held.room())) {
                FactoryPowerLinks.link(end.key()).seen[end.side()] = true;
            }
        }
        Iterator<Map.Entry<FactoryPowerLinks.End, Held>> gone = NODES.entrySet().iterator();
        while (gone.hasNext()) {
            Map.Entry<FactoryPowerLinks.End, Held> entry = gone.next();
            if (standing.containsKey(entry.getKey())) {
                continue;
            }
            letGo(entry.getValue());
            FactoryPowerLinks.forget(entry.getKey().key());
            gone.remove();
        }
    }

    /** Let the connector item handle the click instead of entering or leaving the room. */
    static void onUseConnector(PlayerInteractEvent.RightClickBlock event) {
        if (FactoryConfig.powerLinkEnabled() && event.getItemStack().is(CEEBlocks.CONNECTOR.asItem())
                && event.getLevel().getBlockEntity(event.getPos()) instanceof EndpointBlockEntity) {
            event.setUseBlock(TriState.FALSE);
        }
    }

    /** Reserve a face only after normal item placement checks, including protection hooks. */
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!FactoryConfig.powerLinkEnabled() || event.isCanceled()
                || !(event.getLevel() instanceof ServerLevel level)
                || !event.getPlacedBlock().is(CEEBlocks.CONNECTOR.get())) {
            return;
        }
        Direction facing = event.getPlacedBlock().getValue(ConnectorBlock.FACING);
        BlockPos support = event.getPos().relative(facing.getOpposite());
        Terminal terminal = terminalAt(level.getServer(), level, support, facing);
        if (terminal == null || !(level.getBlockEntity(support) instanceof EndpointBlockEntity endpoint)
                || endpoint.getFactoryId() != terminal.end().key().factoryId()
                || (terminal.end().side() == FactoryPowerLinks.OUTSIDE
                        ? !(endpoint instanceof RecursiveFactoryBlockEntity)
                        : !(endpoint instanceof FactoryBarrierBlockEntity))) {
            return;
        }
        boolean reserved = PENDING.stream().anyMatch(p -> !p.event().isCanceled()
                && p.terminal().end().equals(terminal.end()));
        if (reserved || occupied(level.getServer(), terminal)) {
            event.setCanceled(true); // NeoForge restores both the block and the item stack.
            say(event.getEntity(), "message.recursivefactory.power.terminal_taken");
            return;
        }
        // EntityPlaceEvent runs inside NeoForge's snapshot transaction. MinecraftServer.execute may
        // run immediately on this thread, so defer explicitly until the next server tick instead.
        PENDING.add(new Placement(event, terminal));
    }

    private static boolean occupied(MinecraftServer server, Terminal terminal) {
        for (InWorldNodeData node : InfrastructureSavedData.load(terminal.level()).getDynamicNodes()) {
            if (node.detachedNodeType == DetachedNodeType.FIXED) {
                Terminal existing = match(server, terminal.level(), node.getGlobalPos());
                if (existing != null && existing.end().equals(terminal.end())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void finishPlacements(MinecraftServer server) {
        List<Placement> placements = List.copyOf(PENDING);
        PENDING.clear();
        for (Placement placement : placements) {
            BlockEvent.EntityPlaceEvent event = placement.event();
            Terminal terminal = placement.terminal();
            ServerLevel level = terminal.level();
            if (event.isCanceled() || !level.isLoaded(event.getPos())
                    || !level.getBlockState(event.getPos()).equals(event.getPlacedBlock())) {
                continue;
            }
            Direction facing = event.getPlacedBlock().getValue(ConnectorBlock.FACING);
            Terminal current = terminalAt(server, level, event.getPos().relative(facing.getOpposite()), facing);
            if (current == null || !current.end().equals(terminal.end()) || occupied(server, terminal)) {
                continue; // Leave the placed connector intact if its factory changed in the meantime.
            }
            level.removeBlock(event.getPos(), false);
            InfrastructureSavedData.load(level).createDetachedNode(DetachedNodeType.FIXED, terminal.pos());
            say(event.getEntity(), "message.recursivefactory.power.terminal");
        }
    }

    /** Tells the player what became of the connector they put down. */
    private static void say(@Nullable Entity player, String key) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable(key), true);
        }
    }

    /** Takes a node this end was standing on away, wires and all, so nothing keeps conducting to it. */
    private static void letGo(Held held) {
        InfrastructureSavedData sd = InfrastructureSavedData.load(held.level());
        InWorldNodeData node = standingAt(sd, held);
        if (node == null) {
            return;
        }
        for (InWorldNodeConnection connection : sd.getConnections(node)) {
            sd.removeAndDropConnection(connection);
        }
        sd.removeNode(node.node);
    }

    /** The end's own node, as long as it is still there and still the one this end put down. */
    @Nullable
    private static InWorldNodeData standingAt(InfrastructureSavedData sd, Held held) {
        InWorldNodeData node = sd.getNodeData(held.node());
        if (node == null || node.detachedNodeType != DetachedNodeType.FIXED) {
            return null;
        }
        return node.getGlobalPos().distanceToSqr(held.pos()) <= SAME_SPOT * SAME_SPOT ? node : null;
    }

    /**
     * The port of every end standing in the level being solved, driven towards whatever its link has
     * worked out for it: an ordinary source of the end's own, from its node to a node of the link's that
     * stands nowhere and is only ever inside this one tick's circuit.
     */
    static void onAddToElectricGraph(AddToElectricGraphEvent event) {
        if (!FactoryConfig.powerLinkEnabled()) {
            return;
        }
        int referenceId = -1;
        for (Map.Entry<FactoryPowerLinks.End, Held> entry : NODES.entrySet()) {
            Held held = entry.getValue();
            if (held.level() != event.level) {
                continue;
            }
            FactoryPowerLinks.End end = entry.getKey();
            FactoryPowerLinks.Link link = FactoryPowerLinks.link(end.key());
            if (!link.live) {
                continue;
            }
            InWorldNodeData node = standingAt(event.sd, held);
            if (node == null) {
                continue;
            }
            // A source of the mod's carries its voltage on its second node - a battery's positive
            // terminal is the second one - so the wire node goes second and the setpoint is the voltage
            // the grid is driven to rather than the negative of it. An end driving the negative of its
            // setpoint never agrees with the end reading it, and the two chase each other in circles
            // instead of settling.
            InWorldNode wire = node.node;
            InWorldNode reference = new InWorldNode(referenceId--, wire.sourcePos());
            double target = link.target[end.side()];
            // The reference goes down first: what the port holds is the difference between its node and
            // its reference, so the reference has to be an earth before that difference means anything.
            // A reference left to float would make the port read its own setpoint back as the grid's
            // voltage, and the two ends would sit at zero instead of coming up. It is earthed as firmly
            // as the block this replaced earthed its own hidden reference, so that the earth a port
            // offers the grid around it reads as an earth either way.
            event.builder.ground(reference, REFERENCE_CONDUCTANCE);
            event.builder.connect(reference, wire,
                    ElectricalProperties.fromThevenin(FactoryConfig.powerLinkResistance(), target));
        }
    }

    /**
     * The voltage each end's node came out of the tick at, which is what the link settles the two ends
     * against on the next look. An end that is not driving is measured all the same: that is the grid's
     * own voltage, and it is where a link that is just coming up starts both of its ends.
     */
    static void onFinishElectricSimulation(FinishElectricSimulationEvent event) {
        if (!FactoryConfig.powerLinkEnabled()) {
            return;
        }
        for (Map.Entry<FactoryPowerLinks.End, Held> entry : NODES.entrySet()) {
            Held held = entry.getValue();
            if (held.level() != event.level || standingAt(event.sd, held) == null) {
                continue;
            }
            FactoryPowerLinks.End end = entry.getKey();
            FactoryPowerLinks.link(end.key()).measured[end.side()] = event.results.getVoltageAt(held.node());
        }
    }

    /** Read existing nodes only; an unused factory never allocates electrical nodes. */
    private static Map<FactoryPowerLinks.End, Held> look(MinecraftServer server) {
        Map<FactoryPowerLinks.End, Held> standing = new HashMap<>();
        Map<FactoryPowerLinks.End, Terminal> best = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (InWorldNodeData node : InfrastructureSavedData.load(level).getDynamicNodes()) {
                if (node.detachedNodeType != DetachedNodeType.FIXED) {
                    continue;
                }
                Terminal terminal = match(server, level, node.getGlobalPos());
                if (terminal == null || !better(terminal, best.get(terminal.end()))) {
                    continue;
                }
                best.put(terminal.end(), terminal);
                standing.put(terminal.end(), new Held(level, terminal.pos(), node.node, terminal.room()));
            }
        }
        return standing;
    }

    /** Resolve only the six possible supports of a node, without enumerating every room-wall block. */
    @Nullable
    private static Terminal match(MinecraftServer server, ServerLevel level, Vec3 pos) {
        for (Direction facing : Direction.values()) {
            BlockPos support = BlockPos.containing(pos.add(-facing.getStepX() * STAND_OFF,
                    -facing.getStepY() * STAND_OFF, -facing.getStepZ() * STAND_OFF));
            if (off(support, facing).distanceToSqr(pos) > SAME_SPOT * SAME_SPOT) {
                continue;
            }
            Terminal terminal = terminalAt(server, level, support, facing);
            if (terminal != null) {
                return terminal;
            }
            // Older automatic nodes sit 0.02 blocks on the wall side of the surface. Keep their
            // positions and wires, including the base node which stood above the checkerboard floor.
            if (level.dimension() == FactoryDimension.LEVEL_KEY) {
                BlockPos wall = support.relative(facing, facing == Direction.DOWN ? 2 : 1);
                terminal = terminalAt(server, level, wall, facing.getOpposite());
                if (terminal != null && terminal.end().side() == FactoryPowerLinks.INSIDE
                        && support.equals(FactoryDimension.inward(
                                FactoryData.get(server).factory(terminal.end().key().factoryId()), wall, facing))) {
                    return new Terminal(terminal.end(), level, pos, wall, terminal.rank());
                }
            }
        }
        return null;
    }

    /** A connector faces away from its supporting block. Only the factory dimension contains room walls. */
    @Nullable
    private static Terminal terminalAt(MinecraftServer server, ServerLevel level, BlockPos support,
                                       Direction facing) {
        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord record = data.factoryWithEntranceCell(level.dimension().location(), support);
        int side = FactoryPowerLinks.OUTSIDE;
        Direction face = facing;
        BlockPos wall;
        if (record != null && record.cellAt(support.relative(facing)) == null) {
            FactoryData.FactoryRecord.Cell cell = record.cellAt(support);
            // This position is used only for checking whether the matching room is loaded.
            wall = cell.center();
        } else {
            if (level.dimension() != FactoryDimension.LEVEL_KEY) {
                return null;
            }
            record = data.factoryAt(support);
            face = facing.getOpposite();
            if (record == null || FactoryDimension.shellSide(record, support) != face
                    || FactoryDimension.inward(record, support, face) == null) {
                return null;
            }
            side = FactoryPowerLinks.INSIDE;
            wall = support;
        }
        FactoryData.FactoryRecord.Cell anchor = record.anchorCell();
        if (record.entranceDimension() == null || anchor == null) {
            return null;
        }
        Vec3 pos = off(support, facing);
        double rank = side == FactoryPowerLinks.OUTSIDE
                ? support.distSqr(anchor.entrance()) : pos.distanceToSqr(Vec3.atCenterOf(anchor.center()));
        return new Terminal(new FactoryPowerLinks.End(new FactoryPowerLinks.LinkKey(record.id(), face), side),
                level, pos, wall, rank);
    }

    /** Legacy duplicate nodes are selected consistently, independent of their order in the save. */
    private static boolean better(Terminal terminal, @Nullable Terminal best) {
        if (best == null || terminal.rank() != best.rank()) {
            return best == null || terminal.rank() < best.rank();
        }
        int x = Double.compare(terminal.pos().x, best.pos().x);
        int y = Double.compare(terminal.pos().y, best.pos().y);
        return x != 0 ? x < 0 : y != 0 ? y < 0 : terminal.pos().z < best.pos().z;
    }

    /** A spot on a block's face: its middle, a little way out of the side that faces the way given. */
    private static Vec3 off(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(
                face.getStepX() * STAND_OFF,
                face.getStepY() * STAND_OFF,
                face.getStepZ() * STAND_OFF);
    }

}
