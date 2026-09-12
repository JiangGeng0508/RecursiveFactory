package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class RecursiveFactoryRenderer implements BlockEntityRenderer<RecursiveFactoryBlockEntity> {
    private static final float PREVIEW_SIZE = 0.9F;
    private static final Map<RecursiveFactoryBlockEntity, Long> LAST_REQUEST_TICKS = new WeakHashMap<>();

    public RecursiveFactoryRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RecursiveFactoryBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        List<EndpointBlockEntity.PreviewBlock> previewBlocks = blockEntity.getPreviewBlocks();
        if (blockEntity.getLevel() == null) {
            return;
        }
        if (previewBlocks.isEmpty()) {
            long gameTime = blockEntity.getLevel().getGameTime();
            Long lastRequestTick = LAST_REQUEST_TICKS.get(blockEntity);
            if (lastRequestTick == null || gameTime - lastRequestTick >= 20L) {
                LAST_REQUEST_TICKS.put(blockEntity, gameTime);
                PacketDistributor.sendToServer(new EndpointPreviewPackets.Request(blockEntity.getBlockPos()));
            }
            return;
        }
        LAST_REQUEST_TICKS.remove(blockEntity);

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (EndpointBlockEntity.PreviewBlock previewBlock : previewBlocks) {
            minX = Math.min(minX, previewBlock.x());
            minY = Math.min(minY, previewBlock.y());
            minZ = Math.min(minZ, previewBlock.z());
            maxX = Math.max(maxX, previewBlock.x());
            maxY = Math.max(maxY, previewBlock.y());
            maxZ = Math.max(maxZ, previewBlock.z());
        }

        AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        float scale = (float) (PREVIEW_SIZE / Math.max(
                bounds.getXsize(),
                Math.max(bounds.getYsize(), bounds.getZsize())
        ));

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.55D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - camera.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-bounds.getCenter().x, -bounds.minY, -bounds.getCenter().z);
        for (EndpointBlockEntity.PreviewBlock previewBlock : previewBlocks) {
            poseStack.pushPose();
            poseStack.translate(previewBlock.x(), previewBlock.y(), previewBlock.z());
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    previewBlock.state(),
                    poseStack,
                    bufferSource,
                    LightTexture.FULL_BLOCK,
                    packedOverlay
            );
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
