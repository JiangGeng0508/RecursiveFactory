package com.zinzinc.recursivefactory.client;

import com.george_vi.electroenergetics.client.WireRenderer;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeHelper;
import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.power.FactoryWireSchematics;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import java.util.HashMap;
import java.util.List;

/** Blueprint-and-quill selection runs on the client, using CEE's synchronized node positions. */
public final class FactoryEntranceNodeCapture {
    private FactoryEntranceNodeCapture() {}

    public static void captureConnections(Level level, BlockPos origin,
            List<StructureBlockInfo> blocks) {
        if (level != Minecraft.getInstance().level) return;
        var nodes = new HashMap<InWorldNode, FactoryWireSchematics.Node>();
        WireRenderer.getNodeData().forEach((id, data) -> {
            var position = data.targetPosition != null ? data.targetPosition : data.position;
            if (position != null) nodes.put(id, new FactoryWireSchematics.Node(id, position, DetachedNodeHelper.isDetached(id)));
        });
        var edges = WireRenderer.getAllConnections().stream()
                .map(pair -> new FactoryWireSchematics.Edge(pair.getFirst(), pair.getSecond())).toList();
        FactoryWireSchematics.capture(origin, blocks, nodes, edges);
    }

    public static CompoundTag capture(Level level, BlockPos pos, CompoundTag stored) {
        // A schematic/preview world must keep its own snapshot, not sample the player's real world.
        if (level != Minecraft.getInstance().level) return stored.copy();
        ListTag nodes = new ListTag();
        WireRenderer.getNodeData().values().stream()
                .filter(node -> DetachedNodeHelper.isDetached(node.node))
                .sorted(java.util.Comparator.comparingInt(node -> node.node.id()))
                .forEach(node -> FactoryWires.addEntranceNode(nodes, pos,
                        node.targetPosition != null ? node.targetPosition : node.position, node.label));
        CompoundTag snapshot = new CompoundTag();
        if (!nodes.isEmpty()) snapshot.put("Nodes", nodes);
        return snapshot;
    }
}
