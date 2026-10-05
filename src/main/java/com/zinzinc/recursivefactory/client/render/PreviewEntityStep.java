package com.zinzinc.recursivefactory.client.render;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The timing behind the preview's entity interpolation, kept apart from the renderer and free of client
 * types so it can be exercised on a headless server.
 *
 * <p>A sample arrives once a tick, but it lands at a wandering point of a frame: the server pushes it when
 * it notices the change and the network hands it over whenever it likes. Interpolating with the frame's own
 * fraction of a tick - the partial tick - starts every step wherever the packet happened to land, so the
 * figure skips forward on one frame and crawls on the next, a twitch that comes and goes with the
 * connection. Measuring the step from the clock reading the sample arrived at gives every step the same
 * length instead.
 */
public final class PreviewEntityStep {
    private List<CompoundTag> samples = List.of();
    private double sampleClock = Double.NaN;

    /**
     * Takes note of a sample list and answers whether it is a new one. A room whose blocks changed hands
     * its entities over again unchanged; moving them onto a sample they already stand on would restart a
     * step that has not finished running.
     */
    public boolean note(List<CompoundTag> samples, double clock) {
        if (samples.equals(this.samples)) {
            return false;
        }
        this.samples = List.copyOf(samples);
        this.sampleClock = clock;
        return true;
    }

    /** How far the render has got through the step the newest sample described: 0 at the sample, 1 a tick on. */
    public float fraction(double clock) {
        if (Double.isNaN(sampleClock)) {
            return 1.0F;
        }
        return (float) Mth.clamp(clock - sampleClock, 0.0D, 1.0D);
    }

    /**
     * Moves an entity that is already drawn onto a newer sample. {@code Entity.load} would leave the figure
     * standing exactly on the sample: it puts the position, the rotation and the tick counter in line with
     * it and calls {@code setOldPosAndRot}. The state a real entity has between two ticks is put back by
     * hand instead - the position and rotation the last sample left it with, which is what the renderer
     * draws the step from. The tick counter still moves on, because nothing ticks these entities and a
     * counter stuck at zero would restart whatever is animated with it.
     */
    public static void applySample(Entity entity, CompoundTag tag) {
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        float yRot = entity.getYRot();
        float xRot = entity.getXRot();
        LivingEntity living = entity instanceof LivingEntity candidate ? candidate : null;
        float yBodyRot = living == null ? 0.0F : living.yBodyRot;
        float yHeadRot = living == null ? 0.0F : living.yHeadRot;

        entity.load(tag);

        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
        entity.yRotO = yRot;
        entity.xRotO = xRot;
        if (living != null) {
            living.yBodyRotO = yBodyRot;
            living.yHeadRotO = yHeadRot;
            // An armor stand turns its body inside its own tick instead of in load - its setYBodyRot
            // writes the old value and the head, not the body - so a figure that never ticks would keep
            // the yaw it was built with. Nothing turns these figures but the sample.
            living.yBodyRot = entity.getYRot();
        }
        entity.tickCount++;
    }
}
