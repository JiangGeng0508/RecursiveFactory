package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryBarrierBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.KineticRelay;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.world.FactoryTeleporter;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
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

/**
 * The block a factory room is walled with. Every barrier block belongs to one factory, and the whole
 * wall relays that factory's items and redstone to the entrance block outside: a hopper pointing at a
 * barrier inside a room feeds the factory block, and a signal fed to a barrier lights the factory
 * block's output face (see {@link FactoryRelay}).
 *
 * <p>Right-clicking a barrier leaves the room, which is the way out now that the walls are solid.
 */
public final class FactoryBarrierBlock extends BaseEntityBlock implements IRotate {
    public static final MapCodec<FactoryBarrierBlock> CODEC = simpleCodec(FactoryBarrierBlock::new);
    private static final VoxelShape SHAPE = Shapes.block();

    public FactoryBarrierBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.FACING, Direction.NORTH)
                .setValue(FactoryColors.COLOR_PROPERTY, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.POWERED, BlockStateProperties.FACING, FactoryColors.COLOR_PROPERTY);
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

    /**
     * A barrier is the room's wall, so a player in survival cannot mine one: the progress stays at zero,
     * which stops both the server's "started destroying" and "stopped destroying" paths from ever
     * finishing, and keeps the client from drawing a cracking overlay on it. Creative is left alone -
     * it breaks blocks without asking for progress, so a room can still be taken apart while building.
     */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return player.isCreative() ? super.getDestroyProgress(state, player, level, pos) : 0.0F;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof FactoryBarrierBlockEntity barrier
                && barrier.hasFactoryId()) {
            return FactoryTeleporter.exit(serverPlayer, barrier.getFactoryId())
                    ? InteractionResult.CONSUME
                    : InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint) {
            FactoryRelay.updateFromNeighbours(endpoint);
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FactoryRelay.emittedSignal(state, level.getBlockEntity(pos), direction);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return FactoryRelay.emittedSignal(state, level.getBlockEntity(pos), direction);
    }

    /**
     * A shaft reaches this block from the next block of the shell along - a whole room's wall, floor and
     * ceiling are one machine - and from the room itself, which is where the player builds. It does not
     * reach in from another factory's shell, so two rooms standing side by side are two machines
     * (see {@link KineticRelay#takesShaft}).
     */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return KineticRelay.takesShaft(level, pos, state, face);
    }

    /**
     * The blocks do not draw their own turning, so the axis is only ever used by Create to tell which faces
     * of two touching blocks line up.
     */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactoryBarrierBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        return type == ModBlockEntities.FACTORY_BARRIER.get()
                ? (tickerLevel, tickerPos, tickerState, blockEntity) ->
                        ((EndpointBlockEntity) blockEntity).tick()
                : null;
    }
}
