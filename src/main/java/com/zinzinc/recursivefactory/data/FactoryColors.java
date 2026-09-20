package com.zinzinc.recursivefactory.data;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * The colour a factory is painted with. Everything a factory is made of is drawn as a flat white shape and
 * tinted here: the barrier shell of its room and the plinth of its entrance blocks, so the block the
 * preview sits on comes out the same colour as the walls the room is made of.
 *
 * <p>A colour is one of the sixteen dye colours - the same sixteen the concrete an entrance block is
 * crafted from comes in - and is stored as an index into them, which is small enough to sit in a component
 * on the item ({@link ModDataComponents#COLOR}), in the factory's record, and on every block of the
 * factory, where it is what the client tints with.
 *
 * <p>A factory that was never given a colour of its own - one from a save older than the choice, or one
 * started by a block placed without it - is painted with a hash of its id instead. The id is already on
 * every block and synced to the client, and hashing keeps such a factory's colour stable across a reload
 * without having had to store a colour anywhere.
 */
public final class FactoryColors {
    /** How many colour kinds there are, one per dye colour. */
    public static final int COLOR_COUNT = DyeColor.values().length;
    /** A block, stack or factory with no colour of its own, which falls back to the hash of its id. */
    public static final int NO_COLOR = -1;
    /** For a block whose factory is not known on this side: the plain item, or one that just appeared. */
    public static final int UNKNOWN = 0xB0B4B8;

    private FactoryColors() {
    }

    /** The colour of the kind at {@code colorIndex}; anything out of range is read as white. */
    public static int byIndex(int colorIndex) {
        return DyeColor.byId(colorIndex).getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** The colour of the factory with this id, worked out from the id itself. */
    public static int of(int factoryId) {
        return byIndex(indexOf(factoryId));
    }

    /** Which kind a factory that has no colour of its own is painted: a stable hash of its id. */
    public static int indexOf(int factoryId) {
        int hash = factoryId * 0x9E3779B1;
        hash ^= hash >>> 16;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        return Math.floorMod(hash, COLOR_COUNT);
    }

    /** The name of a kind, which is what the sixteen items in the creative tab are called. */
    public static Component nameOf(int colorIndex) {
        return Component.translatable("color.minecraft." + DyeColor.byId(colorIndex).getName());
    }

    /** The kind a stack carries, or {@link #NO_COLOR} for a stack that carries none. */
    public static int colorOf(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.COLOR.get(), NO_COLOR);
    }

    /** The colour a stack is drawn in: the one it carries, or the plain colour for a stack without one. */
    public static int ofStack(ItemStack stack) {
        int colorIndex = colorOf(stack);
        return colorIndex == NO_COLOR ? UNKNOWN : byIndex(colorIndex);
    }

    /** The colour of the endpoint at {@code pos} - barrier or entrance block. */
    public static int at(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        if (level != null && pos != null
                && level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint) {
            if (endpoint.hasColorIndex()) {
                return byIndex(endpoint.getColorIndex());
            }
            if (endpoint.hasFactoryId()) {
                return of(endpoint.getFactoryId());
            }
        }
        return UNKNOWN;
    }
}