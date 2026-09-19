package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.RecursiveFactory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RecursiveFactory.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RecursiveFactory.MODID);

    private static final BlockBehaviour.Properties FACTORY_PROPERTIES = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 1200.0F)
            .sound(SoundType.METAL)
            .lightLevel(state -> 12)
            .noOcclusion()
            .pushReaction(PushReaction.BLOCK);

    private static final BlockBehaviour.Properties BARRIER_PROPERTIES = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 1200.0F)
            .sound(SoundType.METAL)
            .pushReaction(PushReaction.BLOCK);

    public static final DeferredBlock<RecursiveFactoryBlock> RECURSIVE_FACTORY = BLOCKS.register(
            "recursive_factory",
            () -> new RecursiveFactoryBlock(FACTORY_PROPERTIES)
    );

    public static final DeferredBlock<FactoryBarrierBlock> FACTORY_BARRIER = BLOCKS.register(
            "factory_barrier",
            () -> new FactoryBarrierBlock(BARRIER_PROPERTIES)
    );

    public static final DeferredItem<BlockItem> RECURSIVE_FACTORY_ITEM = ITEMS.registerSimpleBlockItem(
            "recursive_factory",
            RECURSIVE_FACTORY
    );

    public static final DeferredItem<BlockItem> FACTORY_BARRIER_ITEM = ITEMS.registerSimpleBlockItem(
            "factory_barrier",
            FACTORY_BARRIER
    );

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}