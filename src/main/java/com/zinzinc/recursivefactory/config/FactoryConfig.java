package com.zinzinc.recursivefactory.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's config file, {@code config/recursivefactory-common.toml}.
 *
 * <p>It is registered from the mod constructor as a common config, so its values are read on both
 * sides. Every getter falls back to the setting's default when the file has not been loaded yet,
 * which keeps a room that is built before the config is read (a server start, say) from failing.
 */
public final class FactoryConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue REPAIR_BROKEN_FLOOR = BUILDER
            .comment(
                    "Whether the blocks missing from a room's checkerboard floor are put back.",
                    "Off (the default): a room cell's floor is laid once, when the cell is first",
                    "built, and never looked at again, so a hole a player digs stays a hole.",
                    "On: every look at a room - a player entering it, or the server starting - fills",
                    "the missing floor blocks back in. Only the floor blocks that are gone are put",
                    "back; anything else standing on the floor layer is left alone."
            )
            .define("room.repairBrokenFloor", false);

    public static final ModConfigSpec.IntValue PRINTER_DELAY = BUILDER
            .comment(
                    "How many ticks the factory printer waits between the blocks it places.",
                    "Ten ticks per block is what a Create schematicannon does, and it is what lets a",
                    "print be fed as it goes: the printer asks the containers around it for one block's",
                    "worth of items at a time rather than for the whole blueprint up front.",
                    "A room is 16 by 16 by 13 blocks, so a full one takes about half an hour."
            )
            .defineInRange("printer.delay", 10, 0, 200);

    public static final ModConfigSpec.IntValue PRINTER_SHOTS_PER_SUGAR = BUILDER
            .comment(
                    "How many blocks one sugar is worth to the factory printer.",
                    "Four hundred is what a Create schematicannon gets out of one gunpowder, and it is",
                    "what one sugar gets out of a printer."
            )
            .defineInRange("printer.shotsPerSugar", 400, 1, 100000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private FactoryConfig() {
    }

    /** True when a room's floor should be patched back in each time the room is looked at. */
    public static boolean repairBrokenFloor() {
        return SPEC.isLoaded() && REPAIR_BROKEN_FLOOR.getAsBoolean();
    }

    /** Ticks the factory printer waits between two blocks of a print. */
    public static int printerDelay() {
        return SPEC.isLoaded() ? PRINTER_DELAY.getAsInt() : PRINTER_DELAY.getDefault();
    }

    /** How many blocks one sugar feeds the factory printer. */
    public static int printerShotsPerSugar() {
        return SPEC.isLoaded() ? PRINTER_SHOTS_PER_SUGAR.getAsInt() : PRINTER_SHOTS_PER_SUGAR.getDefault();
    }
}
