package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Receives a live preview without acquiring a room or any endpoint transport capabilities. */
public final class FactoryPreviewBlockEntity extends BlockEntity implements FactoryPreviewSource {
    private static final double VIEW_RANGE_SQUARED = 160.0 * 160.0;
    private @Nullable GlobalPos source;
    private List<EndpointBlockEntity.PreviewBlock> blocks = List.of();
    private List<CompoundTag> entities = List.of();
    private List<CompoundTag> blockEntities = List.of();
    private CompoundTag wires = new CompoundTag();
    private List<CompoundTag> bodies = List.of();

    public FactoryPreviewBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_PREVIEW.get(), pos, state);
    }

    public @Nullable GlobalPos source() { return source; }

    public boolean canView(ServerPlayer player) {
        return level instanceof ServerLevel serverLevel && player.level() == level && player.position().distanceToSqr(
                SablePhysicsBodies.worldPosition(serverLevel, Vec3.atCenterOf(worldPosition))) < VIEW_RANGE_SQUARED;
    }

    public void tick() {
        if (level instanceof ServerLevel serverLevel && source != null
                && serverLevel.players().stream().anyMatch(this::canView)) refreshPreviewSnapshot();
    }

    @Override
    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        ServerLevel sourceLevel = source == null ? null : serverLevel.getServer().getLevel(source.dimension());
        if (sourceLevel != null && sourceLevel.isInWorldBounds(source.pos())) {
            // Looking at a remote display is enough to load the linked entrance and keep its room ticking.
            sourceLevel.getChunk(source.pos().getX() >> 4, source.pos().getZ() >> 4);
            if (sourceLevel.getBlockEntity(source.pos()) instanceof RecursiveFactoryBlockEntity entrance) {
                var record = FactoryData.get(serverLevel.getServer()).factory(entrance.getFactoryId());
                if (record != null && record.cellAt(source.pos()) != null
                        && source.dimension().location().equals(record.entranceDimension())) {
                    FactoryDimension.keepLoaded(record.id());
                    entrance.refreshPreviewForDisplay();
                    updatePreview(entrance.getPreviewBlocks(), entrance.getPreviewEntities(),
                            entrance.getPreviewBlockEntities(), entrance.getPreviewWires(), entrance.getPreviewBodies());
                    return;
                }
            }
        }
        updatePreview(List.of(), List.of(), List.of(), new CompoundTag(), List.of());
    }

    private void updatePreview(List<EndpointBlockEntity.PreviewBlock> blocks, List<CompoundTag> entities,
                               List<CompoundTag> blockEntities, CompoundTag wires, List<CompoundTag> bodies) {
        if (this.blocks.equals(blocks) && this.entities.equals(entities) && this.blockEntities.equals(blockEntities)
                && this.wires.equals(wires) && this.bodies.equals(bodies)) return;
        acceptPreview(blocks, entities, blockEntities, wires, bodies);
        if (level instanceof ServerLevel serverLevel) PacketDistributor.sendToPlayersTrackingChunk(serverLevel,
                new ChunkPos(worldPosition), new EndpointPreviewPackets.Sync(worldPosition, previewTag()));
        // Preview contents are transient. Only the coordinate link belongs in the chunk's save data.
    }

    @Override
    public void acceptPreview(List<EndpointBlockEntity.PreviewBlock> blocks, List<CompoundTag> entities,
                              List<CompoundTag> blockEntities, CompoundTag wires, List<CompoundTag> bodies) {
        this.blocks = List.copyOf(blocks);
        this.entities = List.copyOf(entities);
        this.blockEntities = List.copyOf(blockEntities);
        this.wires = wires.copy();
        this.bodies = List.copyOf(bodies);
    }

    @Override public List<EndpointBlockEntity.PreviewBlock> getPreviewBlocks() { return blocks; }
    @Override public List<CompoundTag> getPreviewEntities() { return entities; }
    @Override public List<CompoundTag> getPreviewBlockEntities() { return blockEntities; }
    @Override public CompoundTag getPreviewWires() { return wires; }
    @Override public List<CompoundTag> getPreviewBodies() { return bodies; }

    private CompoundTag previewTag() {
        return EndpointBlockEntity.writePreview(blocks, entities, blockEntities, wires, bodies);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (source != null) tag.put("Source", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, source).getOrThrow());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        source = GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound("Source")).result().orElse(null);
        CompoundTag preview = tag.getCompound("Preview");
        acceptPreview(EndpointBlockEntity.readPreviewBlocks(preview, registries),
                EndpointBlockEntity.readPreviewEntities(preview), EndpointBlockEntity.readPreviewBlockEntities(preview),
                EndpointBlockEntity.readPreviewWires(preview), EndpointBlockEntity.readPreviewBodies(preview));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.put("Preview", previewTag());
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        GlobalPos link = input.get(ModDataComponents.PREVIEW_SOURCE.get());
        if (link != null) source = link;
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (source != null) components.set(ModDataComponents.PREVIEW_SOURCE.get(), source);
    }
}
