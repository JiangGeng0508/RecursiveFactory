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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
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
    private final EntityStore store;
    private final List<BlockEntity> blockEntities = new ArrayList<>();
    private final AABB bounds;

    public FactoryProjectionCache(Level level, List<EndpointBlockEntity.PreviewBlock> previewBlocks,
                                  List<CompoundTag> previewEntities, List<CompoundTag> previewBlockEntities,
                                  EntityStore store) {
        this.store = store;
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

        int worldHeight = previewBlocks.isEmpty() ? 16 : Math.max(16, maxY + 2);
        // The world is kept along with the entities, so an entity that survives a rebuild still stands in a
        // live world instead of one this cache threw away.
        if (store.renderWorld == null || store.worldHeight < worldHeight) {
            store.renderWorld = createRenderWorld(level, worldHeight);
            store.worldHeight = worldHeight;
        }
        renderWorld = store.renderWorld;

        renderedPositions = store.renderedPositions;
        for (BlockPos previous : renderedPositions) {
            renderWorld.setBlock(previous, Blocks.AIR.defaultBlockState(), 0);
        }
        renderedPositions.clear();

        for (EndpointBlockEntity.PreviewBlock previewBlock : previewBlocks) {
            BlockPos localPos = new BlockPos(previewBlock.x(), previewBlock.y(), previewBlock.z());
            renderWorld.setBlock(localPos, previewBlock.state(), 0);
            renderedPositions.add(localPos);
            if (!previewBlock.state().getFluidState().isEmpty()) {
                fluidPositions.add(localPos);
            }
        }

        renderWorld.runLightEngine();
        updateEntities(previewEntities);
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

    /**
     * A world with no sky and no neighbours, standing in for the room the preview samples so the block and
     * entity renderers can be run on it. Everything in it is lit from above.
     */
    private static VirtualRenderWorld createRenderWorld(Level level, int worldHeight) {
        return new VirtualRenderWorld(level, 0, worldHeight, BlockPos.ZERO, () -> {
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
     *
     * <p>Position and yaw are interpolated between the last two samples, the way a real level draws its
     * entities: samples arrive once a tick, so without this everything would move in steps.
     */
    public void renderEntities(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        if (store.entities.isEmpty()) {
            return;
        }
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        for (Entity entity : store.entities.values()) {
            try {
                dispatcher.render(
                        entity,
                        Mth.lerp(partialTick, entity.xOld, entity.getX()),
                        Mth.lerp(partialTick, entity.yOld, entity.getY()),
                        Mth.lerp(partialTick, entity.zOld, entity.getZ()),
                        Mth.lerp(partialTick, entity.yRotO, entity.getYRot()),
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
     * Brings the preview's entities in line with a new sample. The entities themselves are kept from one
     * snapshot to the next instead of being built again every time: an entity built from NBT has never
     * ticked, so it has no previous position, no previous rotation and no age, and between two samples its
     * renderer snaps to the sample and restarts its own animation - which is what made moving and turning
     * entities twitch.
     */
    public void updateEntities(List<CompoundTag> samples) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < samples.size(); index++) {
            CompoundTag tag = samples.get(index);
            String id = tag.getString(EndpointBlockEntity.PREVIEW_ID_TAG);
            if (id.isEmpty()) {
                // A sample that carries no id - fall back on the order it came in.
                id = "sample " + index;
            }
            Entity entity = store.entities.get(id);
            try {
                if (entity != null && !isOfType(entity, tag)) {
                    entity = null;
                }
                if (entity == null) {
                    entity = createEntity(tag);
                    if (entity == null) {
                        continue;
                    }
                    store.entities.put(id, entity);
                } else {
                    loadSampleInto(entity, tag);
                }
                seen.add(id);
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping entity in the endpoint preview", exception);
                store.entities.remove(id);
            }
        }
        store.entities.keySet().retainAll(seen);
    }

    /**
     * The NBT carries the position already rebased on the sample centre, the frame the preview is drawn in.
     * An entity that has never ticked would otherwise be interpolated in from the origin, facing south, so
     * its old position and its body yaw are pulled in line with it before it is drawn for the first time.
     */
    @Nullable
    private Entity createEntity(CompoundTag tag) {
        Entity entity = EntityType.create(tag, renderWorld).orElse(null);
        if (entity == null) {
            LOGGER.warn("Skipping entity {} in the endpoint preview: unknown or disabled type", tag.getString("id"));
            return null;
        }
        entity.setOldPosAndRot();
        if (entity instanceof LivingEntity living) {
            float yaw = living.getYRot();
            living.yBodyRot = yaw;
            living.yBodyRotO = yaw;
            living.yHeadRot = yaw;
            living.yHeadRotO = yaw;
        }
        return entity;
    }

    private static boolean isOfType(Entity entity, CompoundTag tag) {
        return EntityType.getKey(entity.getType()).toString().equals(tag.getString("id"));
    }

    /**
     * Moves an entity that is already there onto a newer sample. {@code Entity.load} also puts the fields the
     * renderer interpolates with back in line with the sample - it ends by calling {@code setOldPosAndRot} -
     * so the previous position, rotation and age are carried over by hand, which leaves the entity in the
     * state a real one is in between two ticks. The tick counter is moved on by hand too, because nothing
     * ticks these entities, and a counter stuck at zero would restart whatever is animated with it.
     */
    private void loadSampleInto(Entity entity, CompoundTag tag) {
        double xo = entity.xo;
        double yo = entity.yo;
        double zo = entity.zo;
        double xOld = entity.xOld;
        double yOld = entity.yOld;
        double zOld = entity.zOld;
        float yRotO = entity.yRotO;
        float xRotO = entity.xRotO;
        LivingEntity living = entity instanceof LivingEntity candidate ? candidate : null;
        float yBodyRotO = living == null ? 0.0F : living.yBodyRotO;
        float yHeadRotO = living == null ? 0.0F : living.yHeadRotO;

        entity.load(tag);

        entity.xo = xo;
        entity.yo = yo;
        entity.zo = zo;
        entity.xOld = xOld;
        entity.yOld = yOld;
        entity.zOld = zOld;
        entity.yRotO = yRotO;
        entity.xRotO = xRotO;
        if (living != null) {
            living.yBodyRotO = yBodyRotO;
            living.yHeadRotO = yHeadRotO;
        }
        entity.tickCount++;
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

    /**
     * The entities one preview is drawn from, handed from one snapshot's cache to the next so that they
     * survive a rebuild and can be interpolated. One per endpoint block, and dropped along with it.
     */
    public static final class EntityStore {
        private final Map<String, Entity> entities = new LinkedHashMap<>();
        private final List<BlockPos> renderedPositions = new ArrayList<>();
        private @Nullable VirtualRenderWorld renderWorld;
        private int worldHeight;
    }

    private static final class ThreadLocalObjects {
        private final PoseStack poseStack = new PoseStack();
        private final RandomSource random = RandomSource.createNewThreadLocalInstance();
        private final ShadedBlockSbbBuilder sbbBuilder = ShadedBlockSbbBuilder.create();
    }
}
