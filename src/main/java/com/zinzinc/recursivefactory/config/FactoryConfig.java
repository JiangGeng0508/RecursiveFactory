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

    public static final ModConfigSpec.BooleanValue POWER_LINK_ENABLED = BUILDER
            .comment(
                    "Whether the two ends of a factory face's electrical link are tied together.",
                    "On (the default): a factory_power_terminal against the entrance block outside and",
                    "one against the room's wall inside are one connection, so the room's grid and the",
                    "grid outside feed each other. Off: every terminal is left to itself, as a plain",
                    "terminal the mod does nothing with. Only matters when Create: Electro Energetics",
                    "is installed."
            )
            .define("power.linkEnabled", true);

    public static final ModConfigSpec.DoubleValue POWER_LINK_RESISTANCE = BUILDER
            .comment(
                    "The series resistance of each end of an electrical link, in ohms. It is also the",
                    "resistance the link itself shows the player's meters, and the two ends together are",
                    "one wire of twice this value.",
                    "Keep it comfortably above the resistance of the grids being joined, or the link will",
                    "overpower the very grids it is reading: an end measures its grid through this",
                    "resistance, so one that is small next to the grid's own (a ground rod is one ohm)",
                    "lets the end hold the grid at its own setpoint and then read that back as the grid's",
                    "voltage. The link then misses the voltage standing on the far side, drags the grid",
                    "down to nothing, and pushes whatever current the two can agree on through the",
                    "player's wires until they give out. Ten ohms is ten times a ground rod, and near",
                    "enough to an ideal connection for a room's worth of machines."
            )
            .defineInRange("power.linkResistance", 10.0D, 1.0E-6D, 100.0D);

    public static final ModConfigSpec.DoubleValue POWER_LINK_RESPONSE = BUILDER
            .comment(
                    "How much of the way an electrical link moves towards its new setpoint each tick.",
                    "One would snap straight to it; a fraction lets the two ends settle instead of",
                    "ringing against each other."
            )
            .defineInRange("power.linkResponse", 0.5D, 0.01D, 1.0D);

    public static final ModConfigSpec.DoubleValue POWER_LINK_MAX_CURRENT = BUILDER
            .comment(
                    "The most current, in amperes, one end of an electrical link will ask of a grid",
                    "through its own resistance: an end never drives its setpoint more than this much",
                    "times power.linkResistance away from the voltage it last measured, which is the",
                    "same as never asking for more than this much current.",
                    "Lowering power.linkResistance tightens that band along with it, since the two",
                    "together are one voltage."
            )
            .defineInRange("power.linkMaxCurrent", 1000.0D, 1.0D, 1.0E7D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** True when the two ends of a factory face's electrical link are tied together. */
    public static boolean powerLinkEnabled() {
        return !SPEC.isLoaded() || POWER_LINK_ENABLED.getAsBoolean();
    }

    /** The series resistance of each end of an electrical link, in ohms. */
    public static double powerLinkResistance() {
        return SPEC.isLoaded() ? POWER_LINK_RESISTANCE.getAsDouble() : POWER_LINK_RESISTANCE.getDefault();
    }

    /** How much of the way an electrical link moves towards its setpoint each tick. */
    public static double powerLinkResponse() {
        return SPEC.isLoaded() ? POWER_LINK_RESPONSE.getAsDouble() : POWER_LINK_RESPONSE.getDefault();
    }

    /** The most current one end of an electrical link will push through itself, in amperes. */
    public static double powerLinkMaxCurrent() {
        return SPEC.isLoaded() ? POWER_LINK_MAX_CURRENT.getAsDouble() : POWER_LINK_MAX_CURRENT.getDefault();
    }

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
