package com.zinzinc.recursivefactory.client.render;

import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.CEEPartialModels;
import com.george_vi.electroenergetics.client.WireRenderer;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryHolderBlock;
import com.george_vi.electroenergetics.foundation.QuadraticWireHelper;
import com.george_vi.electroenergetics.simulation.WireType;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.world.WirePreviewGeometry;
import com.zinzinc.recursivefactory.world.WirePreviewGeometry.Segment;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/** Uses CEE's own wire models without installing preview nodes into its global client grid. */
public final class FactoryWirePreviewRenderer {
    private record Wire(Vec3 from, Vec3 to, WireData data, List<Vec3> points, List<Segment> segments, boolean contained) {}
    private record CatenarySegment(Segment segment, float thickness) {}
    private final List<Wire> wires = new ArrayList<>();
    private final List<Vec3> detachedNodes = new ArrayList<>();
    private final List<CatenarySegment> catenary = new ArrayList<>();

    public FactoryWirePreviewRenderer(CompoundTag snapshot, Level world) {
        ListTag nodes = snapshot.getList("Nodes", Tag.TAG_COMPOUND);
        for (Tag value : nodes) {
            CompoundTag node = (CompoundTag) value;
            Vec3 pos = FactoryWires.vector(node.getCompound("Position"));
            if (node.contains("Detached") && WirePreviewGeometry.CELL.contains(pos)) detachedNodes.add(pos);
        }
        for (Tag value : snapshot.getList("Connections", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            if (!FactoryWires.validEndpoints(tag, nodes.size())) continue;
            WireData data = FactoryWires.readWire(tag);
            if (data == null) continue;
            Vec3 from = FactoryWires.vector(nodes.getCompound(tag.getInt("From")).getCompound("Position"));
            Vec3 to = FactoryWires.vector(nodes.getCompound(tag.getInt("To")).getCompound("Position"));
            if (from.distanceToSqr(to) < 1e-10) continue;
            List<Vec3> points = QuadraticWireHelper.cablePoints(from, to, data.getSag(from.distanceTo(to)));
            List<Segment> segments = clipped(points, to);
            if (!segments.isEmpty()) wires.add(new Wire(from, to, data, points, segments,
                    WirePreviewGeometry.CELL.contains(to) && points.stream().allMatch(WirePreviewGeometry.CELL::contains)));
        }
        for (Tag value : snapshot.getList("Catenary", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            BlockPos first = BlockPos.of(tag.getLong("From")), second = BlockPos.of(tag.getLong("To"));
            Vec3 from = Vec3.atBottomCenterOf(first), to = Vec3.atBottomCenterOf(second);
            if (from.distanceToSqr(to) < 1e-10) continue;
            addCatenary(QuadraticWireHelper.cablePoints(from, to, 0, 10), to, .75F);
            var firstState = world.getBlockState(first);
            var secondState = world.getBlockState(second);
            boolean low = tag.contains("Low") ? tag.getBoolean("Low")
                    : CEEBlocks.CATENARY_HOLDER.has(firstState) && firstState.getValue(CatenaryHolderBlock.STYLE).isLow()
                    || CEEBlocks.CATENARY_HOLDER.has(secondState) && secondState.getValue(CatenaryHolderBlock.STYLE).isLow();
            if (low) continue;
            Vec3 topFrom = from.add(0, 1.5, 0), topTo = to.add(0, 1.5, 0);
            List<Vec3> upper = QuadraticWireHelper.cablePoints(topFrom, topTo, (float)(17.5 / from.distanceTo(to)), 4);
            addCatenary(upper, topTo, .75F);
            Vec3 delta = to.subtract(from);
            for (int i = 1; i < upper.size(); i++) {
                Vec3 point = upper.get(i);
                if (i == upper.size() - 1 && point.distanceToSqr(topTo) < 1.3) continue;
                double along = Math.clamp(point.subtract(from).dot(delta) / delta.lengthSqr(), 0, 1);
                Segment segment = WirePreviewGeometry.clip(point, from.add(delta.scale(along)));
                if (segment != null) catenary.add(new CatenarySegment(segment, .495F));
            }
        }
    }

    private void addCatenary(List<Vec3> points, Vec3 end, float thickness) {
        for (Segment segment : clipped(points, end)) catenary.add(new CatenarySegment(segment, thickness));
    }

    private static List<Segment> clipped(List<Vec3> points, Vec3 end) {
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Segment segment = WirePreviewGeometry.clip(points.get(i), i + 1 < points.size() ? points.get(i + 1) : end);
            if (segment != null) result.add(segment);
        }
        return result;
    }

    public void render(PoseStack pose, MultiBufferSource buffers, Level world) {
        for (Vec3 pos : detachedNodes) {
            CachedBuffers.partial(CEEPartialModels.WIRE_TIE, Blocks.ANDESITE.defaultBlockState()).translate(pos)
                    .light(LightTexture.FULL_BRIGHT).renderInto(pose, buffers.getBuffer(RenderType.cutout()));
        }
        for (Wire wire : wires) {
            if (wire.contained) WireRenderer.forceRenderWire(wire.points, wire.from, wire.to, pose, buffers, wire.data.wireType(), world);
            else for (Segment segment : wire.segments) renderSegment(segment, wire.data.wireType(), 1, pose, buffers);
            for (var attachment : wire.data.attachments()) {
                float point = attachment.getFirst();
                float sag = wire.data.getSag(wire.from.distanceTo(wire.to));
                Vec3 position = QuadraticWireHelper.posAt(wire.from, wire.to, point, sag);
                if (!WirePreviewGeometry.CELL.contains(position)) continue;
                float elevation = QuadraticWireHelper.pointElevationInDegrees(wire.from, wire.to, point, sag);
                pose.pushPose();
                pose.translate(position.x, position.y, position.z);
                pose.mulPose(new Quaternionf().rotationY((float)Math.atan2(wire.to.x - wire.from.x, wire.to.z - wire.from.z) + (float)Math.PI / 2));
                pose.mulPose(new Quaternionf().rotationX((float)Math.PI));
                attachment.getSecond().type.render(pose, buffers, attachment.getSecond(), position, LightTexture.FULL_BRIGHT, elevation);
                pose.popPose();
            }
        }
        for (CatenarySegment segment : catenary) renderSegment(segment.segment, null, segment.thickness, pose, buffers);
    }

    private static void renderSegment(Segment segment, WireType type, float thickness, PoseStack pose, MultiBufferSource buffers) {
        Vec3 from = segment.from(), to = segment.to();
        CachedBuffers.partial(type == null ? CEEPartialModels.WIRE_SEGMENT : type.getModel(), Blocks.ANDESITE.defaultBlockState())
                .translate(from).rotateY((float)Math.atan2(to.x - from.x, to.z - from.z))
                .rotateX(-(float)Math.atan2(to.y - from.y, Math.hypot(to.x - from.x, to.z - from.z)))
                .scale(thickness, thickness, (float)(from.distanceTo(to) * 2) + .02F)
                .light(LightTexture.FULL_BRIGHT).renderInto(pose, buffers.getBuffer(type == null ? RenderType.solid() : type.renderType()));
    }
}
