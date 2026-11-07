package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.preview.*;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Receives a live preview without acquiring a room or any endpoint transport capabilities. */
public final class FactoryPreviewBlockEntity extends BlockEntity implements FactoryPreviewSource {
    private static final double VIEW_RANGE_SQUARED = 160.0 * 160.0;
    private @Nullable GlobalPos source;
    private PreviewSnapshot preview = PreviewSnapshot.empty();

    public FactoryPreviewBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_PREVIEW.get(), pos, state);
    }

    public @Nullable GlobalPos source() { return source; }

    public boolean canView(ServerPlayer player) {
        return level instanceof ServerLevel serverLevel && player.level() == level && player.position().distanceToSqr(
                SablePhysicsBodies.worldPosition(serverLevel, Vec3.atCenterOf(worldPosition))) < VIEW_RANGE_SQUARED;
    }

    public void tick() {
        if (!(level instanceof ServerLevel serverLevel) || source == null) return;
        var position = SablePhysicsBodies.worldPosition(serverLevel, Vec3.atCenterOf(worldPosition));
        double nearest = Double.POSITIVE_INFINITY;
        for (ServerPlayer player : serverLevel.players()) nearest = Math.min(nearest, player.position().distanceTo(position));
        if (nearest < 160) PreviewScheduler.watch(this, nearest);
    }

    public @Nullable RecursiveFactoryBlockEntity resolveSource() {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        ServerLevel sourceLevel = source == null ? null : serverLevel.getServer().getLevel(source.dimension());
        if (sourceLevel == null || !sourceLevel.isInWorldBounds(source.pos())) return null;
        sourceLevel.getChunk(source.pos().getX() >> 4, source.pos().getZ() >> 4);
        if (sourceLevel.getBlockEntity(source.pos()) instanceof RecursiveFactoryBlockEntity entrance) {
            var record = FactoryData.get(serverLevel.getServer()).factory(entrance.getFactoryId());
            if (record != null && record.cellAt(source.pos()) != null
                    && source.dimension().location().equals(record.entranceDimension())) {
                FactoryDimension.keepLoaded(record.id());
                return entrance;
            }
        }
        return null;
    }

    @Override public void refreshPreviewSnapshot() {
        RecursiveFactoryBlockEntity entrance = resolveSource();
        if (entrance == null) { show(PreviewSnapshot.empty()); return; }
        entrance.refreshPreviewForDisplay();
        PreviewTransport.copyTiming(entrance, this);
        show(entrance.previewSnapshot());
    }

    public void show(PreviewSnapshot snapshot) {
        if (preview == snapshot || preview.equals(snapshot)) return;
        preview = snapshot;
        PreviewTransport.broadcast(this);
    }

    @Override public void acceptPreview(List<EndpointBlockEntity.PreviewBlock> blocks, List<CompoundTag> entities,
                              List<CompoundTag> blockEntities, CompoundTag wires,
                              List<CompoundTag> bodies, List<CompoundTag> ropes) {
        preview = new PreviewSnapshot(blocks, entities, blockEntities, wires, bodies, ropes);
    }

    @Override public List<EndpointBlockEntity.PreviewBlock> getPreviewBlocks() { return preview.blocks(); }
    @Override public List<CompoundTag> getPreviewEntities() { return preview.entities(); }
    @Override public List<CompoundTag> getPreviewBlockEntities() { return preview.blockEntities(); }
    @Override public CompoundTag getPreviewWires() { return preview.wires(); }
    @Override public List<CompoundTag> getPreviewBodies() { return preview.bodies(); }
    @Override public List<CompoundTag> getPreviewRopes() { return preview.ropes(); }

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
                EndpointBlockEntity.readPreviewWires(preview), EndpointBlockEntity.readPreviewBodies(preview),
                EndpointBlockEntity.readPreviewRopes(preview));
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
