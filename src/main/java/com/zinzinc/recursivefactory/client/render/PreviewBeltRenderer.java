package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Belt items use preview coordinates, which cannot be tested against the real camera's frustum. */
final class PreviewBeltRenderer extends BeltRenderer {
    private final @Nullable AABB cell;

    PreviewBeltRenderer(@Nullable AABB cell) {
        super(null); // BeltRenderer does not use the dispatcher context.
        this.cell = cell;
    }

    @Override
    public boolean shouldCullItem(Vec3 position, Level level) {
        // Half-open bounds give an item crossing a cell boundary exactly one owning preview.
        return cell != null && !cell.contains(position);
    }

    void renderControllerItems(BeltBlockEntity belt, float partialTick, PoseStack pose,
                               MultiBufferSource buffers, int light, int overlay) {
        renderItems(belt, partialTick, pose, buffers, light, overlay);
    }
}
