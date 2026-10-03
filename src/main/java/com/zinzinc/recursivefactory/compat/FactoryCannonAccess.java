package com.zinzinc.recursivefactory.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The small part of Create's cannon used by room printing. */
public interface FactoryCannonAccess {
    int recursivefactory$cooldown();
    void recursivefactory$cooldown(int value);
    boolean recursivefactory$ignore(BlockState state, BlockEntity entity);
    void recursivefactory$launch(BlockPos target, ItemStack icon, BlockState state, BlockEntity entity);
}
