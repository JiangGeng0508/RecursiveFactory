package com.zinzinc.recursivefactory.block.entity;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
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
}
