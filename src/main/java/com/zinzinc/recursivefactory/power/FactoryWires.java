package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.CEESimulatedDevices;
import com.george_vi.electroenergetics.CEEWireTypes;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryConnection;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryHolderBlock;
import com.george_vi.electroenergetics.content.wire.WireAttachment;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.WireType;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.InWorldNodeData;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;
import com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeType;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.world.FactoryData;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Room-local CEE infrastructure. Only call this class when CEE is installed. */
public final class FactoryWires {
    private FactoryWires() {}

    public static CompoundTag capture(ServerLevel level, FactoryData.FactoryRecord room, BlockPos origin) {
        InfrastructureSavedData grid = InfrastructureSavedData.load(level);
        List<InWorldNodeData> nodes = grid.getNodes().stream().map(grid::getNodeData)
                .filter(node -> node != null && node.isValid() && inside(room, node.getGlobalPos()))
                .sorted(Comparator.comparingDouble((InWorldNodeData node) -> node.getGlobalPos().x)
                        .thenComparingDouble(node -> node.getGlobalPos().y)
                        .thenComparingDouble(node -> node.getGlobalPos().z).thenComparingInt(node -> node.node.id()))
                .toList();
        Map<InWorldNode, Integer> indices = new HashMap<>();
        ListTag nodeTags = new ListTag();
        for (InWorldNodeData node : nodes) {
            indices.put(node.node, nodeTags.size());
            CompoundTag tag = new CompoundTag();
            tag.put("Position", vector(node.getGlobalPos().subtract(Vec3.atLowerCornerOf(origin))));
            if (node.detachedNodeType != null) {
                tag.putString("Detached", node.detachedNodeType.getSerializedName());
            } else {
                tag.putLong("Block", node.node.sourcePos().subtract(origin).asLong());
                tag.putInt("Id", node.node.id());
            }
            if (node.label != null) tag.putString("Label", node.label);
            nodeTags.add(tag);
        }
        TreeSet<InWorldNodeConnection> connections = new TreeSet<>();
        for (InWorldNodeData node : nodes) connections.addAll(grid.getConnections(node));
        ListTag wires = new ListTag();
        for (InWorldNodeConnection connection : connections) {
            Integer first = indices.get(connection.node1());
            Integer second = indices.get(connection.node2());
            WireData data = grid.getConnectionData(connection);
            if (first == null || second == null || data == null) continue;
            CompoundTag tag = writeWire(data);
            tag.putInt("From", first);
            tag.putInt("To", second);
            wires.add(tag);
        }
        ListTag catenary = new ListTag();
        grid.getAllCatenaryConnections().stream()
                .filter(line -> inside(room, line.pos1().getCenter()) && inside(room, line.pos2().getCenter()))
                .sorted(Comparator.comparingLong((CatenaryConnection line) -> line.pos1().asLong())
                        .thenComparingLong(line -> line.pos2().asLong()))
                .forEach(line -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putLong("From", line.pos1().subtract(origin).asLong());
                    tag.putLong("To", line.pos2().subtract(origin).asLong());
                    // A holder can be outside the cell shown by this entrance's preview.
                    var first = level.getBlockState(line.pos1());
                    var second = level.getBlockState(line.pos2());
                    tag.putBoolean("Low", CEEBlocks.CATENARY_HOLDER.has(first)
                            && first.getValue(CatenaryHolderBlock.STYLE).isLow()
                            || CEEBlocks.CATENARY_HOLDER.has(second)
                            && second.getValue(CatenaryHolderBlock.STYLE).isLow());
                    catenary.add(tag);
                });
        CompoundTag result = new CompoundTag();
        if (!nodeTags.isEmpty()) result.put("Nodes", nodeTags);
        if (!wires.isEmpty()) result.put("Connections", wires);
        if (!catenary.isEmpty()) result.put("Catenary", catenary);
        return result;
    }

    private static boolean inside(FactoryData.FactoryRecord room, Vec3 pos) {
        if (pos.y < FactoryData.BASE_Y - .03 || pos.y > FactoryData.CEILING_Y + 1.03) return false;
        return room.cells().stream().anyMatch(cell -> pos.x >= cell.roomX() - .03
                && pos.x <= cell.roomX() + FactoryData.CELL_SIZE + .03
                && pos.z >= cell.roomZ() - .03 && pos.z <= cell.roomZ() + FactoryData.CELL_SIZE + .03);
    }

    public static ItemRequirement requirements(CompoundTag snapshot) {
        return requirements(snapshot, null, BlockPos.ZERO);
    }

    /** Existing wires/nodes are omitted when resuming a partially completed printer. */
    public static ItemRequirement requirements(CompoundTag snapshot, ServerLevel level, BlockPos origin) {
        InfrastructureSavedData grid = level == null ? null : InfrastructureSavedData.load(level);
        ListTag nodes = snapshot.getList("Nodes", Tag.TAG_COMPOUND);
        List<ItemStack> items = new ArrayList<>();
        List<InWorldNode> resolved = new ArrayList<>();
        for (Tag value : nodes) {
            CompoundTag node = (CompoundTag) value;
            InWorldNode found = grid == null ? null : findNode(grid, node, origin);
            resolved.add(found);
            if (DetachedNodeType.FIXED.getSerializedName().equals(node.getString("Detached")) && found == null)
                items.add(CEEBlocks.CONNECTOR.asStack());
        }
        for (Tag value : snapshot.getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) value;
            if (!validEndpoints(wire, nodes.size())) continue;
            InWorldNode from = resolved.get(wire.getInt("From"));
            InWorldNode to = resolved.get(wire.getInt("To"));
            if (grid != null && from != null && to != null && grid.isConnected(from, to)) continue;
            WireData data = readWire(wire);
            if (data == null) continue;
            items.add(new ItemStack(data.wireType().getDrops(), CEEConfigs.server().wiresPerSpool.get()));
            for (Pair<Float, WireAttachment> attachment : data.attachments()) items.addAll(attachment.getSecond().getItemRequirement());
        }
        for (Tag value : snapshot.getList("Catenary", Tag.TAG_COMPOUND)) {
            CompoundTag wire = (CompoundTag) value;
            BlockPos from = BlockPos.of(wire.getLong("From")).offset(origin);
            BlockPos to = BlockPos.of(wire.getLong("To")).offset(origin);
            if (grid == null || !grid.getAllCatenaryConnections().contains(new CatenaryConnection(from, to)))
                items.add(new ItemStack(CEEWireTypes.COPPER.get().getDrops(), CEEConfigs.server().wiresPerSpool.get()));
        }
        return items.isEmpty() ? ItemRequirement.NONE : new ItemRequirement(ItemRequirement.ItemUseType.CONSUME, items);
    }

    public static void place(ServerLevel level, BlockPos origin, CompoundTag snapshot) {
        if (snapshot.isEmpty()) return;
        InfrastructureSavedData grid = InfrastructureSavedData.load(level);
        DevicesSavedData devices = DevicesSavedData.load(level);
        List<InWorldNode> nodes = new ArrayList<>();
        for (Tag value : snapshot.getList("Nodes", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            InWorldNode node = findNode(grid, tag, origin);
            if (node == null) {
                Vec3 position = vector(tag.getCompound("Position")).add(Vec3.atLowerCornerOf(origin));
                if (tag.contains("Detached")) {
                    DetachedNodeType type = DetachedNodeType.byName(tag.getString("Detached"));
                    node = grid.createDetachedNode(type == null ? DetachedNodeType.FIXED : type, position).node;
                } else {
                    BlockPos block = BlockPos.of(tag.getLong("Block")).offset(origin);
                    node = new InWorldNode(tag.getInt("Id"), block);
                    if (devices.getDevice(block) == null) devices.addDevice(CEESimulatedDevices.TEMPORARY.get(), block, new CompoundTag());
                    grid.createNode(node, position, position.subtract(Vec3.atLowerCornerOf(block)));
                }
            }
            nodes.add(node);
            if (tag.contains("Label")) {
                InWorldNodeData data = grid.getNodeData(node);
                data.label = tag.getString("Label");
                grid.wireSync.handleNodeLabelRename(data);
            }
        }
        for (Tag value : snapshot.getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            if (!validEndpoints(tag, nodes.size())) continue;
            InWorldNode from = nodes.get(tag.getInt("From"));
            InWorldNode to = nodes.get(tag.getInt("To"));
            WireData data = readWire(tag);
            if (data == null || grid.isConnected(from, to)) continue;
            if (!new InWorldNodeConnection(from, to).node1().equals(from)) {
                List<Pair<Float, WireAttachment>> reversed = data.attachments().stream()
                        .map(attachment -> Pair.of(1F - attachment.getFirst(), attachment.getSecond())).toList();
                data = new WireData(data.wireType(), data.temperature(), reversed, data.length);
            }
            grid.connect(from, to, data);
        }
        for (Tag value : snapshot.getList("Catenary", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            BlockPos from = BlockPos.of(tag.getLong("From")).offset(origin);
            BlockPos to = BlockPos.of(tag.getLong("To")).offset(origin);
            if (!grid.getAllCatenaryConnections().contains(new CatenaryConnection(from, to))) grid.connectCatenary(from, to);
        }
        grid.setDirty();
    }

    private static InWorldNode findNode(InfrastructureSavedData grid, CompoundTag tag, BlockPos origin) {
        if (!tag.contains("Detached")) {
            InWorldNode node = new InWorldNode(tag.getInt("Id"), BlockPos.of(tag.getLong("Block")).offset(origin));
            return grid.hasNode(node) ? node : null;
        }
        Vec3 target = vector(tag.getCompound("Position")).add(Vec3.atLowerCornerOf(origin));
        for (InWorldNodeData node : grid.getDynamicNodes()) {
            if (node.detachedNodeType != null && node.getGlobalPos().distanceToSqr(target) < 1e-8) return node.node;
        }
        return null;
    }

    public static boolean validEndpoints(CompoundTag tag, int size) {
        int first = tag.getInt("From"), second = tag.getInt("To");
        return first >= 0 && second >= 0 && first < size && second < size && first != second;
    }

    public static CompoundTag writeWire(WireData data) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", CEERegistries.WIRE_TYPE.getKey(data.wireType()).toString());
        tag.putDouble("Length", data.length);
        ListTag attachments = new ListTag();
        for (Pair<Float, WireAttachment> entry : data.attachments()) {
            CompoundTag attachment = entry.getSecond().write();
            attachment.putFloat("Point", entry.getFirst());
            attachments.add(attachment);
        }
        if (!attachments.isEmpty()) tag.put("Attachments", attachments);
        return tag;
    }

    public static WireData readWire(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Type"));
        WireType type = id == null ? null : CEERegistries.WIRE_TYPE.get(id);
        if (type == null) return null;
        List<Pair<Float, WireAttachment>> attachments = new ArrayList<>();
        for (Tag value : tag.getList("Attachments", Tag.TAG_COMPOUND)) {
            CompoundTag attachment = (CompoundTag) value;
            ResourceLocation kind = ResourceLocation.tryParse(attachment.getString("ID"));
            if (kind != null && CEERegistries.WIRE_ATTACHMENT_TYPE.containsKey(kind))
                attachments.add(Pair.of(attachment.getFloat("Point"), WireAttachment.read(attachment)));
        }
        return new WireData(type, 0, attachments, tag.getDouble("Length"));
    }

    public static CompoundTag vector(Vec3 position) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("X", position.x); tag.putDouble("Y", position.y); tag.putDouble("Z", position.z);
        return tag;
    }

    public static Vec3 vector(CompoundTag tag) {
        return new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }
}
