package com.zinzinc.recursivefactory.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings shared with master that do not replace the portal room geometry. */
public final class FactoryConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue REPAIR_BROKEN_FLOOR = BUILDER
            .comment("Fill holes in an existing factory floor when preparing its room.",
                    "The default is false: floors are generated once and player edits are retained.",
                    "Only missing blocks are repaired; replacement blocks are never overwritten.")
            .define("room.repairBrokenFloor", false);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private FactoryConfig() { }

    public static boolean repairBrokenFloor() {
        return REPAIR_BROKEN_FLOOR.get();
    }
}
