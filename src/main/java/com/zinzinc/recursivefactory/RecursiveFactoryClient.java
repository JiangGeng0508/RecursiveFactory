package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.render.MirrorFactoryRenderer;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import com.zinzinc.recursivefactory.world.FactoryPortalEntity;
import com.zinzinc.recursivefactory.world.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

@Mod(value = RecursiveFactory.MODID, dist = Dist.CLIENT)
public final class RecursiveFactoryClient {
    /**
     * Logs Immersive Portals' portal render range and lag state every {@link #DEBUG_PORTAL_RANGE_INTERVAL}
     * ticks. Off once the "doorway goes blank at a distance" question is settled.
     */
    private static final boolean DEBUG_PORTAL_RANGE = true;
    private static final int DEBUG_PORTAL_RANGE_INTERVAL = 100;
    private static int debugPortalRangeTimer;

    public RecursiveFactoryClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
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

    private void onClientSetup(FMLClientSetupEvent event) {
        // Immersive Portals' lag protection is a trap for a doorway that is meant to be looked at from across
        // the yard. RenderStates.updateIsLaggy() latches once a frame renders more than ten portals while the
        // average or minimum fps over the last twenty seconds drops below 8 / 6 - which a factory cluster of
        // forty-odd planes reaches on its own while its chunks are being built - and it only clears again at a
        // minimum of 15 fps. While it is latched, getRenderRange() is a flat 16 blocks and
        // PortalRenderer.shouldSkipRenderingPortal() skips every portal farther from the camera than that, so
        // the whole doorway stops being drawn about sixteen blocks out. maxPortalLayer also collapses to 1,
        // which kills the view through a factory inside a factory.
        //
        // This runs in client setup, after every mod's constructor has applied its own config, so it wins over
        // the lagAttackProof entry in immersive_portals.json. Set it back there (and drop this line) if the
        // frames actually do suffer: the price of turning it off is that nothing throttles portal rendering.
        IPGlobal.lagAttackProof = false;
        RecursiveFactory.LOGGER.info(
                "Immersive Portals lag protection disabled (lagAttackProof={}), portal render range is now "
                        + "the player's render distance instead of 16 blocks",
                IPGlobal.lagAttackProof
        );
    }

    private void onClientTick(ClientTickEvent.Post event) {
        // Immersive Portals re-applies its config when the config screen is used, which would put the lag
        // protection back and take the doorways away again, so the flag is re-asserted rather than set once.
        if (IPGlobal.lagAttackProof) {
            IPGlobal.lagAttackProof = false;
            RecursiveFactory.LOGGER.info("Immersive Portals lag protection turned off again after it was re-enabled");
        }

        if (!DEBUG_PORTAL_RANGE) {
            return;
        }
        if (++debugPortalRangeTimer < DEBUG_PORTAL_RANGE_INTERVAL) {
            return;
        }
        debugPortalRangeTimer = 0;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        RecursiveFactory.LOGGER.info(
                "Portal view: renderRange={} blocks, laggy={}, fps avg/min={}/{}, lagAttackProof={}, "
                        + "reducedPortalRendering={}, maxPortalLayer={}, portalsRendered={}/{}",
                PortalRenderer.getRenderRange(), RenderStates.isLaggy,
                ClientPerformanceMonitor.getAverageFps(), ClientPerformanceMonitor.getMinimumFps(),
                IPGlobal.lagAttackProof, IPGlobal.reducedPortalRendering, IPGlobal.maxPortalLayer,
                RenderStates.portalsRenderedThisFrame, IPGlobal.portalRenderLimit
        );

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        FactoryPortalEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof FactoryPortalEntity portal) {
                double distance = portal.getDistanceToNearestPointInPortal(camera);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = portal;
                }
            }
        }
        if (nearest == null) {
            RecursiveFactory.LOGGER.info("Portal view: no factory doorway on the client right now");
            return;
        }

        // The doorway is only drawn if the client holds the chunk behind it, so log that too: an unloaded
        // room and a skipped portal look identical from the outside.
        ResourceKey<Level> destDimension = nearest.getDestDim();
        BlockPos destPos = BlockPos.containing(nearest.getDestPos());
        ClientLevel destLevel = destDimension == null ? null : ClientWorldLoader.getWorld(destDimension);
        boolean destChunkLoaded = destLevel != null
                && destLevel.getChunkSource().hasChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
        RecursiveFactory.LOGGER.info(
                "Portal view: nearest doorway {} is {} blocks away, destination {} {} loaded on the client={}",
                nearest.portalTag, String.format("%.1f", nearestDistance), destDimension, destPos, destChunkLoaded
        );
    }
}
