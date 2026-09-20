package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryTeleporter;
import java.util.List;
import java.util.UUID;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The factory's entrance block. Placing one next to another entrance block grows that factory's room
 * instead of starting a second one, so a bigger factory is built by laying entrance blocks out and
 * letting the room follow the same shape.
 *
 * <p>It is a solid 4px plinth: the preview of the room is drawn floating just above it (see
 * RecursiveFactoryRenderer), and the client tints it with its factory's colour so it matches the shell of
 * the room it leads into (see FactoryColors). Which colour that is comes from the kind the block was
 * crafted in when it starts a factory, and from the factory itself when it is laid down next to one.
 */
public final class RecursiveFactoryBlock extends BaseEntityBlock implements IRotate {
    public static final MapCodec<RecursiveFactoryBlock> CODEC = simpleCodec(RecursiveFactoryBlock::new);
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D);

    public RecursiveFactoryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.POWERED, BlockStateProperties.FACING);
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity
                && blockEntity.hasFactoryId()) {
            return FactoryTeleporter.enter(serverPlayer, blockEntity.getFactoryId(), pos)
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

        // Which of the sixteen colour kinds this block was crafted in. A factory is painted one colour,
        // so this only decides the colour of a factory that is being started here: a block laid down next
        // to an existing factory joins that factory and takes the colour it already has.
        int colorIndex = FactoryColors.colorOf(stack);
        UUID owner = placer instanceof Player player ? player.getUUID() : null;
        FactoryData data = FactoryData.get(level.getServer());
        ServerLevel factoryLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);

        // Next to an existing entrance, this block grows that factory: the room layout mirrors the
        // entrance layout, so the new cell's room cell is the neighbour's shifted by the same offset.
        // Any adjacent cell gives the same answer, so the first one found is enough.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbourPos = pos.relative(direction);
            FactoryData.FactoryRecord neighbour = data.factoryWithEntranceCell(
                    level.dimension().location(),
                    neighbourPos
            );
            if (neighbour == null) {
                continue;
            }
            FactoryData.FactoryRecord.Cell adjacentCell = neighbour.cellAt(neighbourPos);
            int roomX = adjacentCell.roomX()
                    + (pos.getX() - adjacentCell.entrance().getX()) * FactoryData.CELL_SIZE;
            int roomZ = adjacentCell.roomZ()
                    + (pos.getZ() - adjacentCell.entrance().getZ()) * FactoryData.CELL_SIZE;
            data.addEntrance(neighbour.id(), level.dimension().location(), pos, roomX, roomZ);
            blockEntity.setFactoryId(neighbour.id());

            FactoryData.FactoryRecord grown = data.factory(neighbour.id());
            if (grown != null) {
                blockEntity.setColorIndex(grown.colorIndex());
            }
            if (factoryLevel != null && grown != null) {
                FactoryDimension.prepare(factoryLevel, grown);
            }
            FactoryRelay.updateFromNeighbours(blockEntity);
            return;
        }

        FactoryData.FactoryRecord record = data.create(owner, colorIndex);
        blockEntity.setFactoryId(record.id());
        blockEntity.setColorIndex(record.colorIndex());
        data.bindEntrance(record.id(), level.dimension().location(), pos);

        FactoryData.FactoryRecord bound = data.factory(record.id());
        if (factoryLevel != null && bound != null) {
            FactoryDimension.prepare(factoryLevel, bound);
        }
        // A lever or a dust line may already be waiting next to the block it was placed against.
        FactoryRelay.updateFromNeighbours(blockEntity);
    }

    /**
     * What a broken entrance block drops keeps the colour kind it was painted with. The loot table still
     * decides what comes out of it, and every copy of this block it hands out is stamped with the kind the
     * block that broke was standing for.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                instanceof RecursiveFactoryBlockEntity blockEntity && blockEntity.hasColorIndex()) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.set(ModDataComponents.COLOR.get(), blockEntity.getColorIndex());
                }
            }
        }
        return drops;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide() && level.getServer() != null
                && level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity) {
            int factoryId = blockEntity.getFactoryId();
            FactoryData data = FactoryData.get(level.getServer());
            FactoryData.FactoryRecord before = data.factory(factoryId);
            FactoryData.FactoryRecord.Cell removed = before == null ? null : before.cellAt(pos);

            data.clearEntrance(factoryId, level.dimension().location(), pos);

            ServerLevel factoryLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);
            FactoryData.FactoryRecord remaining = data.factory(factoryId);
            if (factoryLevel != null && removed != null) {
                // The room shrinks: drop the shell around the cell that left, then rebuild the rest.
                FactoryDimension.clearShellAround(factoryLevel, removed);
                if (remaining != null && !remaining.cells().isEmpty()) {
                    FactoryDimension.prepare(factoryLevel, remaining);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
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
     * Every face takes a shaft, so that the whole shell of a room is one kinetic network: a shaft or a
     * machine put against the wall anywhere turns the factory it stands in, and the link with the entrance
     * block outside reaches the room wherever it is geared in (see KineticRelay).
     */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return true;
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
        return new RecursiveFactoryBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        return type == ModBlockEntities.RECURSIVE_FACTORY.get()
                ? (tickerLevel, tickerPos, tickerState, blockEntity) ->
                        ((EndpointBlockEntity) blockEntity).tick()
                : null;
    }
}
