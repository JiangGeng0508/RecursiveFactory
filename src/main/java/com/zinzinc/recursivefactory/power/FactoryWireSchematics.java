package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;
import com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeType;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Connections CEE's native blueprint capture misses because an endpoint uses virtual coordinates. */
public final class FactoryWireSchematics {
    private FactoryWireSchematics() {}

    public record Node(InWorldNode id, Vec3 position, boolean detached) {}
    public record Edge(InWorldNodeConnection connection, WireData data) {}

    public static void capture(Level level, BlockPos origin, List<StructureBlockInfo> blocks) {
        if (blocks.stream().noneMatch(block -> block.state().is(ModBlocks.RECURSIVE_FACTORY.get()))) return;
        if (!(level instanceof ServerLevel server)) {
            if (level.isClientSide) com.zinzinc.recursivefactory.client.FactoryEntranceNodeCapture
                    .captureConnections(level, origin, blocks);
            return;
        }
        InfrastructureSavedData grid = InfrastructureSavedData.load(server);
        Map<InWorldNode, Node> nodes = new HashMap<>();
        TreeSet<InWorldNodeConnection> connections = new TreeSet<>();
        for (InWorldNode id : grid.getNodes()) {
            var data = grid.getNodeData(id);
            if (data == null || !data.isValid()
                    || data.detachedNodeType != null && data.detachedNodeType != DetachedNodeType.FIXED) continue;
            nodes.put(id, new Node(id, data.getGlobalPos(), data.detachedNodeType != null));
            connections.addAll(grid.getConnections(id));
        }
        List<Edge> edges = new ArrayList<>();
        for (InWorldNodeConnection connection : connections) {
            WireData data = grid.getConnectionData(connection);
            if (data != null) edges.add(new Edge(connection, data));
        }
        capture(origin, blocks, nodes, edges);
    }

    /** The same selection/ownership rules are used with server data and CEE's client snapshot. */
    public static void capture(BlockPos origin, List<StructureBlockInfo> blocks,
                               Map<InWorldNode, Node> nodes, List<Edge> edges) {
        Map<BlockPos, StructureBlockInfo> selected = new HashMap<>();
        for (StructureBlockInfo block : blocks) {
            selected.put(block.pos().offset(origin), block);
            if (block.nbt() != null && block.state().is(ModBlocks.RECURSIVE_FACTORY.get())) {
                block.nbt().remove(RecursiveFactoryBlockEntity.ENTRANCE_WIRES_TAG);
                block.nbt().remove(RecursiveFactoryBlockEntity.PENDING_WIRES_TAG);
            }
        }
        Map<BlockPos, CompoundTag> snapshots = new HashMap<>();
        Map<BlockPos, Map<InWorldNode, Integer>> indices = new HashMap<>();
        for (Edge edge : edges) {
            Node first = nodes.get(edge.connection.node1()), second = nodes.get(edge.connection.node2());
            if (first == null || second == null || !first.detached && !second.detached) continue;
            BlockPos from = support(first, selected), to = support(second, selected);
            if (from == null || to == null) continue; // Both supporting blocks must be in the selection.
            BlockPos owner = !first.detached ? to : !second.detached ? from : from.compareTo(to) <= 0 ? from : to;
            if (selected.get(owner).nbt() == null) continue;
            CompoundTag snapshot = snapshots.computeIfAbsent(owner, ignored -> {
                CompoundTag tag = new CompoundTag();
                tag.put("Nodes", new ListTag()); tag.put("Connections", new ListTag());
                return tag;
            });
            Map<InWorldNode, Integer> ids = indices.computeIfAbsent(owner, ignored -> new HashMap<>());
            CompoundTag wire = FactoryWires.writeWire(edge.data);
            wire.putInt("From", addNode(snapshot, ids, first, from, owner, selected));
            wire.putInt("To", addNode(snapshot, ids, second, to, owner, selected));
            snapshot.getList("Connections", Tag.TAG_COMPOUND).add(wire);
        }
        snapshots.forEach((owner, snapshot) -> selected.get(owner).nbt()
                .put(RecursiveFactoryBlockEntity.ENTRANCE_WIRES_TAG, snapshot));
    }

