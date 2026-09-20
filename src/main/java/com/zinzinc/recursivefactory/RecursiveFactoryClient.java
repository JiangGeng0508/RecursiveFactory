package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import com.zinzinc.recursivefactory.data.FactoryColors;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@Mod(value = RecursiveFactory.MODID, dist = Dist.CLIENT)
public final class RecursiveFactoryClient {
    public RecursiveFactoryClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerBlockColors);
        modEventBus.addListener(this::registerItemColors);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RECURSIVE_FACTORY.get(), RecursiveFactoryRenderer::new);
    }

    /**
     * Both blocks are flat white shapes tinted per factory, so a factory's shell and the pedestal of its
     * entrance block (the one the preview sits on) always come out the same colour.
     */
    private void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> FactoryColors.at(level, pos),
                ModBlocks.FACTORY_BARRIER.get(),
                ModBlocks.RECURSIVE_FACTORY.get());
    }

    /** The item is tinted with the colour kind it carries, which is what tells the sixteen apart. */
    private void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> FactoryColors.ofStack(stack),
                ModBlocks.FACTORY_BARRIER_ITEM.get(),
                ModBlocks.RECURSIVE_FACTORY_ITEM.get());
    }
}
