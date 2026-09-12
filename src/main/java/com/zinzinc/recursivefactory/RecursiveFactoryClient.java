package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.render.MirrorFactoryRenderer;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@Mod(value = RecursiveFactory.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = RecursiveFactory.MODID, value = Dist.CLIENT)
public final class RecursiveFactoryClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RECURSIVE_FACTORY.get(), RecursiveFactoryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MIRROR_FACTORY.get(), MirrorFactoryRenderer::new);
    }
}
