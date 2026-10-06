package com.zinzinc.recursivefactory.block.entity;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** Preview data shared by working endpoints and display-only blocks. */
public interface FactoryPreviewSource {
    @Nullable Level getLevel();
    BlockPos getBlockPos();
    List<EndpointBlockEntity.PreviewBlock> getPreviewBlocks();
    List<CompoundTag> getPreviewEntities();
    List<CompoundTag> getPreviewBlockEntities();
    CompoundTag getPreviewWires();
    List<CompoundTag> getPreviewBodies();
    void refreshPreviewSnapshot();
    void acceptPreview(List<EndpointBlockEntity.PreviewBlock> blocks, List<CompoundTag> entities,
                       List<CompoundTag> blockEntities, CompoundTag wires, List<CompoundTag> bodies);
}
