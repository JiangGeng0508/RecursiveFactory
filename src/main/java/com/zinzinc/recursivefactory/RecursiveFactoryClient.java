package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import com.zinzinc.recursivefactory.client.render.FactoryFrameModel;
import com.zinzinc.recursivefactory.client.render.FactoryPreviewRenderer;
import com.zinzinc.recursivefactory.client.render.EndpointPreviewRenderer;
import com.zinzinc.recursivefactory.data.FactoryColors;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

@Mod(value = RecursiveFactory.MODID, dist = Dist.CLIENT)
public final class RecursiveFactoryClient {
    public RecursiveFactoryClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerBlockColors);
        modEventBus.addListener(this::registerItemColors);
        modEventBus.addListener(FactoryFrameModel::registerModels);
        modEventBus.addListener(FactoryFrameModel::wrapModels);
        modEventBus.addListener((ModelEvent.BakingCompleted event) -> EndpointPreviewRenderer.clearCaches());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) EndpointPreviewRenderer.clearCaches();
        });
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RECURSIVE_FACTORY.get(), RecursiveFactoryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FACTORY_PREVIEW.get(), FactoryPreviewRenderer::new);
    }

    /**
     * Both blocks are flat white shapes tinted per factory, so a factory's shell and the frame of its
     * entrance block always come out the same colour (see {@link FactoryColors#at}).
     */
    private void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> FactoryColors.at(state, level, pos),
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
