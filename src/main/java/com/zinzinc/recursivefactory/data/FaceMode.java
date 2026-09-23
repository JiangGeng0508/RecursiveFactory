package com.zinzinc.recursivefactory.data;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * What one face of a factory's entrance block is for.
 *
 * <p>A face is one channel: it carries exactly one kind of thing, and the six faces of an entrance block
 * are six channels that do not disturb each other. {@link #TRANSPARENT} is the face that carries nothing -
 * it is a window, and the room behind it is what you see - and each of the other four carries one thing and
 * nothing else.
 *
 * <p>A mode names a side of the <em>room</em>, not a direction of travel: the entrance block's north face is
 * the room's north side, and a thing crossing the link there goes in or out through whichever of the room's
 * north wall and the entrance block's north face the journey has left to make (see {@code FactoryRelay}).
 * That is what makes a face readable as "this side of the factory is the fluid side".
 *
 * <p>Every face of a factory that has just been placed is {@link #TRANSPARENT}, so a new factory connects
 * nothing at all until its faces are opened. The ordinal is what a block entity stores, so the order of
 * these constants is part of the save format.
 */
public enum FaceMode implements StringRepresentable {
    TRANSPARENT("transparent", -1),
    REDSTONE("redstone", 0xFF3C1E),
    LOGISTICS("logistics", 0xE08A22),
    FLUID("fluid", 0x2E7BE0),
    STRESS("stress", 0x35C25A);

    /** How many modes there are, and how many of them fit in the three bits a face takes up. */
    public static final int COUNT = values().length;
    /** The modes as a click counts through them, which is the order they are declared in. */
    private static final FaceMode[] ORDER = values();

    private final String name;
    private final int tint;

    FaceMode(String name, int tint) {
        this.name = name;
        this.tint = tint;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /**
     * The colour a face set to this mode is drawn in, or {@code -1} for {@link #TRANSPARENT}: a face with
     * nothing on it is drawn in the colour of the factory it belongs to, so it does not stand out from the
     * rest of the block.
     */
    public int tint() {
        return tint;
    }

    /** Whether this mode is drawn in a colour of its own rather than the factory's. */
    public boolean hasTint() {
        return tint >= 0;
    }

    /** The mode one click further on, so a face cycles through all five of them. */
    public FaceMode next() {
        return ORDER[(ordinal() + 1) % ORDER.length];
    }

    /** The mode a stored ordinal stands for; anything out of range reads as {@link #TRANSPARENT}. */
    public static FaceMode of(int ordinal) {
        return ordinal >= 0 && ordinal < ORDER.length ? ORDER[ordinal] : TRANSPARENT;
    }

    /** What this mode is called, which is what a face says when it is switched to it. */
    public Component displayName() {
        return Component.translatable("face_mode.recursivefactory." + name);
    }
}