    private static BlockPos support(Node node, Map<BlockPos, StructureBlockInfo> selected) {
        if (!node.detached) return selected.containsKey(node.id.sourcePos()) ? node.id.sourcePos() : null;
        for (Direction face : Direction.values()) {
            BlockPos block = BlockPos.containing(node.position.add(-face.getStepX() * .52,
                    -face.getStepY() * .52, -face.getStepZ() * .52));
            StructureBlockInfo info = selected.get(block);
            if (info == null || !info.state().is(ModBlocks.RECURSIVE_FACTORY.get())) continue;
            Vec3 expected = Vec3.atCenterOf(block).add(face.getStepX() * .52, face.getStepY() * .52, face.getStepZ() * .52);
            if (node.position.distanceToSqr(expected) < 1e-6) return block;
        }
        return null;
    }

    private static int addNode(CompoundTag snapshot, Map<InWorldNode, Integer> indices, Node node,
                               BlockPos support, BlockPos owner, Map<BlockPos, StructureBlockInfo> selected) {
        return indices.computeIfAbsent(node.id, ignored -> {
            CompoundTag tag = new CompoundTag();
            tag.put("Position", FactoryWires.vector(node.position.subtract(Vec3.atLowerCornerOf(owner))));
            tag.putLong("Support", support.subtract(owner).asLong());
            tag.putString("SupportBlock", BuiltInRegistries.BLOCK.getKey(selected.get(support).state().getBlock()).toString());
            if (node.detached) tag.putString("Detached", DetachedNodeType.FIXED.getSerializedName());
            else {
                tag.putLong("Block", node.id.sourcePos().subtract(owner).asLong());
                tag.putInt("Id", node.id.id());
            }
            ListTag list = snapshot.getList("Nodes", Tag.TAG_COMPOUND);
            int index = list.size(); list.add(tag); return index;
        });
    }

    public static ItemRequirement requirements(CompoundTag snapshot) {
        // Each entrance already pays for its own terminals. This owner pays only for the shared wire.
        CompoundTag wires = snapshot.copy();
        for (Tag value : wires.getList("Nodes", Tag.TAG_COMPOUND)) ((CompoundTag) value).remove("Detached");
        return FactoryWires.requirements(wires);
    }

    public static void transform(CompoundTag snapshot, StructureTransform transform) {
        for (Tag value : snapshot.getList("Nodes", Tag.TAG_COMPOUND)) {
            CompoundTag node = (CompoundTag) value;
            node.put("Position", FactoryWires.vector(transform.applyWithoutOffset(FactoryWires.vector(node.getCompound("Position")))));
            for (String key : List.of("Support", "Block")) if (node.contains(key))
                node.putLong(key, transform.applyWithoutOffset(BlockPos.of(node.getLong(key))).asLong());
        }
    }

    /** Paid wires wait for both actual endpoints; fulfilled entries are removed permanently. */
    public static boolean restore(ServerLevel level, BlockPos owner, CompoundTag pending) {
        InfrastructureSavedData grid = InfrastructureSavedData.load(level);
        ListTag nodes = pending.getList("Nodes", Tag.TAG_COMPOUND);
        return pending.getList("Connections", Tag.TAG_COMPOUND).removeIf(value -> {
            CompoundTag wire = (CompoundTag) value;
            if (!FactoryWires.validEndpoints(wire, nodes.size())) return true;
            CompoundTag fromTag = nodes.getCompound(wire.getInt("From")), toTag = nodes.getCompound(wire.getInt("To"));
            InWorldNode from = resolve(level, grid, owner, fromTag), to = resolve(level, grid, owner, toTag);
            // If a player removed the owner's terminal, abandon its queued wires instead of resurrecting it.
            if ((from == null && fromTag.getLong("Support") == 0)
                    || (to == null && toTag.getLong("Support") == 0)) return true;
            if (from == null || to == null) return false;
            WireData data = FactoryWires.readWire(wire);
            if (data != null && !grid.isConnected(from, to)) FactoryWires.connect(grid, from, to, data);
            return true;
        });
    }

    private static InWorldNode resolve(ServerLevel level, InfrastructureSavedData grid, BlockPos owner, CompoundTag tag) {
        BlockPos support = BlockPos.of(tag.getLong("Support")).offset(owner);
        if (!level.isLoaded(support) || !BuiltInRegistries.BLOCK.getKey(level.getBlockState(support).getBlock()).toString()
                .equals(tag.getString("SupportBlock"))) return null;
        return FactoryWires.findNode(grid, tag, owner);
    }
}
