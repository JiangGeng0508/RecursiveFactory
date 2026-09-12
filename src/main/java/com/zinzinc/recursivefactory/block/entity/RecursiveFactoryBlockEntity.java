package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class RecursiveFactoryBlockEntity extends EndpointBlockEntity {
    public RecursiveFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECURSIVE_FACTORY.get(), pos, state);
    }

    @Override
    public List<PreviewBlock> getPreviewBlocks() {
        return List.of();
    }
}
