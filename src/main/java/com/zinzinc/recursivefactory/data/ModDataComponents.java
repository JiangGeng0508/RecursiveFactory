package com.zinzinc.recursivefactory.data;

import com.mojang.serialization.Codec;
import com.zinzinc.recursivefactory.RecursiveFactory;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The components the mod's items carry.
 *
 * <p>{@link #COLOR} is how an entrance block item says which of the sixteen colour kinds it was made
 * with, so one item covers every colour the recipes can ask for. The value is an index into the dye
 * colours - the same sixteen the concrete the recipe takes comes in - and a stack without one falls back
 * to the colour of the factory it is placed into, or to the hash of a new factory's id.
 *
 * <p>{@link #ROOM} and {@link #BLUEPRINT} remain for legacy mirror factory items. New print jobs are
 * saved on Create's Schematicannon block entity.
 */
public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, RecursiveFactory.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COLOR =
            COMPONENTS.register("color", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    /** The name of the blueprint file this stack stands for, inside the world's blueprint folder. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BLUEPRINT =
            COMPONENTS.register("blueprint", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    /** The room referenced by a legacy mirror factory item. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ROOM =
            COMPONENTS.register("room", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
