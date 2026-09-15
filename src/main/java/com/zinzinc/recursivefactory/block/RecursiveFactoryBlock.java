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
        // Nothing is drawn in the world any more: the block's faces are the portal planes, so its model would
        // only sit inside them. The item model still points at the block model, which is what keeps its icon.
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Kept for the selection outline, which is what keeps the block breakable.
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // No collision: the side planes pass through the block's own cell, so a solid block would push the
        // player out of its portals.
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // Right-clicking no longer teleports: entering is done by walking into one of the block's portal
        // planes, which Immersive Portals handles without a loading screen.
        // if (level.isClientSide()) {
        //     return InteractionResult.SUCCESS;
        // }
        // if (player instanceof ServerPlayer serverPlayer
        //         && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity
        //         && blockEntity.hasFactoryId()) {
        //     return FactoryTeleporter.enter(serverPlayer, blockEntity.getFactoryId())
        //             ? InteractionResult.CONSUME
        //             : InteractionResult.FAIL;
        // }
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
        ServerLevel factoryLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);

        // Placing next to an existing entrance grows that factory instead of starting a new one: the room
        // layout mirrors the entrance layout, so the new cell's room cell is the neighbour's shifted by
        // the same offset. Any adjacent cell gives the same answer, so the first found is enough.
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            FactoryData.FactoryRecord neighbour = data.factoryWithEntranceCell(
                    level.dimension().location(),
                    pos.relative(direction)
            );
            if (neighbour == null) {
                continue;
            }
            FactoryData.FactoryRecord.Cell adjacentCell = neighbour.cellAt(pos.relative(direction));
            int roomX = adjacentCell.roomX() + (pos.getX() - adjacentCell.entrance().getX()) * 16;
            int roomZ = adjacentCell.roomZ() + (pos.getZ() - adjacentCell.entrance().getZ()) * 16;
            data.addEntrance(neighbour.id(), level.dimension().location(), pos, roomX, roomZ);
            blockEntity.setFactoryId(neighbour.id());
            FactoryData.FactoryRecord grown = data.factory(neighbour.id());
            if (factoryLevel != null && grown != null) {
                FactoryDimension.prepare(factoryLevel, grown);
            }
            FactoryPortal.ensure(level, neighbour.id());
            return;
        }

        FactoryData.FactoryRecord record = data.create(owner);
        blockEntity.setFactoryId(record.id());
        data.bindEntrance(record.id(), level.dimension().location(), pos);

        FactoryData.FactoryRecord bound = data.factory(record.id());
        if (factoryLevel != null && bound != null) {
            FactoryDimension.prepare(factoryLevel, bound);
        }
        FactoryPortal.ensure(level, record.id());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide() && level.getServer() != null
                && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity) {
            int factoryId = blockEntity.getFactoryId();
            FactoryData data = FactoryData.get(level.getServer());
            data.clearEntrance(factoryId, level.dimension().location(), pos);
            FactoryData.FactoryRecord remaining = data.factory(factoryId);
            if (remaining != null && !remaining.cells().isEmpty()) {
                // The factory keeps its other cells; rebuild so the removed cell's faces become doorways.
                FactoryPortal.ensure(level, factoryId);
            } else {
                FactoryPortal.remove(level, factoryId);
            }
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
