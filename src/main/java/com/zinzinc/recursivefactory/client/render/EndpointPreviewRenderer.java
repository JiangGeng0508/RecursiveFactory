package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zinzinc.recursivefactory.block.entity.FactoryPreviewSource;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws an endpoint block's face preview: the sampled blocks of the far side, scaled down and laid on
 * the block's surface. Shared by every endpoint block, so the block entity renderers stay thin.
 */
public final class EndpointPreviewRenderer {
    /**
     * One room cell is drawn one block across, so a preview fills the block it stands for. The sample is a
     * whole room - sixteen blocks across and as many tall - centred on the cell, so this mapping puts the
     * room's shell box exactly on the block's own cube: the shell's layers and columns land on the outer
     * sixteenth of every direction, which is where the block's frame stands, and the free space of the
     * room fills the opening.
     */
    private static final float PREVIEW_SCALE = 1.0F / FactoryData.CELL_SIZE;
    /**
     * The room's shell box and the block's cube are the same size and share their middle, so the sample
     * needs no offset at all: base layer on the block's bottom face, ceiling under its top one.
     */
    private static final float PREVIEW_Y_OFFSET = 0.0F;
    /**
     * How often the client asks again when nothing was pushed to it. The server pushes every change, so
     * this is only a safety net for a broadcast that was missed while the block's chunk was unloaded.
     */
    private static final int REQUEST_INTERVAL_TICKS = 40;
    private static final Map<FactoryPreviewSource, CachedProjection> PROJECTION_CACHE = new WeakHashMap<>();
    private static final Map<FactoryPreviewSource, Long> LAST_REQUEST_TICKS = new WeakHashMap<>();
    private static final Map<FactoryPreviewSource, PhysicsPreviewRenderer> PHYSICS_CACHE = new WeakHashMap<>();
    private static final Map<FactoryPreviewSource, RopePreviewRenderer> ROPE_CACHE = new WeakHashMap<>();

    private EndpointPreviewRenderer() {
    }

    /**
     * Asks the server for a fresh snapshot on a slow interval, so outside changes reach clients even
     * when a broadcast was missed, for example while the block's chunk was unloaded.
     */
    public static void requestPreview(FactoryPreviewSource blockEntity) {
        Level level = blockEntity.getLevel();
        // A miniature's virtual world uses local coordinates, not addresses in the player's level.
        if (!(level instanceof ClientLevel)) {
            LAST_REQUEST_TICKS.remove(blockEntity);
            return;
        }
        long gameTime = level.getGameTime();
        Long lastRequest = LAST_REQUEST_TICKS.get(blockEntity);
        if (lastRequest != null && gameTime - lastRequest < REQUEST_INTERVAL_TICKS) {
            return;
        }
        LAST_REQUEST_TICKS.put(blockEntity, gameTime);
        PacketDistributor.sendToServer(new EndpointPreviewPackets.Request(blockEntity.getBlockPos()));
    }

    public static void renderPreview(FactoryPreviewSource blockEntity, PoseStack poseStack,
                                     MultiBufferSource bufferSource, float partialTick) {
        double clock = FactoryProjectionCache.renderClock(blockEntity.getLevel(), partialTick);
        FactoryProjectionCache cache = getProjectionCache(blockEntity, clock);
        if (cache == null && blockEntity.getPreviewBodies().isEmpty() && blockEntity.getPreviewRopes().isEmpty()) {
            PHYSICS_CACHE.remove(blockEntity);
            ROPE_CACHE.remove(blockEntity);
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5D, PREVIEW_Y_OFFSET, 0.5D);
        poseStack.scale(PREVIEW_SCALE, PREVIEW_SCALE, PREVIEW_SCALE);
        // Sample coordinates are offsets from the middle of the room, so local (0, 0, 0) is the middle of
        // the base layer and the preview comes out centred on the block however lopsided the room is.
        if (cache != null) {
            cache.render(poseStack, bufferSource);
            cache.renderBlockEntities(poseStack, bufferSource, partialTick);
            cache.renderEntities(poseStack, bufferSource, clock);
        }
        if (blockEntity.getPreviewBodies().isEmpty()) PHYSICS_CACHE.remove(blockEntity);
        else PHYSICS_CACHE.computeIfAbsent(blockEntity, ignored -> new PhysicsPreviewRenderer()).render(
                blockEntity.getLevel(), blockEntity.getPreviewBodies(), poseStack, bufferSource, partialTick, clock);
        if (blockEntity.getPreviewRopes().isEmpty()) ROPE_CACHE.remove(blockEntity);
        else ROPE_CACHE.computeIfAbsent(blockEntity, ignored -> new RopePreviewRenderer()).render(
                blockEntity.getPreviewRopes(), poseStack, bufferSource, clock);
        poseStack.popPose();
    }

