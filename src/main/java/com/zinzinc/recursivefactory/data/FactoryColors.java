package com.zinzinc.recursivefactory.data;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.data.FaceMode;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The colour a factory is painted with. Everything a factory is made of is drawn as a flat white shape and
 * tinted here: the barrier shell of its room and the plinth of its entrance blocks, so the block the
 * preview sits on comes out the same colour as the walls the room is made of.
 *
 * <p>A colour is one of the sixteen dye colours - the same sixteen the concrete an entrance block is
 * crafted from comes in - and is stored as an index into them, which is small enough to sit in a component
 * on the item ({@link ModDataComponents#COLOR}) and in the factory's record.
 *
 * <p>Every block of the factory also carries the kind it is drawn in on its <em>state</em>
 * ({@link #COLOR_PROPERTY}), which is what the client tints with. The state travels with the block itself,
 * so a block that has just been placed is drawn in the right colour from its first frame instead of
 * turning from the plain colour into the factory's once the block entity reaches the client. The property
 * holds the kind plus one, so that its default - which is what a block from a save older than this reads
 * as - means "no colour of its own yet" and falls back to the block entity, exactly as it used to.
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
    /**
     * The state property every factory block carries: the colour kind it is drawn in, as the kind plus one,
     * or 0 for a block that has no colour of its own and has to ask its factory - which is what a block
     * placed before the factory was known, and every block from a save older than this, reads as.
     */
    public static final IntegerProperty COLOR_PROPERTY = IntegerProperty.create("color", 0, COLOR_COUNT);
    /**
     * The first tint index the entrance block's model uses to ask about one of its faces: the index
     * {@code FACE_TINT_BASE + face.get3DDataValue()} is the outer side of that face of the frame. Tint
     * indexes below this one are the plain factory colour, which is what the barrier's model asks for on
     * every face of the shell.
     */
    public static final int FACE_TINT_BASE = 100;

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

    /**
     * The kind a factory is painted: the one it was given, or the one its id hashes to for a factory that
     * was never given one. Never {@link #NO_COLOR}, so a block of a known factory always has a colour on it.
     */
    public static int kindOfFactory(int colorIndex, int factoryId) {
        return colorIndex == NO_COLOR ? indexOf(factoryId) : colorIndex;
    }

    /** {@link #COLOR_PROPERTY}'s value for a colour kind: the kind plus one, or 0 for no colour of its own. */
    public static int stateValue(int colorIndex) {
        return colorIndex == NO_COLOR ? 0 : colorIndex + 1;
    }

    /** The colour kind a state is drawn in, or {@link #NO_COLOR} for one that still has to ask its factory. */
    public static int kindOfState(BlockState state) {
        int value = state.getValue(COLOR_PROPERTY);
        return value == 0 ? NO_COLOR : value - 1;
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

    /**
     * The colour the block at {@code pos} is drawn in - barrier or entrance block. The block's own state
     * answers for every block of a known factory; a block that has not been told a colour yet (one placed
     * before the factory was there, or one from an older save) falls back to its block entity, and a block
     * whose factory is not known on this side at all is drawn in the plain colour.
     */
    public static int at(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        int kind = state.hasProperty(COLOR_PROPERTY) ? kindOfState(state) : NO_COLOR;
        if (kind != NO_COLOR) {
            return byIndex(kind);
        }
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

    /**
     * What one face of a block is drawn in, which is the whole of what the six modes look like: a face that
     * carries something is drawn in that thing's colour, and a face that carries nothing - one left
     * transparent - is drawn in the colour of its factory, so a window does not stand out from the rest of
     * the block.
     *
     * <p>The mode is asked of the block entity, so this is only ever answered for a block that is there: a
     * model being baked for the inventory, or for a block whose entity has not arrived yet, falls back on
     * the factory's own colour.
     */
    public static int tint(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos,
                           int tintIndex) {
        int faceIndex = tintIndex - FACE_TINT_BASE;
        if (faceIndex >= 0 && faceIndex < Direction.values().length
                && level != null && pos != null
                && level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint) {
            FaceMode mode = endpoint.faceMode(Direction.from3DDataValue(faceIndex));
            if (mode.hasTint()) {
                return mode.tint();
            }
        }
        return at(state, level, pos);
    }
}
