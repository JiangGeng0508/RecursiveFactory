package com.zinzinc.recursivefactory.world;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

/** Clips wires crossing cell boundaries so each entrance draws only its own miniature. */
public final class WirePreviewGeometry {
    public static final AABB CELL = new AABB(-8, 0, -8, 8, 16, 8);
    private WirePreviewGeometry() {}

    public record Segment(Vec3 from, Vec3 to) {}

    public static @Nullable Segment clip(Vec3 from, Vec3 to) {
        double low = 0, high = 1;
        double[] start = {from.x, from.y, from.z}, end = {to.x, to.y, to.z};
        double[] min = {CELL.minX, CELL.minY, CELL.minZ}, max = {CELL.maxX, CELL.maxY, CELL.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(start[axis]) || !Double.isFinite(end[axis])) return null;
            double delta = end[axis] - start[axis];
            if (Math.abs(delta) < 1e-10) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return null;
                continue;
            }
            double a = (min[axis] - start[axis]) / delta, b = (max[axis] - start[axis]) / delta;
            low = Math.max(low, Math.min(a, b));
            high = Math.min(high, Math.max(a, b));
            if (high <= low) return null;
        }
        Vec3 a = from.lerp(to, low), b = from.lerp(to, high);
        return a.distanceToSqr(b) < 1e-12 ? null : new Segment(a, b);
    }
}
