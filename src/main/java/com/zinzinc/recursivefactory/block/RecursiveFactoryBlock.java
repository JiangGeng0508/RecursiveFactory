package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryPortal;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RecursiveFactoryBlock extends BaseEntityBlock {
    public static final MapCodec<RecursiveFactoryBlock> CODEC = simpleCodec(RecursiveFactoryBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 4.0D),
            Block.box(0.0D, 0.0D, 12.0D, 16.0D, 4.0D, 16.0D),
            Block.box(0.0D, 0.0D, 4.0D, 4.0D, 4.0D, 12.0D),
            Block.box(12.0D, 0.0D, 4.0D, 16.0D, 4.0D, 12.0D)
    );

    public RecursiveFactoryBlock(BlockBehaviour.Properties properties) {
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
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
                && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity
                && blockEntity.hasFactoryId()) {
            return FactoryTeleporter.enter(serverPlayer, blockEntity.getFactoryId())
                    ? InteractionResult.CONSUME
                    : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || level.getServer() == null
                || !(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity)) {
            return;
        }

        java.util.UUID owner = placer instanceof Player player ? player.getUUID() : null;
        FactoryData data = FactoryData.get(level.getServer());
        FactoryData.FactoryRecord record = data.create(owner);
        blockEntity.setFactoryId(record.id());
        data.bindEntrance(record.id(), level.dimension().location(), pos);

        ServerLevel factoryLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (factoryLevel != null) {
            FactoryDimension.prepare(factoryLevel, record);
        }
        FactoryPortal.ensure(level, record.id());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide() && level.getServer() != null
                && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity) {
            FactoryPortal.remove(level, blockEntity.getFactoryId());
            FactoryData.get(level.getServer()).clearEntrance(
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
        return new RecursiveFactoryBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntities.RECURSIVE_FACTORY.get()
                ? (tickerLevel, tickerPos, tickerState, blockEntity) -> ((EndpointBlockEntity) blockEntity).serverTick()
                : null;
    }
}
