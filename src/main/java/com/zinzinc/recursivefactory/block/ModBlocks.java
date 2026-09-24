package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.item.FactoryBlueprintItem;
import com.zinzinc.recursivefactory.item.MirrorFactoryItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    /**
     * What the entrance block answers when it is asked whether it is a solid block of stone. Vanilla sets
     * its own glass up with exactly these four ({@code Blocks::never}), and the frame is a window rather
     * than a block of stone, so it answers the same way.
     *
     * <p>The one that matters for redstone is {@code isRedstoneConductor}. A block that conducts is a
     * block the strong power around it passes through: {@code SignalGetter#getSignal} folds the strongest
     * direct signal of the block's neighbours into the block's own answer whenever
     * {@code shouldCheckWeakPower} says yes, and that answer is this flag
     * ({@code IBlockExtension#shouldCheckWeakPower}). A frame that conducts is a piece of wire - a lever
     * or a repeater lying against an entrance block would light up the dust behind it, and a signal set
     * from outside would reach the room around the relay instead of through it. The relay's own answer
     * ({@code FactoryRelay#emittedSignal}) is the block's own signal and does not go through any of this.
     */
    private static final BlockBehaviour.StatePredicate NEVER = (state, level, pos) -> false;
    private static final BlockBehaviour.StateArgumentPredicate<EntityType<?>> NEVER_SPAWNS =
            (state, level, pos, entity) -> false;

    private static final BlockBehaviour.Properties FACTORY_PROPERTIES = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 1200.0F)
            .sound(SoundType.METAL)
            .lightLevel(state -> 12)
            .noOcclusion()
            .isValidSpawn(NEVER_SPAWNS)
            .isRedstoneConductor(NEVER)
            .isSuffocating(NEVER)
            .isViewBlocking(NEVER)
            .pushReaction(PushReaction.BLOCK);

    private static final BlockBehaviour.Properties BARRIER_PROPERTIES = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0F, 1200.0F)
            .sound(SoundType.METAL)
            .pushReaction(PushReaction.BLOCK);

    private static final BlockBehaviour.Properties PRINTER_PROPERTIES = BlockBehaviour.Properties.of()
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

    public static final DeferredBlock<FactoryPrinterBlock> FACTORY_PRINTER = BLOCKS.register(
            "factory_printer",
            () -> new FactoryPrinterBlock(PRINTER_PROPERTIES)
    );

    public static final DeferredItem<RecursiveFactoryItem> RECURSIVE_FACTORY_ITEM = ITEMS.register(
            "recursive_factory",
            () -> new RecursiveFactoryItem(RECURSIVE_FACTORY.get(), new Item.Properties())
    );

    public static final DeferredItem<BlockItem> FACTORY_BARRIER_ITEM = ITEMS.registerSimpleBlockItem(
            "factory_barrier",
            FACTORY_BARRIER
    );

    /** The printer: one per stack, so the print it is carrying is never mixed up with another one's. */
    public static final DeferredItem<BlockItem> FACTORY_PRINTER_ITEM = ITEMS.registerSimpleBlockItem(
            "factory_printer",
            FACTORY_PRINTER,
            new Item.Properties().stacksTo(1)
    );

    /** The blueprint a player takes off a factory and loads into a printer. */
    public static final DeferredItem<FactoryBlueprintItem> FACTORY_BLUEPRINT_ITEM = ITEMS.register(
            "mirror_factory_blueprint",
            () -> new FactoryBlueprintItem(new Item.Properties().stacksTo(1))
    );

    /** What a printer hands out: the copy of a factory, waiting for its entrance block to be put down. */
    public static final DeferredItem<MirrorFactoryItem> MIRROR_FACTORY_ITEM = ITEMS.register(
            "mirror_factory",
            () -> new MirrorFactoryItem(new Item.Properties().stacksTo(1))
    );

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
