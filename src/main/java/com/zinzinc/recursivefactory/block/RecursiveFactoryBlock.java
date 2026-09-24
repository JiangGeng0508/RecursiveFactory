package com.zinzinc.recursivefactory.block;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.zinzinc.recursivefactory.data.FaceMode;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.KineticRelay;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

/**
 * The factory's entrance block. Placing one next to another entrance block grows that factory's room
 * instead of starting a second one, so a bigger factory is built by laying entrance blocks out and
 * letting the room follow the same shape.
 *
 * <p>It is a frame: twelve one sixteenth bars along the edges of the block, with the middle open. The
 * preview of the room is drawn inside that opening, scaled so that the room's own shell lines up with the
 * frame (see RecursiveFactoryRenderer), and the client tints the frame with its factory's colour so it
 * matches the shell of the room it leads into (see FactoryColors). Which colour that is comes from the
 * kind the block was crafted in when it starts a factory, and from the factory itself when it is laid
 * down next to one.
 */
public final class RecursiveFactoryBlock extends BaseEntityBlock implements IRotate, IWrenchable {
    public static final MapCodec<RecursiveFactoryBlock> CODEC = simpleCodec(RecursiveFactoryBlock::new);
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * A whole block, even though the model is a frame with its middle open: the preview of the room hangs
     * in that opening, and a player walking up to the block should not step into the room's miniature.
     */
    private static final VoxelShape SHAPE = Shapes.block();

