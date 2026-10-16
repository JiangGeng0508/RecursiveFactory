package com.zinzinc.recursivefactory.block.entity;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;

/** Preview data shared by working endpoints and display-only blocks. */
public interface FactoryPreviewSource {
    default boolean canView(ServerPlayer player) {
        return getLevel() instanceof ServerLevel level && player.level() == level
                && player.position().distanceToSqr(SablePhysicsBodies.worldPosition(level,
                        Vec3.atCenterOf(getBlockPos()))) < 160.0 * 160.0;
    }

    @Nullable Level getLevel();
    BlockPos getBlockPos();
    List<EndpointBlockEntity.PreviewBlock> getPreviewBlocks();
    List<CompoundTag> getPreviewEntities();
    List<CompoundTag> getPreviewBlockEntities();
    CompoundTag getPreviewWires();
    List<CompoundTag> getPreviewBodies();
    List<CompoundTag> getPreviewRopes();
    void refreshPreviewSnapshot();
    void acceptPreview(List<EndpointBlockEntity.PreviewBlock> blocks, List<CompoundTag> entities,
                       List<CompoundTag> blockEntities, CompoundTag wires, List<CompoundTag> bodies, List<CompoundTag> ropes);

    /** Display data is serialized explicitly for preview transport, never as persistent block data. */
    default CompoundTag previewTag() {
        return EndpointBlockEntity.writePreview(getPreviewBlocks(), getPreviewEntities(), getPreviewBlockEntities(),
                getPreviewWires(), getPreviewBodies(), getPreviewRopes());
    }

    default void acceptPreviewTag(CompoundTag tag, HolderLookup.Provider registries) {
        acceptPreview(EndpointBlockEntity.readPreviewBlocks(tag, registries), EndpointBlockEntity.readPreviewEntities(tag),
                EndpointBlockEntity.readPreviewBlockEntities(tag), EndpointBlockEntity.readPreviewWires(tag),
                EndpointBlockEntity.readPreviewBodies(tag), EndpointBlockEntity.readPreviewRopes(tag));
    }

    default void clearPreview() {
        acceptPreview(List.of(), List.of(), List.of(), new CompoundTag(), List.of(), List.of());
    }
}
