package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryTeleporter;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

public final class MirrorFactoryBlock extends BaseEntityBlock {
    public static final MapCodec<MirrorFactoryBlock> CODEC = simpleCodec(MirrorFactoryBlock::new);

    public MirrorFactoryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.POWERED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.dimension() == FactoryDimension.LEVEL_KEY
                && level.getBlockEntity(pos) instanceof MirrorFactoryBlockEntity blockEntity
                && blockEntity.hasFactoryId()) {
            return FactoryTeleporter.exit(serverPlayer, blockEntity.getFactoryId())
                    ? InteractionResult.CONSUME
                    : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || level.getServer() == null
                || !(level.getBlockEntity(pos) instanceof MirrorFactoryBlockEntity blockEntity)) {
            return;
        }

        FactoryData data = FactoryData.get(level.getServer());
        FactoryData.FactoryRecord record = level.dimension() == FactoryDimension.LEVEL_KEY
                ? data.factoryAt(pos)
                : findNearbyFactory(level, pos);
        if (record == null) {
            return;
        }

        blockEntity.setFactoryId(record.id());
        data.bindMirror(record.id(), level.dimension().location(), pos);
    }

    private static FactoryData.FactoryRecord findNearbyFactory(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return null;
        }

        FactoryData data = FactoryData.get(serverLevel.getServer());
        for (FactoryData.FactoryRecord record : data.factories()) {
            if (record.entranceDimension() == null || !record.entranceDimension().equals(level.dimension().location())) {
                continue;
            }
            if (record.entrancePos().distSqr(pos) <= 8 * 8) {
                return record;
            }
        }
        return null;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide() && level.getServer() != null
                && level.getBlockEntity(pos) instanceof MirrorFactoryBlockEntity blockEntity) {
            FactoryData.get(level.getServer()).clearMirror(
                    blockEntity.getFactoryId(),
                    level.dimension().location(),
                    pos
            );
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint) {
            FactoryRelay.syncPower(
                    endpoint,
                    level.hasNeighborSignal(pos),
                    FactoryRelay.strongestInputDirection(level, pos)
            );
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(BlockStateProperties.POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (!state.getValue(BlockStateProperties.POWERED) || !(level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint)) {
            return 0;
        }
        return endpoint.getOutputDirection() == direction.getOpposite() ? 15 : 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MirrorFactoryBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntities.MIRROR_FACTORY.get()
                ? (tickerLevel, tickerPos, tickerState, blockEntity) -> ((EndpointBlockEntity) blockEntity).serverTick()
                : null;
    }
}