    public RecursiveFactoryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.FACING, Direction.NORTH)
                .setValue(BlockStateProperties.NORTH, false)
                .setValue(BlockStateProperties.EAST, false)
                .setValue(BlockStateProperties.SOUTH, false)
                .setValue(BlockStateProperties.WEST, false)
                .setValue(FactoryColors.COLOR_PROPERTY, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.POWERED, BlockStateProperties.FACING, FactoryColors.COLOR_PROPERTY,
                BlockStateProperties.NORTH, BlockStateProperties.EAST,
                BlockStateProperties.SOUTH, BlockStateProperties.WEST);
    }

    /**
     * The colour kind the item was crafted in, written onto the block as it is placed. The state is what
     * the client tints with and it travels with the block, so the pedestal comes out in its own colour on
     * the very first frame instead of turning from the plain colour once the block entity catches up.
     *
     * <p>A stack that carries no colour kind leaves the property at 0, which asks the factory instead:
     * {@link #setPlacedBy} fills it in once the factory - the one this block joins, or the one it starts -
     * is known.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withConnections(
                defaultBlockState().setValue(
                        FactoryColors.COLOR_PROPERTY,
                        FactoryColors.stateValue(FactoryColors.colorOf(context.getItemInHand()))),
                context.getLevel(),
                context.getClickedPos());
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

    /**
     * Works out one side of the frame from the block that has just changed beside it, which is what keeps a
     * growing factory joined up while entrance blocks are laid down and taken away. Up and down are not
     * sides of a room, so a change above or below leaves the state alone.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!direction.getAxis().isHorizontal() || !state.hasProperty(BlockStateProperties.NORTH)) {
            return state;
        }
        return state.setValue(joinedAcross(direction),
                neighborState.is(this) && sharesRoom(level, pos, neighborPos));
    }

    /**
     * {@code state} with all four sides worked out from what stands beside {@code pos}. A side is joined
     * when the block over there is another entrance block of the same room: the two frames meet along the
     * plane they share, and a plane inside a room is drawn by neither of them. The plane two rooms share
     * is a wall of both of them, see {@link #sharesRoom}.
     */
    public static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos) {
        if (!state.hasProperty(BlockStateProperties.NORTH)) {
            return state;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            state = state.setValue(joinedAcross(side), joins(level, pos, side));
        }
        return state;
    }

    /**
     * Whether the frame carries on into the block beside {@code pos} on {@code side}: the block over
     * there is another entrance block of the same room, see {@link #sharesRoom}.
     */
    private static boolean joins(BlockGetter level, BlockPos pos, Direction side) {
        BlockPos neighbourPos = pos.relative(side);
        return level.getBlockState(neighbourPos).is(ModBlocks.RECURSIVE_FACTORY.get())
                && sharesRoom(level, pos, neighbourPos);
    }

    /**
     * Whether the entrance block at {@code otherPos} leads into the same room as the one at {@code pos}.
     *
     * <p>Two entrance blocks of one factory stand for two cells of a single room, so the plane they share
     * is inside that room and neither of them draws it. Two blocks of different factories stand for two
     * rooms side by side, and the plane between them is a wall of both: a room's checkerboard floor stops
     * at its own shell, so the room behind such a block has no floor along that plane. Dropping the bars
     * there would leave the two rooms joined up in the frame while the floor behind one of them breaks
     * off, which reads as a crack along the join.
     *
     * <p>The answer comes from the two block entities. One that is not there yet - a block beside a
     * freshly placed block, whose block entity has not been made yet, or a neighbour in a chunk that has
     * not finished loading - counts as the same room, which is what every block in a save older than this
     * rule carries. The first tick of an entrance block settles the question again, see
     * {@code RecursiveFactoryBlockEntity#tick}.
     */
    private static boolean sharesRoom(BlockGetter level, BlockPos pos, BlockPos otherPos) {
        if (level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity here
                && level.getBlockEntity(otherPos) instanceof RecursiveFactoryBlockEntity other) {
            return here.hasFactoryId() && here.getFactoryId() == other.getFactoryId();
        }
        return true;
    }

    /**
     * Works the four sides of the frame out again for the entrance block at {@code pos} and for the
     * blocks beside it. A block that has just been laid down or taken up owes this to its neighbours: a
     * neighbour was asked about the join while the new block's block entity - which is half of the answer,
     * see {@link #sharesRoom} - was not there yet.
     */
    public static void refreshConnections(Level level, BlockPos pos) {
        settleConnections(level, pos);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            settleConnections(level, pos.relative(side));
        }
    }

    private static void settleConnections(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.RECURSIVE_FACTORY.get())) {
            return;
        }
        BlockState joined = withConnections(state, level, pos);
        if (joined != state) {
            level.setBlock(pos, joined, Block.UPDATE_ALL);
        }
    }

    /**
     * The property that says whether the frame carries on into the block on {@code side}. Only the four
     * sides of a room have one: a room has no side above or below it.
     */
    private static BooleanProperty joinedAcross(Direction side) {
        return switch (side) {
            case NORTH -> BlockStateProperties.NORTH;
            case EAST -> BlockStateProperties.EAST;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            default -> throw new IllegalArgumentException("a room has no " + side + " side");
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity)
                || !blockEntity.hasFactoryId()) {
            return InteractionResult.PASS;
        }
        // Sneaking switches what the face that was clicked carries; anything else walks in. The face is the
        // one the click landed on, so a player can set up the side of the factory they are standing at
        // without having to walk round it.
        if (player.isShiftKeyDown()) {
            return cycleFaceMode(level, blockEntity, hitResult.getDirection(), player)
                    ? InteractionResult.CONSUME
                    : InteractionResult.PASS;
        }
        return player instanceof ServerPlayer serverPlayer
                && FactoryTeleporter.enter(serverPlayer, blockEntity.getFactoryId(), pos)
                ? InteractionResult.CONSUME
                : InteractionResult.FAIL;
    }

    /**
     * A wrench switches the face it is aimed at, exactly as sneaking with a bare hand does, and does not
     * turn the block or take it up: the face a wrench is pointed at means nothing to the direction this
     * block faces, which only ever marks where its relay is driving redstone, and picking the block up
     * would leave the room behind it without an entrance.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return wrenchFace(context, true);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        return wrenchFace(context, true);
    }

    private static InteractionResult wrenchFace(UseOnContext context, boolean playSound) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity)
                || !blockEntity.hasFactoryId()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!cycleFaceMode(level, blockEntity, context.getClickedFace(), context.getPlayer())) {
            return InteractionResult.PASS;
        }
        if (playSound) {
            IWrenchable.playRotateSound(level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Moves one face on to the next mode and says so on the player's action bar, which is the only place a
     * face can be read as more than a colour. Answers whether the face moved at all.
     */
    private static boolean cycleFaceMode(Level level, RecursiveFactoryBlockEntity blockEntity, Direction face,
                                        @Nullable Player player) {
        FaceMode mode = blockEntity.faceMode(face).next();
        if (!blockEntity.setFaceMode(face, mode)) {
            return false;
        }
        LOGGER.debug("Factory #{}: {} face of {} now carries {}", blockEntity.getFactoryId(),
                face.getSerializedName(), blockEntity.getBlockPos(), mode.getSerializedName());
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "message.recursivefactory.face_mode",
                    Component.translatable("face.recursivefactory." + face.getSerializedName()),
                    mode.displayName()
            ), true);
        }
        return true;
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
            refreshConnections(level, pos);
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
        refreshConnections(level, pos);
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
        // Which colour kind the block comes back as: the one it is drawn in, or - for a block that predates
        // the colour sitting on the state - the one its block entity was given.
        int colorIndex = FactoryColors.kindOfState(state);
        if (colorIndex == FactoryColors.NO_COLOR
                && params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                        instanceof RecursiveFactoryBlockEntity blockEntity && blockEntity.hasColorIndex()) {
            colorIndex = blockEntity.getColorIndex();
        }
        if (colorIndex != FactoryColors.NO_COLOR) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    drop.set(ModDataComponents.COLOR.get(), colorIndex);
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
            // The room this block led into may have lost its cell, and the neighbours of a block that is
            // gone have to be asked again anyway: whether the frame carries on into the gap is now a
            // question about two other factories, or about none.
            refreshConnections(level, pos);
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
     * The entrance block stands on its own outside a factory, so a shaft reaches it from any face that is
     * not another factory's block - a row of entrance blocks therefore stays a row of separate machines
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
