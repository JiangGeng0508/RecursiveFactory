package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.zinzinc.recursivefactory.block.entity.FactoryPreviewBlockEntity;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A thin plate that displays a linked entrance's miniature within its own block space. */
public final class FactoryPreviewBlock extends BaseEntityBlock {
    public static final MapCodec<FactoryPreviewBlock> CODEC = simpleCodec(FactoryPreviewBlock::new);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);

    public FactoryPreviewBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactoryPreviewBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                          BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.FACTORY_PREVIEW.get(),
                (world, pos, blockState, display) -> display.tick());
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof FactoryPreviewBlockEntity display) {
            for (ItemStack drop : drops) if (drop.is(asItem())) copyLink(drop, display);
        }
        return drops;
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof FactoryPreviewBlockEntity display) copyLink(stack, display);
        return stack;
    }

    private static void copyLink(ItemStack stack, FactoryPreviewBlockEntity display) {
        if (display.source() != null) stack.set(ModDataComponents.PREVIEW_SOURCE.get(), display.source());
    }
}
