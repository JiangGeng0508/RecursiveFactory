package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.render.MirrorFactoryRenderer;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import com.zinzinc.recursivefactory.world.FactoryPortalEntity;
import com.zinzinc.recursivefactory.world.ModEntities;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;

@Mod(value = RecursiveFactory.MODID, dist = Dist.CLIENT)
public final class RecursiveFactoryClient {
    public RecursiveFactoryClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.RECURSIVE_FACTORY.get(), RecursiveFactoryRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MIRROR_FACTORY.get(), MirrorFactoryRenderer::new);
        // Immersive Portals draws portals through its own renderer, and a custom portal entity type still has
        // to be pointed at it or the planes are never drawn. The cast is safe: the renderer takes any Portal.
        event.registerEntityRenderer(
                ModEntities.factoryPortal(),
                context -> (EntityRenderer<FactoryPortalEntity>) (Object) new PortalEntityRenderer(context)
        );
    }
}
