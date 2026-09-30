package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.events.AddToElectricGraphEvent;
import com.george_vi.electroenergetics.events.FinishElectricSimulationEvent;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.george_vi.electroenergetics.simulation.infrastructure.InWorldNodeData;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeType;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The electrical nodes a factory carries on its own surface, and the port driven into each of them.
 *
 * <p>A player runs the grid outside into the node standing on the entrance block's face, and the room's
 * own grid into the node standing on the room's wall behind that same face. The two are one link (see
 * {@link FactoryPowerLinks}), so the two grids feed each other and the factory's face is what joins them.
 * Nothing of ours stands in between: both nodes are the electricity mod's own detached nodes, which hold
 * wires and carry current without a block under them.
 *
 * <p>Where the nodes go is worked out from the factory's own data every tick rather than remembered, and
 * it is the middle of the cell the two sides share: the face of the entrance block that faces the way
 * the room's wall is on, and the inner surface of that wall. A room that is copied or printed therefore
 * comes back with its faces wired up by itself. A node a factory has left behind - its entrance block
 * broken, or its face moved to another cell - is taken away again, wire and all.
 *
 * <p>The nodes answer only while the room is up: a room is held open by the entrance block standing at
 * it (see FactoryDimension), so a player at the factory or inside the room has the two grids joined, and
 * a room nobody is looking at is left alone. The nodes themselves stay where they are either way, so the
 * wires a player has run are never dropped just because a chunk unloaded.
 *
 * <p>Nothing here may be touched unless Create: Electro Energetics is installed: every class in this
 * package names one of its types, and loading one without the mod would take the whole game with it.
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

    private FactoryPowerNodes() {
    }

    /** One end's node, and the level it lives in. */
    private record Held(ServerLevel level, Vec3 pos, InWorldNode node) {
    }

    /** Where one end's node goes: the level it goes in, the spot, and the wall that has to be up. */
    private record Spot(ServerLevel level, Vec3 pos, BlockPos room) {
    }

    /** Forgets every node: a server does not inherit the nodes the last one was holding. */
    public static void reset() {
        NODES.clear();
    }

    /**
     * One look at every factory face: every end that should carry a node gets one - the one it already
     * had, or a new one, or one an earlier run left in the save - and every node whose end is gone is
     * taken away.
     */
    static void tick(MinecraftServer server) {
        Map<FactoryPowerLinks.End, Spot> spots = look(server);
        ServerLevel room = server.getLevel(FactoryDimension.LEVEL_KEY);
        for (Map.Entry<FactoryPowerLinks.End, Spot> entry : spots.entrySet()) {
            FactoryPowerLinks.End end = entry.getKey();
            Spot spot = entry.getValue();
            Held held = NODES.get(end);
            if (held != null && (held.level() != spot.level()
                    || held.pos().distanceToSqr(spot.pos()) > SAME_SPOT * SAME_SPOT)) {
                // The face moved: a cell joined it or left it, or the entrance block it answers on was
                // put down elsewhere. The node left at the old spot is not this end's any more.
                letGo(held);
                held = null;
            }
            if (held == null) {
                held = stand(spot);
                NODES.put(end, held);
            }
            // The end answers while the room behind its face is up. A room is held open by the entrance
            // block standing at it, so this is what a player at the factory has; a room nobody is looking
            // at lets the link go dormant without the nodes going anywhere.
            if (room != null && room.isLoaded(spot.room())) {
                FactoryPowerLinks.link(end.key()).seen[end.side()] = true;
            }
        }
        Iterator<Map.Entry<FactoryPowerLinks.End, Held>> held = NODES.entrySet().iterator();
        while (held.hasNext()) {
            Map.Entry<FactoryPowerLinks.End, Held> entry = held.next();
            if (spots.containsKey(entry.getKey())) {
                continue;
            }
            letGo(entry.getValue());
            FactoryPowerLinks.forget(entry.getKey().key());
            held.remove();
        }
    }

    /** The node an end stands on: the one already at that spot, or a new one. */
    private static Held stand(Spot spot) {
        InfrastructureSavedData sd = InfrastructureSavedData.load(spot.level());
        InWorldNodeData node = fixedNodeAt(sd, spot.pos());
        if (node == null) {
            node = sd.createDetachedNode(DetachedNodeType.FIXED, spot.pos());
        }
        return new Held(spot.level(), spot.pos(), node.node);
    }

    /** Takes a node this end was standing on away, wires and all, so nothing keeps conducting to it. */
    private static void letGo(Held held) {
        InfrastructureSavedData sd = InfrastructureSavedData.load(held.level());
        InWorldNodeData node = fixedNodeAt(sd, held.pos());
        if (node == null) {
            return;
        }
        for (InWorldNodeConnection connection : sd.getConnections(node)) {
            sd.removeAndDropConnection(connection);
        }
        sd.removeNode(node.node);
    }

    /**
     * The fixed node standing at a spot, or null when there is none. A node is found by where it stands
     * rather than by a number remembered from the tick it was made on: the nodes are the electricity
     * mod's own and outlive a server of ours, so the ones a save is carrying are picked up again as they
     * are instead of a second set being put on top of them. Only fixed nodes are looked at - a hanging
     * wire's node is the mod's to move about, and it must not be mistaken for one of ours.
     */
    @Nullable
    private static InWorldNodeData fixedNodeAt(InfrastructureSavedData sd, Vec3 pos) {
        for (InWorldNodeData node : sd.getDynamicNodes()) {
            if (node.detachedNodeType != DetachedNodeType.FIXED) {
                continue;
            }
            if (node.getGlobalPos().distanceToSqr(pos) <= SAME_SPOT * SAME_SPOT) {
                return node;
            }
        }
        return null;
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

    /**
     * Every end of every face of every factory that is standing, with where its node goes: one entry per
     * face the room carries a wall on, plus the entrance block's own face when a block stands for that
     * cell. Nothing here reads a block or a chunk: what a factory is made of is in the save, so a face
     * that is standing gets its nodes whether or not anybody is near it.
     */
    private static Map<FactoryPowerLinks.End, Spot> look(MinecraftServer server) {
        Map<FactoryPowerLinks.End, Spot> spots = new HashMap<>();
        ServerLevel room = server.getLevel(FactoryDimension.LEVEL_KEY);
        if (room == null) {
            return spots;
        }
        FactoryData data = FactoryData.get(server);
        for (FactoryData.FactoryRecord record : data.factories()) {
            ResourceLocation entranceDimension = record.entranceDimension();
            FactoryData.FactoryRecord.Cell anchor = record.anchorCell();
            if (entranceDimension == null || anchor == null) {
                continue;
            }
            ServerLevel outside = server.getLevel(dimension(entranceDimension));
            for (Direction face : Direction.values()) {
                FactoryData.FactoryRecord.Cell cell = outerCell(record, face, anchor);
                if (cell == null) {
                    continue;
                }
                BlockPos wall = middleWall(record, cell, face);
                BlockPos entry = wall == null ? null : FactoryDimension.inward(record, wall, face);
                if (wall == null || entry == null) {
                    continue;
                }
                FactoryPowerLinks.LinkKey key = new FactoryPowerLinks.LinkKey(record.id(), face);
                spots.put(new FactoryPowerLinks.End(key, FactoryPowerLinks.INSIDE),
                        new Spot(room, off(entry, face), wall));
                BlockPos entrance = outerEntrance(record, face, anchor);
                if (outside != null && entrance != null) {
                    spots.put(new FactoryPowerLinks.End(key, FactoryPowerLinks.OUTSIDE),
                            new Spot(outside, off(entrance, face), wall));
                }
            }
        }
        return spots;
    }

    /**
     * The cell whose wall a face's room end answers on: the one carrying that side nearest the factory's
     * anchor cell, so a room of several cells still answers on one part of a side rather than on all of
     * it. A cell the room does not reach the outside on - one with another cell behind it - has no wall
     * on that side and is passed over. Where the wall line ends does not depend on the cell chosen (see
     * {@link #middleWall}), so a room that has grown still answers on its far wall.
     */
    @Nullable
    private static FactoryData.FactoryRecord.Cell outerCell(FactoryData.FactoryRecord record, Direction face,
                                                            FactoryData.FactoryRecord.Cell anchor) {
        FactoryData.FactoryRecord.Cell best = null;
        double bestDistance = Double.MAX_VALUE;
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            if (middleWall(record, cell, face) == null) {
                continue;
            }
            double distance = cell.center().distSqr(anchor.center());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cell;
            }
        }
        return best;
    }

    /**
     * The wall block a face's link answers on: the middle of the wall line the cell shares with what is
     * outside the room. A corner column is shell on two sides at once and has no free space behind it
     * (see FactoryDimension#inward), and a block a neighbour stands behind is room rather than wall, so
     * both are passed over.
     */
    @Nullable
    private static BlockPos middleWall(FactoryData.FactoryRecord record, FactoryData.FactoryRecord.Cell cell,
                                       Direction face) {
        BlockPos center = cell.center();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos wall : FactoryDimension.wallLine(record, cell, face)) {
            if (FactoryDimension.shellSide(record, wall) != face
                    || FactoryDimension.inward(record, wall, face) == null) {
                continue;
            }
            double distance = wall.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = wall;
            }
        }
        return best;
    }

    /**
     * The entrance block a face's outside end stands on: one of the factory's own blocks out there, with
     * none of the same factory's other blocks against that face. A factory of one cell has exactly one of
     * these per face and it is that cell's block; a factory that has grown answers on its far blocks
     * instead, so a node never ends up buried between two blocks of the same factory. Where several
     * blocks share a side - a row of them along the other axis - the one nearest the anchor is taken,
     * which keeps the outside end on the same row as the room end. Null while no block is out there.
     */
    @Nullable
    private static BlockPos outerEntrance(FactoryData.FactoryRecord record, Direction face,
                                          FactoryData.FactoryRecord.Cell anchor) {
        BlockPos anchorEntrance = anchor.entrance();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (FactoryData.FactoryRecord.Cell cell : record.cells()) {
            BlockPos entrance = cell.entrance();
            if (entrance.equals(FactoryData.UNBOUND_ENTRANCE)) {
                continue;
            }
            if (record.cellAt(entrance.relative(face)) != null) {
                continue;
            }
            double distance = entrance.distSqr(anchorEntrance);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entrance;
            }
        }
        return best;
    }

    /** A spot on a block's face: its middle, a little way out of the side that faces the way given. */
    private static Vec3 off(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(
                face.getStepX() * STAND_OFF,
                face.getStepY() * STAND_OFF,
                face.getStepZ() * STAND_OFF);
    }

    private static ResourceKey<Level> dimension(ResourceLocation location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }
}