    private static FactoryProjectionCache getProjectionCache(FactoryPreviewSource blockEntity, double clock) {
        if (blockEntity.getLevel() == null
                || blockEntity.getPreviewBlocks().isEmpty()
                        && blockEntity.getPreviewEntities().isEmpty()
                        && blockEntity.getPreviewBlockEntities().isEmpty()
                        && blockEntity.getPreviewWires().isEmpty()) {
            PROJECTION_CACHE.remove(blockEntity);
            return null;
        }

        // The blocks are drawn again only when they change. Entities are handed the new sample instead, see
        // FactoryProjectionCache#updateEntities - building them again every tick is what made them twitch.
        int blockHash = 31 * blockEntity.getPreviewBlocks().hashCode()
                + blockEntityGeometryHash(blockEntity);
        blockHash = 31 * blockHash + blockEntity.getPreviewWires().hashCode();
        int entityHash = blockEntity.getPreviewEntities().hashCode();
        int blockEntityHash = blockEntity.getPreviewBlockEntities().hashCode();
        CachedProjection cached = PROJECTION_CACHE.get(blockEntity);
        if (cached != null && cached.blockHash() == blockHash) {
            if (cached.blockEntityHash() != blockEntityHash) {
                cached.cache().updateBeltInventories(blockEntity.getPreviewBlockEntities());
            }
            if (cached.entityHash() != entityHash) {
                cached.cache().updateEntities(blockEntity.getPreviewEntities(), clock);
            }
            if (cached.entityHash() != entityHash || cached.blockEntityHash() != blockEntityHash) {
                PROJECTION_CACHE.put(blockEntity, new CachedProjection(
                        blockHash, entityHash, blockEntityHash, cached.entities(), cached.cache()
                ));
            }
            return cached.cache();
        }

        FactoryProjectionCache.EntityStore entities = cached == null
                ? new FactoryProjectionCache.EntityStore()
                : cached.entities();
        FactoryProjectionCache rebuilt = new FactoryProjectionCache(
                blockEntity.getLevel(),
                blockEntity.getPreviewBlocks(),
                blockEntity.getPreviewEntities(),
                blockEntity.getPreviewBlockEntities(),
                blockEntity.getPreviewWires(),
                entities,
                clock,
                new AABB(-FactoryData.CELL_SIZE / 2.0, 0, -FactoryData.CELL_SIZE / 2.0,
                        FactoryData.CELL_SIZE / 2.0, FactoryData.ROOM_HEIGHT, FactoryData.CELL_SIZE / 2.0)
        );
        PROJECTION_CACHE.put(blockEntity, new CachedProjection(blockHash, entityHash, blockEntityHash, entities, rebuilt));
        return rebuilt;
    }

    private static int blockEntityGeometryHash(FactoryPreviewSource endpoint) {
        int hash = 1;
        for (CompoundTag sample : endpoint.getPreviewBlockEntities()) {
            CompoundTag geometry = sample;
            if (sample.getString("id").equals("create:belt")) {
                geometry = sample.copy();
                // Moving items change every tick, but do not change the room's static mesh.
                geometry.remove("Inventory");
            }
            hash = 31 * hash + geometry.hashCode();
        }
        return hash;
    }

    private record CachedProjection(int blockHash, int entityHash, int blockEntityHash,
                                    FactoryProjectionCache.EntityStore entities,
                                    FactoryProjectionCache cache) {
    }
}
