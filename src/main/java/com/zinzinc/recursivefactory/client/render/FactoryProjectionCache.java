package com.zinzinc.recursivefactory.client.render;

import com.mojang.logging.LogUtils;
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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;

public final class FactoryProjectionCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS =
            ThreadLocal.withInitial(ThreadLocalObjects::new);
    private static final ByteBufferBuilder FLUID_BUFFER_BUILDER = new ByteBufferBuilder(1536);

    private final VirtualRenderWorld renderWorld;
    private final List<BlockPos> renderedPositions;
    private final List<BlockPos> fluidPositions = new ArrayList<>();
    private final Map<RenderType, SuperByteBuffer> bufferCache = new LinkedHashMap<>();
    private final Map<RenderType, SuperByteBuffer> fluidBufferCache = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<BlockEntity> blockEntities = new ArrayList<>();
    private final AABB bounds;

    public FactoryProjectionCache(Level level, List<EndpointBlockEntity.PreviewBlock> previewBlocks,
                                  List<CompoundTag> previewEntities, List<CompoundTag> previewBlockEntities) {
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
        for (CompoundTag tag : previewEntities) {
            try {
                EntityType.create(tag, renderWorld).ifPresentOrElse(
                        this::prepareEntity,
                        () -> LOGGER.warn(
                                "Skipping entity {} in the endpoint preview: unknown or disabled type",
                                tag.getString("id")
                        )
                );
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping entity in the endpoint preview", exception);
            }
        }
        for (CompoundTag tag : previewBlockEntities) {
            try {
                createBlockEntity(tag);
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping block entity in the endpoint preview", exception);
            }
        }
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

    /**
     * Draws the room's entities on top of the blocks, in the same local frame and at the same scale, so a
     * mob or a dropped item stands exactly where it stands in the room. Called from a block entity renderer,
     * so a renderer that throws is logged and skipped instead of taking the frame down with it.
     */
    public void renderEntities(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        if (entities.isEmpty()) {
            return;
        }
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        for (Entity entity : entities) {
            try {
                dispatcher.render(
                        entity,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        0.0F,
                        partialTick,
                        poseStack,
                        bufferSource,
                        LightTexture.FULL_BRIGHT
                );
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping entity render in the endpoint preview", exception);
            }
        }
    }

    /**
     * The NBT carries the position already rebased on the sample centre, which is the frame the preview is
     * drawn in, so the entity only needs its old position and its body yaw pulled in line: an entity that
     * has never ticked would otherwise be interpolated in from the origin, facing south.
     */
    private void prepareEntity(Entity entity) {
        entity.setOldPosAndRot();
        if (entity instanceof LivingEntity living) {
            float yaw = living.getYRot();
            living.yBodyRot = yaw;
            living.yBodyRotO = yaw;
            living.yHeadRot = yaw;
            living.yHeadRotO = yaw;
        }
        entities.add(entity);
    }

    /**
     * Builds a block entity again in the virtual world so its renderer can draw what the block state
     * cannot say: the items in a chest, the swinging part of a bell, the text on a sign. The tag carries the
     * type id and a position already rebased on the sample centre, the frame the preview is drawn in.
     */
    private void createBlockEntity(CompoundTag tag) {
        String id = tag.getString("id");
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ResourceLocation.tryParse(id));
        if (type == null) {
            LOGGER.warn("Skipping block entity {} in the endpoint preview: unknown type", id);
            return;
        }
        BlockPos pos = BlockEntity.getPosFromTag(tag);
        BlockEntity blockEntity = type.create(pos, renderWorld.getBlockState(pos));
        if (blockEntity == null) {
            LOGGER.warn("Skipping block entity {} in the endpoint preview: it cannot be created", id);
            return;
        }
        blockEntity.setLevel(renderWorld);
        blockEntity.loadWithComponents(tag, renderWorld.registryAccess());
        renderWorld.setBlockEntity(blockEntity);
        blockEntities.add(blockEntity);
    }

    /**
     * Draws the room's block entities on top of the blocks they stand on. Their renderers are called
     * directly instead of through the dispatcher's render(): that one culls by the distance from the real
     * camera to the block entity, and these sit at the preview's local coordinates, so everything would be
     * culled away. A renderer that throws is logged and skipped, exactly as with the entities.
     */
    public void renderBlockEntities(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        if (blockEntities.isEmpty()) {
            return;
        }
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        for (BlockEntity blockEntity : blockEntities) {
            BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
            if (renderer == null) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos();
            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            try {
                renderer.render(
                        blockEntity,
                        partialTick,
                        poseStack,
                        bufferSource,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY
                );
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping block entity render in the endpoint preview", exception);
            }
            poseStack.popPose();
        }
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
