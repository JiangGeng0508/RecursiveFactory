package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.power.FactoryWireSchematics;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.api.schematic.requirement.SpecialBlockItemRequirement;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.KineticRelay;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.compat.FactorySchematicMaterials;
import com.zinzinc.recursivefactory.compat.sable.SableAssembly;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryTeleporter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
public final class RecursiveFactoryBlock extends BaseEntityBlock
        implements IRotate, SpecialBlockItemRequirement {
    public static final MapCodec<RecursiveFactoryBlock> CODEC = simpleCodec(RecursiveFactoryBlock::new);
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
                .setValue(BlockStateProperties.UP, false)
                .setValue(BlockStateProperties.DOWN, false)
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
                BlockStateProperties.SOUTH, BlockStateProperties.WEST,
                BlockStateProperties.UP, BlockStateProperties.DOWN);
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
     * growing factory joined up while entrance blocks are laid down and taken away, on all six sides.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!state.hasProperty(BlockStateProperties.NORTH)) {
            return state;
        }
        return state.setValue(joinedAcross(direction),
                neighborState.is(this) && sharesRoom(level, pos, neighborPos));
    }

    /**
     * {@code state} with all six sides worked out from what stands beside {@code pos}. A side is joined
     * when the block over there is another entrance block of the same room: the two frames meet along the
     * plane they share, and a plane inside a room is drawn by neither of them. The plane two rooms share
     * is a wall of both of them, see {@link #sharesRoom}.
     */
    public static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos) {
        if (!state.hasProperty(BlockStateProperties.NORTH)) {
            return state;
        }
        for (Direction side : Direction.values()) {
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
     * Works the six sides of the frame out again for the entrance block at {@code pos} and for the
     * blocks beside it. A block that has just been laid down or taken up owes this to its neighbours: a
     * neighbour was asked about the join while the new block's block entity - which is half of the answer,
     * see {@link #sharesRoom} - was not there yet.
     */
    public static void refreshConnections(Level level, BlockPos pos) {
        settleConnections(level, pos);
        for (Direction side : Direction.values()) {
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
     * The property that says whether the frame carries on into the block on {@code side}.
     */
    private static BooleanProperty joinedAcross(Direction side) {
        return switch (side) {
            case NORTH -> BlockStateProperties.NORTH;
            case EAST -> BlockStateProperties.EAST;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            case UP -> BlockStateProperties.UP;
            case DOWN -> BlockStateProperties.DOWN;
        };
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
                || !(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity blockEntity)) {
            return;
        }

        // Which of the sixteen colour kinds this block was crafted in. A factory is painted one colour,
        // so this only decides the colour of a factory that is being started here: a block laid down next
        // to an existing factory joins that factory and takes the colour it already has. A block a
        // creative deploy put down comes with no stack at all, which reads as no colour of its own.
        int colorIndex = stack == null ? FactoryColors.NO_COLOR : FactoryColors.colorOf(stack);
        UUID owner = placer instanceof Player player ? player.getUUID() : null;
        FactoryData data = FactoryData.get(level.getServer());
        ServerLevel factoryLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        var linkedCopy = blockEntity.takePreparedCopy();
        if (linkedCopy != null) {
            blockEntity.clearDoor();
            int roomId = FactoryBlueprint.build(serverLevel, pos, linkedCopy.blueprint(), owner);
            if (roomId > 0) {
                if (RecursiveFactory.powerAvailable() && !linkedCopy.entranceNodes().isEmpty()) {
                    FactoryWires.place(serverLevel, pos, linkedCopy.entranceNodes());
                }
                placeCopiedDoors(level, pos, linkedCopy.doors(), roomId, data);
            }
            return;
        }
        var printedRoom = blockEntity.takeCannonRoom();
        if (!printedRoom.isEmpty()) {
            blockEntity.clearDoor();
            blockEntity.restoreBlueprintEntranceNodes();
            FactoryDimension.linkEntranceCell(level, pos, printedRoom.getInt("Id"),
                    BlockPos.of(printedRoom.getLong("Cell")));
            return;
        }

        // Creative deployment and legacy mirror items still build immediately. Cannon projectiles take
        // the preallocated-room path above and leave their contents to the cannon's persisted task.
        String blueprintFile = blockEntity.blueprintFile();
        String blueprintOrigin = blockEntity.blueprintOrigin();
        BlockPos sourceCell = blockEntity.roomCell();
        int sourceFactory = blockEntity.hasFactoryId() ? blockEntity.getFactoryId() : -1;
        blockEntity.clearDoor();
        blockEntity.restoreBlueprintEntranceNodes();

        if (blueprintFile != null) {
            int roomId = FactoryBlueprint.build(serverLevel, pos, blueprintFile, owner);
            if (roomId > 0) {
                placeCopiedDoors(level, pos, stack, roomId, data);
                return;
            }
        } else if (sourceFactory > 0 && takenInThisSave(data, blueprintOrigin)) {
            // A block that was copied out of the world - by Create's own blueprint and quill, or by
            // anything else that takes a snapshot of what stands there - carries the factory it stood in
            // and nothing of the room, which is in another dimension and is not in the snapshot. So the
            // factory is copied behind the block here and now, the factories standing inside it and all.
            // The save it was taken in has to be this one, or the number would name whatever factory of
            // this save happened to carry it (see takenInThisSave).
            FactoryData.FactoryRecord source = data.factory(sourceFactory);
            if (source != null) {
                // The other blocks of the same file are put down beside this one and join the copy rather
                // than copying the same factory again, which is what the copy remembers it was made from
                // for (see FactoryData#markCopiedFrom).
                FactoryData.FactoryRecord copy = copiedFactoryBeside(level, data, pos, sourceFactory);
                if (copy != null && joinFactory(level, pos, data, factoryLevel, blockEntity, copy)) {
                    return;
                }
                if (FactoryBlueprint.copy(serverLevel, pos, source, sourceCell, owner) > 0) {
                    return;
                }
            }
        }

        // Next to an existing entrance, this block grows that factory. Any adjacent cell gives the same
        // answer, so the first one found is enough.
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            FactoryData.FactoryRecord neighbour = data.factoryWithEntranceCell(
                    level.dimension().location(),
                    neighbourPos
            );
            if (neighbour != null && joinFactory(level, pos, data, factoryLevel, blockEntity, neighbour)) {
                return;
            }
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
     * Lays down the other entrance blocks a copied expanded factory travelled with, each bound to the room
     * cell the copy already stands on (see {@link FactoryDimension#linkEntranceCell}). The item checked
     * every position before the first block went down, so this has somewhere to put each of them; a cell
     * the copy no longer stands on - a factory that has changed since it was copied - is skipped.
     */
    private static void placeCopiedDoors(Level level, BlockPos anchor, ItemStack stack, int roomId,
                                         FactoryData data) {
        placeCopiedDoors(level, anchor, RecursiveFactoryItem.doorOffsets(stack), roomId, data);
    }

    private static void placeCopiedDoors(Level level, BlockPos anchor, List<BlockPos> offsets, int roomId,
                                         FactoryData data) {
        if (offsets.isEmpty()) {
            return;
        }
        FactoryData.FactoryRecord room = data.factory(roomId);
        if (room == null) {
            return;
        }
        BlockState doorState = ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState()
                .setValue(FactoryColors.COLOR_PROPERTY,
                        FactoryColors.stateValue(FactoryColors.kindOfFactory(room.colorIndex(), room.id())));
        for (BlockPos offset : offsets) {
            BlockPos target = anchor.offset(offset);
            level.setBlock(target, doorState, Block.UPDATE_ALL);
            FactoryDimension.linkEntranceCell(level, target, roomId, offset.multiply(FactoryData.CELL_SIZE));
        }
    }

    /**
     * Whether the factory number an entrance block carries out of a snapshot names a factory of this save,
     * so that copying that factory is the right thing to do.
     *
     * <p>A block carries the origin of the save it was copied out of (see
     * RecursiveFactoryBlockEntity#ORIGIN_TAG), and a snapshot naming another save is turned down: its
     * number would name whatever factory of this save happened to carry it. A snapshot with no origin at
     * all was taken before the origin was written - by this mod's own earlier version, or by a tool that
     * dropped the tag - and is taken at its word rather than turned into an empty factory.
     */
    private static boolean takenInThisSave(FactoryData data, @Nullable String blueprintOrigin) {
        return blueprintOrigin == null || data.origin().toString().equals(blueprintOrigin);
    }

    /**
     * Points an entrance block that has just been put down at the factory it stands beside: the room
     * layout mirrors the entrance layout, so the new cell's room cell is a cell of that factory shifted
     * by the same offset. A cell the factory already stands on - a room that was copied from a blueprint
     * was built wide enough for every block the file carries - is taken over by the block rather than
     * grown a second time (see FactoryData#addEntrance).
     *
     * @return whether a cell of that factory stands beside the block, which leaves the block joined to it
     */
    private static boolean joinFactory(Level level, BlockPos pos, FactoryData data,
                                       @Nullable ServerLevel factoryLevel,
                                       RecursiveFactoryBlockEntity blockEntity,
                                       FactoryData.FactoryRecord factory) {
        FactoryData.FactoryRecord.Cell adjacentCell = null;
        for (Direction direction : Direction.values()) {
            FactoryData.FactoryRecord.Cell cell = factory.cellAt(pos.relative(direction));
            if (cell != null) {
                adjacentCell = cell;
                break;
            }
        }
        if (adjacentCell == null) {
            return false;
        }
        int roomX = adjacentCell.roomX()
                + (pos.getX() - adjacentCell.entrance().getX()) * FactoryData.CELL_SIZE;
        int roomY = adjacentCell.roomY()
                + (pos.getY() - adjacentCell.entrance().getY()) * FactoryData.CELL_SIZE;
        int roomZ = adjacentCell.roomZ()
                + (pos.getZ() - adjacentCell.entrance().getZ()) * FactoryData.CELL_SIZE;
        data.addEntrance(factory.id(), level.dimension().location(), pos, roomX, roomY, roomZ);
        blockEntity.setFactoryId(factory.id());

        FactoryData.FactoryRecord grown = data.factory(factory.id());
        if (grown != null) {
            blockEntity.setColorIndex(grown.colorIndex());
        }
        if (factoryLevel != null && grown != null) {
            FactoryDimension.prepare(factoryLevel, grown);
        }
        refreshConnections(level, pos);
        FactoryRelay.updateFromNeighbours(blockEntity);
        return true;
    }

    /**
     * The factory standing beside this block that was copied from the factory this block names, or null
     * when there is none: the first block a blueprint puts down copies the factory behind it, and the
     * blocks of that same blueprint put down beside it join that copy rather than copying the same factory
     * again.
     */
    private static @Nullable FactoryData.FactoryRecord copiedFactoryBeside(Level level, FactoryData data,
                                                                          BlockPos pos, int sourceFactory) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            FactoryData.FactoryRecord neighbour = data.factoryWithEntranceCell(
                    level.dimension().location(),
                    neighbourPos
            );
            if (neighbour != null && neighbour.copiedFrom() == sourceFactory) {
                return neighbour;
            }
        }
        return null;
    }

    /** The entrance and its installed terminals/wires. Room contents are paid per cannon shot. */
    @Override
    public ItemRequirement getRequiredItems(BlockState state, @Nullable BlockEntity blockEntity) {
        List<ItemRequirement.StackRequirement> items = new ArrayList<>();
        boolean copiesRoom = false;
        add(items, new ItemStack(asItem()), ItemRequirement.ItemUseType.CONSUME, false);
        if (blockEntity instanceof RecursiveFactoryBlockEntity door) {
            if (RecursiveFactory.powerAvailable()) {
                var nodes = door.blueprintEntranceNodes();
                if (!nodes.isEmpty()) {
                    copiesRoom = true;
                    add(items, FactoryWires.requirements(nodes));
                }
                var wires = door.blueprintEntranceWires();
                if (!wires.isEmpty()) {
                    copiesRoom = true;
                    add(items, FactoryWireSchematics.requirements(wires));
                }
            }
        }
        return copiesRoom ? new FactorySchematicMaterials.Requirement(items)
                : new ItemRequirement(items);
    }

    /**
     * The factory a block that has not landed yet stands for: the blueprint file the block was cut out of,
     * or - for a block carried out of the world by Create's own blueprint and quill, which holds the
     * factory it stood in and none of the room around it - that factory, read out of the world here and
     * now, the same reading {@link #setPlacedBy} builds the copy out of. Null when the block is no door, or
     * names something that cannot be read.
     */
    public static @Nullable FactoryBlueprint blueprintBehind(MinecraftServer server,
                                                              RecursiveFactoryBlockEntity door) {
        String file = door.blueprintFile();
        if (file != null) {
            return FactoryBlueprint.read(server, file);
        }
        if (!door.hasFactoryId()) {
            return null;
        }
        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord source = takenInThisSave(data, door.blueprintOrigin())
                ? data.factory(door.getFactoryId())
                : null;
        if (source == null) {
            return null;
        }
        return FactoryBlueprint.captureAround(server, source, door.roomCell(), "factory #" + source.id());
    }

    /**
     * Folds one more thing a placement asks for into the list, counting it with the rest of its kind and cut
     * into whole stacks. Sixty-four of the same cobblestone are one thing to fetch, not sixty-four: a cannon
     * holds the requirement up against the containers beside it a stack at a time, so what a room costs is
     * asked for as the stacks it would take to pay for it. These stacks must be checked and paid together:
     * Create's normal per-stack simulation would count the same inventory again for each stack. The
     * factory requirement marker lets our cannon integration reserve the complete list before launch.
     */
    private static void add(List<ItemRequirement.StackRequirement> items, ItemRequirement requirement) {
        if (requirement == null || requirement.isEmpty() || requirement.isInvalid()) {
            // A block with no item form at all - water, or something another mod plants rather than places
            // - is not asked for, following Create's requirement handling.
            return;
        }
        for (ItemRequirement.StackRequirement required : requirement.getRequiredItems()) {
            add(items, required.stack, required.usage,
                    required instanceof ItemRequirement.StrictNbtStackRequirement);
        }
    }

    /**
     * One thing a placement asks for, put with the rest of its kind: what is already being asked for is
     * topped up, and what is left over starts further stacks. A block that is only itself with its own data
     * on it - a banner, say - is kept apart from the plain ones rather than counted in with them.
     */
    private static void add(List<ItemRequirement.StackRequirement> items, ItemStack stack,
                            ItemRequirement.ItemUseType usage, boolean strict) {
        if (stack.isEmpty()) {
            return;
        }
        int perStack = Math.max(1, Math.min(stack.getMaxStackSize(), 64));
        int remaining = stack.getCount();
        for (ItemRequirement.StackRequirement existing : items) {
            if (existing instanceof ItemRequirement.StrictNbtStackRequirement != strict
                    || existing.usage != usage || existing.stack.getCount() >= perStack
                    || !ItemStack.isSameItemSameComponents(existing.stack, stack)) {
                continue;
            }
            int take = Math.min(perStack - existing.stack.getCount(), remaining);
            existing.stack.grow(take);
            remaining -= take;
            if (remaining == 0) {
                return;
            }
        }
        while (remaining > 0) {
            int take = Math.min(perStack, remaining);
            ItemStack part = stack.copyWithCount(take);
            items.add(strict
                    ? new ItemRequirement.StrictNbtStackRequirement(part, usage)
                    : new ItemRequirement.StackRequirement(part, usage));
            remaining -= take;
        }
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
                && !SableAssembly.isMoving(level, pos)
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
