package com.zinzinc.recursivefactory;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.client.FactoryRoomRenderRange;
import com.zinzinc.recursivefactory.client.render.FactoryDimensionEffects;
import com.zinzinc.recursivefactory.client.render.MirrorFactoryRenderer;
import com.zinzinc.recursivefactory.client.render.RecursiveFactoryRenderer;
import com.zinzinc.recursivefactory.world.FactoryDimension;
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
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
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

    /**
     * Logs why the doorways around the camera are or are not being drawn, but only when that answer changes.
     * Off once the "the doorway picture blinks while walking next to it" question is settled.
     */
    private static final boolean DEBUG_PORTAL_FLICKER = true;
    private static final int DEBUG_PORTAL_FLICKER_INTERVAL = 2;
    /** How close a doorway has to be to be part of that report, in blocks. */
    private static final double NEAR_DOORWAY_RADIUS = 4.0D;
    private static int debugPortalFlickerTimer;
    private static String lastDoorwayDrawState;

    public RecursiveFactoryClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerDimensionEffects);
        modEventBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    /**
     * Gives the factory dimension its own sky effects, which is how the room loses its clouds. Immersive
     * Portals cannot hide them on its own - see {@link FactoryDimensionEffects}.
     */
    private void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(FactoryDimension.DIMENSION_TYPE_ID, new FactoryDimensionEffects());
        // Logged so that a room that still shows clouds can be traced: this line means the effects are in
        // place and the JSON resolved to them, so anything still drawn above the room came from elsewhere.
        RecursiveFactory.LOGGER.info(
                "Registered the factory room's sky effects under {} (cloud height NaN, no clouds)",
                FactoryDimension.DIMENSION_TYPE_ID
        );
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

        // Immersive Portals only recomputes this from MixinMinecraft's snooper hook, which in practice never
        // runs, so the level stays at its static default of medium for a whole session. VisibleSectionDiscovery
        // passes it through getPortalRenderingDistance, and medium halves the view distance of a portal's inner
        // world; for a scaled doorway, whose virtual camera sits scaling times further into the room than the
        // player stands from the entrance, that drops the room out of view about sixteen blocks away.
        if (ClientPerformanceMonitor.level != PerformanceLevel.good) {
            RecursiveFactory.LOGGER.info(
                    "Immersive Portals client performance level was {}; forcing good so the portal world's view "
                            + "distance is not halved", ClientPerformanceMonitor.level
            );
            ClientPerformanceMonitor.level = PerformanceLevel.good;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level != null && DEBUG_PORTAL_FLICKER
                && ++debugPortalFlickerTimer >= DEBUG_PORTAL_FLICKER_INTERVAL) {
            debugPortalFlickerTimer = 0;
            logDoorwayDrawState(client, client.level);
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

        // PortalRenderer.getPortalRenderDistance is private, so the same sum is repeated here, and then fed to
        // the performance level the way VisibleSectionDiscovery does: the result is how far the inner world is
        // rendered, and for a scaled doorway that is measured from a virtual camera pushed into the room.
        double estimate = nearest.getDestAreaRadiusEstimation();
        int renderDistanceChunks = minecraft.options.getEffectiveRenderDistance();
        int portalRenderDistance = nearest.getScale() > 2.0
                ? Math.max((int) (Math.min(estimate * 1.4, 512.0) / 16.0), renderDistanceChunks)
                : (IPGlobal.reducedPortalRendering ? renderDistanceChunks / 3 : renderDistanceChunks);
        int innerViewDistance = PerformanceLevel.getPortalRenderingDistance(
                ClientPerformanceMonitor.level, portalRenderDistance
        );

        RecursiveFactory.LOGGER.info(
                "Portal view: nearest doorway {} is {} blocks away, scaling={}, fuseView={}, valid={}, "
                        + "visible={}, roughlyVisible={}",
                nearest.portalTag, String.format("%.1f", nearestDistance), nearest.getScale(),
                nearest.isFuseView(), nearest.isPortalValid(), nearest.isVisible(),
                nearest.isRoughlyVisibleTo(camera)
        );
        RecursiveFactory.LOGGER.info(
                "Portal view: inner world is rendered {} chunks out (portal render distance {} chunks, estimate "
                        + "{}, level {}); the virtual camera sits {} blocks from the room; the client holds {}/9 "
                        + "room chunks and {}/5 room probes are solid; portal layer={}",
                innerViewDistance, portalRenderDistance, String.format("%.0f", estimate),
                ClientPerformanceMonitor.level, String.format("%.1f", nearest.transformPoint(camera)
                        .distanceTo(nearest.getDestPos())),
                countLoadedChunks(destLevel, destPos), countSolidProbes(destLevel, destPos),
                PortalRendering.getPortalLayer()
        );
        // Only the doorway is scaled by 16, so only its pass gets the wider radius; a doorway is also the
        // direction the room disappears in, because 16 x distance is what pushes the room out of reach.
        if (nearest.getScale() > 2.0D) {
            RecursiveFactory.LOGGER.info(
                    "Portal view: the room is drawn out to {} chunks because of this mod's mixin (Immersive "
                            + "Portals on its own allows {}); the mixin last wrote {} (-1 = it never ran, so "
                            + "the distance is still Immersive Portals' own)",
                    Math.max(innerViewDistance, FactoryRoomRenderRange.CHUNKS), innerViewDistance,
                    FactoryRoomRenderRange.lastApplied
            );
        }
    }

    /**
     * Reports, once per change, whether the doorways near the camera are being drawn and why not.
     *
     * <p>Immersive Portals decides that from the camera position alone and with no hysteresis:
     * PortalRenderer.shouldSkipRenderingPortal skips any portal whose isRoughlyVisibleTo(camera) is false,
     * and for a rectangular plane that test is nothing but "the camera is strictly on the front side"
     * (RectangularPortalShape.roughTestVisibility returns local z &gt; 0). Our planes sit a thousandth of a
     * block outside the block's faces, and the entrance block's own volume is walk-in able - its collision
     * is a low rim rather than a cube - so the camera crosses that boundary whenever someone stands in a
     * doorway or walks past the edge of a block, and the picture blinks out for as long as it is on the
     * wrong side. The signed distance to the plane is logged next to the flags, so this can be told apart
     * from a portal being culled for distance, from the portal limit being reached, or from the room's
     * chunks going missing.
     */
    private static void logDoorwayDrawState(Minecraft minecraft, ClientLevel level) {
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int near = 0;
        int nearFront = 0;
        FactoryPortalEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof FactoryPortalEntity portal)) {
                continue;
            }
            double distance = portal.getDistanceToNearestPointInPortal(camera);
            if (distance > NEAR_DOORWAY_RADIUS) {
                continue;
            }
            near++;
            if (portal.isRoughlyVisibleTo(camera)) {
                nearFront++;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = portal;
            }
        }
        if (nearest == null) {
            if (lastDoorwayDrawState != null) {
                lastDoorwayDrawState = null;
                RecursiveFactory.LOGGER.info(
                        "Doorway draw state: nothing within {} blocks of the camera", NEAR_DOORWAY_RADIUS);
            }
            return;
        }

        // A plane's own frame runs its local z along the normal, so this is the value the rough test reads.
        double planeDistance = nearest.getNormal().dot(camera.subtract(nearest.getOriginPos()));
        boolean roughlyVisible = nearest.isRoughlyVisibleTo(camera);
        String state = near + "|" + nearFront + "|" + roughlyVisible + "|" + (planeDistance > 0.0D);
        if (state.equals(lastDoorwayDrawState)) {
            return;
        }
        lastDoorwayDrawState = state;

        RecursiveFactory.LOGGER.info(
                "Doorway draw state: {} of {} doorways within {} blocks face the camera; the nearest is {} "
                        + "blocks away with valid={}, visible={}, roughlyVisible={}, planeDistance={}, "
                        + "renderRange={}, portalsRendered={}/{}",
                nearFront, near, NEAR_DOORWAY_RADIUS, String.format("%.2f", nearestDistance),
                nearest.isPortalValid(), nearest.isVisible(), roughlyVisible,
                String.format("%+.3f", planeDistance), PortalRenderer.getRenderRange(),
                RenderStates.portalsRenderedThisFrame, IPGlobal.portalRenderLimit
        );
    }

    /** How many of the nine chunks around the destination the client actually holds. */
    private static int countLoadedChunks(ClientLevel level, BlockPos pos) {
        if (level == null) {
            return 0;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int found = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().hasChunk(chunkX + dx, chunkZ + dz)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** Samples the room itself, since a loaded chunk says nothing about what is inside it. */
    private static int countSolidProbes(ClientLevel level, BlockPos pos) {
        if (level == null) {
            return 0;
        }
        int found = 0;
        int[][] offsets = {{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}};
        for (int[] offset : offsets) {
            if (!level.getBlockState(pos.offset(offset[0], 0, offset[1])).isAir()) {
                found++;
            }
        }
        return found;
    }
}
