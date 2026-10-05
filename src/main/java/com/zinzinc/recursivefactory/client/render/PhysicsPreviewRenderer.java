package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Draws ordinary preview meshes in each physics body's sampled pose; requires no client Sable API. */
public final class PhysicsPreviewRenderer {
    private final Map<UUID, Body> bodies = new HashMap<>();

    public void render(Level level, List<CompoundTag> samples, PoseStack stack, MultiBufferSource buffers,
                       float partialTick, double clock) {
        var seen = new HashSet<UUID>();
        for (CompoundTag sample : samples) {
            if (!sample.hasUUID("Id")) continue;
            UUID id = sample.getUUID("Id");
            seen.add(id);
            Body body = bodies.computeIfAbsent(id, ignored -> new Body());
            body.update(level, sample, clock);
            float alpha = (float) Mth.clamp(clock - body.arrival, 0, 1);
            Vec3 position = body.previousPosition.lerp(body.position, alpha);
            Quaternionf rotation = new Quaternionf(body.previousRotation).slerp(body.rotation, alpha);
            stack.pushPose();
            try {
                stack.translate(position.x, position.y, position.z);
                stack.mulPose(rotation);
                body.mesh.render(stack, buffers);
                body.mesh.renderBlockEntities(stack, buffers, partialTick);
            } finally {
                stack.popPose();
            }
        }
        bodies.keySet().retainAll(seen);
    }

    private static final class Body {
        CompoundTag geometry;
        FactoryProjectionCache mesh;
        Vec3 previousPosition, position;
        Quaternionf previousRotation, rotation;
        double arrival;

        void update(Level level, CompoundTag sample, double clock) {
            Vec3 next = new Vec3(sample.getDouble("X"), sample.getDouble("Y"), sample.getDouble("Z"));
            Quaternionf nextRotation = new Quaternionf((float) sample.getDouble("QX"), (float) sample.getDouble("QY"),
                    (float) sample.getDouble("QZ"), (float) sample.getDouble("QW")).normalize();
            if (position == null || !position.equals(next) || !rotation.equals(nextRotation)) {
                previousPosition = position == null ? next : position;
                previousRotation = rotation == null ? nextRotation : rotation;
                position = next;
                rotation = nextRotation;
                arrival = clock;
            }
            CompoundTag nextGeometry = sample.getCompound("Geometry");
            if (!nextGeometry.equals(geometry)) {
                geometry = nextGeometry.copy();
                mesh = new FactoryProjectionCache(level,
                        EndpointBlockEntity.readPreviewBlocks(geometry, level.registryAccess()), List.of(),
                        EndpointBlockEntity.readPreviewBlockEntities(geometry), new CompoundTag(),
                        new FactoryProjectionCache.EntityStore(), clock);
            }
        }
    }
}
