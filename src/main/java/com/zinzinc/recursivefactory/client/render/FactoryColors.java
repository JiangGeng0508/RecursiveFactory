package com.zinzinc.recursivefactory.client.render;

import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * The colour a factory is painted with. Barriers are drawn as a flat white cube and tinted here, so every
 * barrier of one factory - the ones a room starts with and the ones it picks up when it grows - comes out
 * the same colour, while the next factory gets a different one.
 *
 * <p>The colour is worked out from the factory id rather than stored: the id is already on every barrier
 * (and synced to the client), and hashing it keeps the colour of a factory stable across a reload without
 * having to save a colour anywhere.
 */
public final class FactoryColors {
    /** Colours far enough apart to tell two factories apart at a glance. */
    private static final int[] PALETTE = {
            0xD25B4B, 0xE0923C, 0xD9C05A, 0x8FBF5A,
            0x4F9E5C, 0x5AA9A0, 0x4F86C6, 0x6E6FC0,
            0x9A6BC0, 0xC05A96, 0xC06A6A, 0x8A7B62,
            0x6E8B99, 0xA8A8A8, 0xC97F4A, 0x7FA35A
    };
    /** For a barrier whose factory is not known on this side: the item form, or one that just appeared. */
    public static final int UNKNOWN = 0xB0B4B8;

    private FactoryColors() {
    }

    /** The colour of the factory the barrier at {@code pos} belongs to. */
    public static int at(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        if (level != null && pos != null
                && level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity barrier
                && barrier.hasFactoryId()) {
            return of(barrier.getFactoryId());
        }
        return UNKNOWN;
    }

    public static int of(int factoryId) {
        int hash = factoryId * 0x9E3779B1;
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }
}
