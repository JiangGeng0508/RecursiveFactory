package com.zinzinc.recursivefactory.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The room side of a factory's endpoint. Shared endpoint data stores the factory binding, colour and
 * installed redstone connection. Each barrier relays to its own room cell's entrance (see {@link FactoryRelay}).
 */
public final class FactoryBarrierBlockEntity extends EndpointBlockEntity {
    public FactoryBarrierBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_BARRIER.get(), pos, state);
    }
}