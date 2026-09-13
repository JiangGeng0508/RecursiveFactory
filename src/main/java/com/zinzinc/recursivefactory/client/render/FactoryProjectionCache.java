package com.zinzinc.recursivefactory.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.createmod.catnip.render.MutableTemplateMesh;
import net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer;
import net.createmod.catnip.render.ShadedBlockSbbBuilder;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class FactoryProjectionCache {
    private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS =
            ThreadLocal.withInitial(ThreadLocalObjects::new);
    private static final ByteBufferBuilder FLUID_BUFFER_BUILDER = new ByteBufferBuilder(1536);

    private final VirtualRenderWorld renderWorld;
    private final List<BlockPos> renderedPositions;
    private final List<BlockPos> fluidPositions = new ArrayList<>();
    private final Map<RenderType, SuperByteBuffer> bufferCache = new LinkedHashMap<>();
    private final Map<RenderType, SuperByteBuffer> fluidBufferCache = new LinkedHashMap<>();
    private final AABB bounds;

    public FactoryProjectionCache(Level level, List<EndpointBlockEntity.PreviewBlock> previewBlocks) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        renderedPositions = new ArrayList<>(previewBlocks.size());

        for (EndpointBlockEntity.PreviewBlock previewBlock : previewBlocks) {
            minX = Math.min(minX, previewBlock.x());
            minY = Math.min(minY, previewBlock.y());
            minZ = Math.min(minZ, previewBlock.z());
            maxX = Math.max(maxX, previewBlock.x());
            maxY = Math.max(maxY, previewBlock.y());
            maxZ = Math.max(maxZ, previewBlock.z());
        }

        int worldHeight = previewBlocks.isEmpty() ? 16 : Math.max(16, maxY + 2);
        renderWorld = new VirtualRenderWorld(level, 0, worldHeight, BlockPos.ZERO, () -> {
        }) {
            @Override
            public boolean supportsVisualization() {
                return false;
            }

            @Override
            public int getBrightness(LightLayer lightLayer, BlockPos pos) {
                return 15;
            }

            @Override
            public int getRawBrightness(BlockPos pos, int amount) {
                return 15;
            }
        };

        for (EndpointBlockEntity.PreviewBlock previewBlock : previewBlocks) {
            BlockPos localPos = new BlockPos(previewBlock.x(), previewBlock.y(), previewBlock.z());
            renderWorld.setBlock(localPos, previewBlock.state(), 0);
            renderedPositions.add(localPos);
            if (!previewBlock.state().getFluidState().isEmpty()) {
                fluidPositions.add(localPos);
            }
        }

        renderWorld.runLightEngine();
        bounds = previewBlocks.isEmpty()
                ? new AABB(BlockPos.ZERO)
                : new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        redraw();
    }

    public AABB getBounds() {
        return bounds;
    }

    public int getBlockCount() {
        return renderedPositions.size();
    }

    public float getMiniatureScale() {
        double maxSize = Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
        return (float) (0.85D / Math.max(1.0D, maxSize));
    }

    public void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        bufferCache.forEach((layer, buffer) -> buffer.renderInto(
                poseStack,
                bufferSource.getBuffer(layer)
        ));
        fluidBufferCache.forEach((layer, buffer) -> buffer.renderInto(
                poseStack,
                bufferSource.getBuffer(layer)
        ));
    }

    private void redraw() {
        bufferCache.clear();
        fluidBufferCache.clear();
        for (RenderType layer : RenderType.chunkBufferLayers()) {
            SuperByteBuffer buffer = drawLayer(layer);
            if (!buffer.isEmpty()) {
                bufferCache.put(layer, buffer);
            }
            SuperByteBuffer fluids = drawFluidLayer(layer);
            if (fluids != null) {
                fluidBufferCache.put(layer, fluids);
            }
        }
    }

    /**
     * Fluids are drawn by the vanilla fluid renderer instead of their block model, so water and lava
     * show up like they do in a real world. That renderer keeps only the lowest four bits of the
     * block position, the part it drops is put back by {@link BlockOffsetVertexConsumer}.
     */
    @Nullable
    private SuperByteBuffer drawFluidLayer(RenderType layer) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : fluidPositions) {
            FluidState fluidState = renderWorld.getBlockState(pos).getFluidState();
            if (!fluidState.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluidState) == layer) {
                positions.add(pos);
            }
        }
        if (positions.isEmpty()) {
            return null;
        }

        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BufferBuilder buffer = new BufferBuilder(
                FLUID_BUFFER_BUILDER,
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.BLOCK
        );
        for (BlockPos pos : positions) {
            dispatcher.renderLiquid(
                    pos,
                    renderWorld,
                    new BlockOffsetVertexConsumer(
                            buffer,
                            pos.getX() & ~15,
                            pos.getY() & ~15,
                            pos.getZ() & ~15
                    ),
                    renderWorld.getBlockState(pos),
                    renderWorld.getBlockState(pos).getFluidState()
            );
        }

        MeshData meshData = buffer.build();
        if (meshData == null) {
            return null;
        }
        TemplateMesh template = new MutableTemplateMesh(meshData).toImmutable();
        meshData.close();
        return new ShadeSeparatingSuperByteBuffer(template);
    }

    private SuperByteBuffer drawLayer(RenderType layer) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();

        PoseStack poseStack = objects.poseStack;
        RandomSource random = objects.random;
        ShadedBlockSbbBuilder sbbBuilder = objects.sbbBuilder;
        sbbBuilder.begin();

        ModelBlockRenderer.enableCaching();
        for (BlockPos pos : renderedPositions) {
            BlockState state = renderWorld.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }

            BakedModel model = dispatcher.getBlockModel(state);
            ModelData modelData = renderWorld.getModelData(pos);
            modelData = model.getModelData(renderWorld, pos, state, modelData);
            long randomSeed = state.getSeed(pos);
            random.setSeed(randomSeed);
            if (!model.getRenderTypes(state, random, modelData).contains(layer)) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            renderer.tesselateBlock(
                    renderWorld,
                    model,
                    state,
                    pos,
                    poseStack,
                    sbbBuilder,
                    true,
                    random,
                    randomSeed,
                    OverlayTexture.NO_OVERLAY,
                    modelData,
                    layer
            );
            poseStack.popPose();
        }
        ModelBlockRenderer.clearCache();

        return sbbBuilder.end();
    }

    /** Puts back the part of a block position the fluid renderer masks away. */
    private record BlockOffsetVertexConsumer(VertexConsumer delegate, int offsetX, int offsetY, int offsetZ)
            implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x + offsetX, y + offsetY, z + offsetZ);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    private static final class ThreadLocalObjects {
        private final PoseStack poseStack = new PoseStack();
        private final RandomSource random = RandomSource.createNewThreadLocalInstance();
        private final ShadedBlockSbbBuilder sbbBuilder = ShadedBlockSbbBuilder.create();
    }
}
