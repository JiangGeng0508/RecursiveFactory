package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.zinzinc.recursivefactory.world.WirePreviewGeometry;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaternionf;

/** Renders private rope snapshots without adding strands to Simulated's global client manager. */
public final class RopePreviewRenderer {
    private static PartialModel ropeModel, knotModel;
    private static boolean broken;
    private final Map<UUID, Strand> strands = new HashMap<>();

    public void render(List<CompoundTag> samples, PoseStack stack, MultiBufferSource buffers, double clock) {
        if (broken || !ModList.get().isLoaded("simulated")) return;
        try {
            if (ropeModel == null) {
                Class<?> models = Class.forName("dev.simulated_team.simulated.index.SimPartialModels");
                ropeModel = (PartialModel) models.getField("ROPE").get(null);
                knotModel = (PartialModel) models.getField("ROPE_KNOT").get(null);
            }
            var seen = new HashSet<UUID>();
            for (CompoundTag sample : samples) {
                if (!sample.hasUUID("Id")) continue;
                UUID id = sample.getUUID("Id");
                seen.add(id);
                Strand strand = strands.computeIfAbsent(id, ignored -> new Strand());
                strand.update(sample, clock);
                float alpha = (float) Mth.clamp(clock - strand.arrival, 0, 1);
                for (int i = 1; i < strand.points.size(); i++) {
                    Vec3 from = strand.point(i - 1, alpha), to = strand.point(i, alpha);
                    var segment = WirePreviewGeometry.clip(from, to);
                    if (segment == null) continue;
                    Vec3 direction = segment.to().subtract(segment.from()).normalize();
                    Quaternionf rotation = new Quaternionf().rotationTo(0, 1, 0,
                            (float) direction.x, (float) direction.y, (float) direction.z);
                    stack.pushPose();
                    try {
                        Vec3 start = segment.from();
                        stack.translate(start.x, start.y, start.z);
                        stack.mulPose(rotation);
                        if (i > 1 && from.distanceToSqr(start) < 1e-12 && WirePreviewGeometry.CELL.contains(from)) {
                            stack.pushPose();
                            try {
                                stack.translate(-0.5, -0.5, -0.5);
                                CachedBuffers.partialFacing(knotModel, AllBlocks.ROPE.getDefaultState(), Direction.NORTH)
                                        .light(LightTexture.FULL_BRIGHT).renderInto(stack, buffers.getBuffer(RenderType.solid()));
                            } finally {
                                stack.popPose();
                            }
                        }
                        stack.translate(-0.5, 0, -0.5);
                        stack.scale(1, (float) segment.from().distanceTo(segment.to()), 1);
                        CachedBuffers.partialFacing(ropeModel, AllBlocks.ROPE.getDefaultState(), Direction.NORTH)
                                .light(LightTexture.FULL_BRIGHT).renderInto(stack, buffers.getBuffer(RenderType.solid()));
                    } finally {
                        stack.popPose();
                    }
                }
            }
            strands.keySet().retainAll(seen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LogUtils.getLogger().warn("Sable factory preview could not render Simulated ropes", failure);
            broken = true;
        }
    }

    private static final class Strand {
        CompoundTag sample;
        List<Vec3> previous = List.of(), points = List.of();
        double arrival;

        void update(CompoundTag next, double clock) {
            if (next.equals(sample)) return;
            List<Vec3> updated = new ArrayList<>();
            for (Tag value : next.getList("Points", Tag.TAG_COMPOUND)) {
                CompoundTag point = (CompoundTag) value;
                updated.add(new Vec3(point.getDouble("X"), point.getDouble("Y"), point.getDouble("Z")));
            }
            // Winches add/remove nodes at the start. Snap topology changes instead of interpolating
            // unrelated node indices; equal samples must not restart interpolation.
            previous = points.size() == updated.size() ? points : updated;
            points = List.copyOf(updated);
            sample = next.copy();
            arrival = clock;
        }

        Vec3 point(int index, float alpha) {
            return previous.get(index).lerp(points.get(index), alpha);
        }
    }
}
