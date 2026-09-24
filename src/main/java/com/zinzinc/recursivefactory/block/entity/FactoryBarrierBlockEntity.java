package com.zinzinc.recursivefactory.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The room side of a factory's endpoint. It keeps no state of its own beyond the factory id and the
 * colour the factory is painted: items and redstone are relayed to the factory's entrance block, one
 * barrier at a time (see {@link FactoryRelay}).
 */
public final class FactoryBarrierBlockEntity extends EndpointBlockEntity {
    public FactoryBarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_BARRIER.get(), pos, state);
    }
}