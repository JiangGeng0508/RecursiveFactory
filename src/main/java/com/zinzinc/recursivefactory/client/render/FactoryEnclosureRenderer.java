package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryEnclosure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.slf4j.Logger;

/**
 * Draws the sampled outside world around the factory platform so that standing inside a factory
 * looks like standing inside the block, with the enlarged world wrapping around the player.
 */
@EventBusSubscriber(modid = RecursiveFactory.MODID, value = Dist.CLIENT)
public final class FactoryEnclosureRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float FOG_START = 90.0F;
    private static final float FOG_END = 140.0F;
    private static final long DRAW_LOG_INTERVAL_TICKS = 100L;

    private static long lastDrawLogTick = Long.MIN_VALUE;

    private FactoryEnclosureRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        ClientEnclosureCache.onLevelChanged(level);
        if (!isInsideFactory(level, player)) {
            return;
        }

        ChunkPos chunk = player.chunkPosition();
        FactoryProjectionCache cache = ClientEnclosureCache.get(level, chunk);
        if (cache == null) {
            ClientEnclosureCache.requestIfMissing(level, chunk);
            return;
        }

        long gameTime = level.getGameTime();
        if (lastDrawLogTick == Long.MIN_VALUE || gameTime - lastDrawLogTick >= DRAW_LOG_INTERVAL_TICKS) {
            lastDrawLogTick = gameTime;
            LOGGER.info(
                    "Drawing factory enclosure for chunk [{}, {}] at scale {} with {} blocks",
                    chunk.x,
                    chunk.z,
                    FactoryEnclosure.SCALE,
                    cache.getBlockCount()
            );
        }

        BlockPos origin = FactoryEnclosure.origin(chunk);        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(
                origin.getX() - camera.x,
                origin.getY() - camera.y,
                origin.getZ() - camera.z
        );
        poseStack.scale(FactoryEnclosure.SCALE, FactoryEnclosure.SCALE, FactoryEnclosure.SCALE);
        cache.render(poseStack, bufferSource);
        poseStack.popPose();
        bufferSource.endBatch();
    }

    /**
     * Keeps the far edge of the sampled shell out of sight by pulling the terrain fog close to the
     * player while standing on a factory platform.
     */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || !isInsideFactory(level, player)) {
            return;
        }
        if (!ClientEnclosureCache.has(player.chunkPosition())) {
            return;
        }

        event.setCanceled(true);
        event.setNearPlaneDistance(FOG_START);
        event.setFarPlaneDistance(FOG_END);
        event.setFogShape(FogShape.SPHERE);
    }

    private static boolean isInsideFactory(ClientLevel level, LocalPlayer player) {
        if (level.dimension() != FactoryDimension.LEVEL_KEY) {
            return false;
        }
        double y = player.getY();
        return y >= FactoryData.FLOOR_Y - 1.0D && y <= FactoryData.CEILING_Y + 1.0D;
    }
}