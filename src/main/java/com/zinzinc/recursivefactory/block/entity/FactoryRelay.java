package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.zinzinc.recursivefactory.block.FactoryBarrierBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.data.FaceMode;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

/**
 * The link between a factory's entrance block and the barrier wall of its room.
 *
 * <p>An item pushed into an endpoint is pushed out beside the other one, in the direction it was going:
 * a hopper feeding a barrier from inside the room drops its items next to the entrance block outside, and
 * a hopper feeding the entrance block outside drops its items beside the room's wall on the same side
 * (see {@link #resolveRemotes}).
 *
 * <p>Redstone is mirrored the same way, and an end drives exactly the faces a signal is leaving through:
 * fed from two sides at once, an entrance block answers on both of the room's sides. Only the far end
 * drives them - the end the signal went in through lights up and passes it on, but emits nothing itself,
 * so a signal fed into the entrance block from outside never comes back out of it.
 *
 * <p>It is mirrored the way redstone dust does it, strength and all: every face carries its own level
 * (see {@link EndpointBlockEntity#getInputPower}), the link between the two ends counts as one dust block,
 * and a dust sitting against either end is charged as if it were sitting against that one dust (see
 * {@link #readInputs}).
 *
 * <p>The entrance block's far end is a whole side of the room rather than one block: anything built along
 * the wall the thing came in through picks the link up, so the player does not have to find the one block
 * of the wall the link happens to land on.
 */
public final class FactoryRelay {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FactoryRelay() {
    }

    public static void transport(EndpointBlockEntity local) {
        if (local.getPendingStack().isEmpty() || local.getPendingInput() == null
                || !(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        List<RemoteEndpoint> remotes =
                resolveRemotes(localLevel, local, local.getPendingInput(), FaceMode.LOGISTICS);
        if (remotes.isEmpty()) {
            return;
        }

        Direction outputSide = local.getPendingInput().getOpposite();
        ItemStack waiting = local.getPendingStack();
        for (RemoteEndpoint remote : remotes) {
            BlockPos outputPos = remote.outputPos(outputSide);
            IItemHandler target = remote.level().getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    outputPos,
                    outputSide.getOpposite()
            );
            if (target == null) {
                continue;
            }

            ItemStack remainder = ItemHandlerHelper.insertItem(target, waiting, false);
            if (ItemStack.matches(remainder, waiting)) {
                // Something is there and would not take a single one of them - a full container, or a wall
                // block that is already carrying a stack of its own. Keep looking along the far end: taking
                // this for a transfer is what used to leave a stack shuttling between two endpoints that
                // each thought the other had it, without a word in the log.
                continue;
            }
            local.setTransportResult(remainder);
            local.noteBlockedTransport(null);
            return;
        }

        // Nowhere to put it, anywhere along the far end. The stack stays in the buffer, so the inserter
        // backs up instead of the item being lost, and the wait is reported once per face rather than once
        // per tick.
        if (local.noteBlockedTransport(outputSide)) {
            LOGGER.info("Item {} is waiting in {}: nothing at {} would take it ({} positions tried)",
                    waiting, local.getBlockPos(), remotes.get(0).outputPos(outputSide), remotes.size());
        }
    }

    /**
     * Pushes the fluid this end is holding into the far end, the way {@link #transport} pushes a stack: it
     * is handed to the very spots the link hands its items to - beside the entrance block outside for a
     * barrier, the free spot behind the wall for the entrance block - and what is not taken stays in the
     * buffer, so a pipe feeding the wall backs up instead of pouring the fluid away.
     *
     * <p>A tank sitting at the far end is what this fills, so a pipe inside the room feeds the tank built
     * against the wall outside, exactly as a hopper inside the room feeds the chest outside.
     *
     * <p>A Create pipe standing at the far end instead takes no fill at all and pulls what it is built
     * against on its own ({@link #waitingFluid}), so a spot with a pipe on it is left alone here and the
     * fluid is not reported as having nowhere to go.
     */
    public static void transportFluid(EndpointBlockEntity local) {
        if (local.getPendingFluid().isEmpty() || local.getPendingFluidInput() == null
                || !(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        List<RemoteEndpoint> remotes =
                resolveRemotes(localLevel, local, local.getPendingFluidInput(), FaceMode.FLUID);
        if (remotes.isEmpty()) {
            return;
        }

        Direction outputSide = local.getPendingFluidInput().getOpposite();
        FluidStack waiting = local.getPendingFluid();
        boolean piped = false;
        for (RemoteEndpoint remote : remotes) {
            BlockPos outputPos = remote.outputPos(outputSide);
            IFluidHandler target = remote.level().getCapability(
                    Capabilities.FluidHandler.BLOCK,
                    outputPos,
                    outputSide.getOpposite()
            );
            if (target == null) {
                // Not a tank: a pipe standing there is filled by pulling rather than by this push, so it
                // is not a spot the fluid has nowhere to go at.
                piped |= FluidPropagator.getPipe(remote.level(), outputPos) != null;
                continue;
            }

            int accepted = target.fill(waiting.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (accepted <= 0) {
                // A full tank further along the wall, or none at all: keep looking, the way items do.
                continue;
            }
            local.setFluidTransportResult(accepted);
            local.noteBlockedFluidTransport(null);
            return;
        }

        if (!piped && local.noteBlockedFluidTransport(outputSide)) {
            LOGGER.info("Fluid {} is waiting in {}: nothing at {} would take it ({} positions tried)",
                    waiting.getHoverName(), local.getBlockPos(), remotes.get(0).outputPos(outputSide),
                    remotes.size());
        }
    }

    /**
     * Takes up to {@code amount} out of the far end of a link, which is what an extracting block - a funnel,
     * a chute, a hopper - sitting against {@code face} of this end pulls. Items are taken from the very
     * spots the link hands its own items to (see {@link #transport}), so a container the link fills is the
     * one an extraction drains, and a room's wall answers along the whole side the pull came in through.
     *
     * <p>{@code face} is the side of this end the pulling block sits on. It picks the side of the room the
     * link answers on, exactly as it does for an item going the other way, and a pull that does not name a
     * side asks the room about all of them ({@code null} for the sides this end has nothing to say on).
     */
    public static ItemStack extract(EndpointBlockEntity local, @Nullable Direction face, int amount, boolean simulate) {
        if (amount <= 0 || !(local.getLevel() instanceof ServerLevel localLevel)) {
            return ItemStack.EMPTY;
        }
        for (Direction inputFace : face == null ? Direction.values() : new Direction[]{face}) {
            Direction outputSide = inputFace.getOpposite();
            for (RemoteEndpoint remote : resolveRemotes(localLevel, local, inputFace, FaceMode.LOGISTICS)) {
                IItemHandler target = remote.level().getCapability(
                        Capabilities.ItemHandler.BLOCK,
                        remote.outputPos(outputSide),
                        outputSide.getOpposite()
                );
                if (target == null) {
                    continue;
                }
                for (int slot = 0; slot < target.getSlots(); slot++) {
                    ItemStack taken = target.extractItem(slot, amount, simulate);
                    if (!taken.isEmpty()) {
                        return taken;
                    }
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Takes fluid out of the far end of a link, the same bargain {@link #extract} makes for items: what is
     * pulled comes from the very spots the link hands its own fluid to, so a tank the link fills is the one
     * an extraction drains, and {@code face} - the side of this end the pulling block sits on - picks the
     * side of the room the pull came in through. An empty {@code resource} asks for whatever is there.
     *
     * <p>Standing at that spot instead of a tank can be a Create pipe, and a pipe takes no fill at all: it
     * has no tank of its own, it only pulls from the blocks it is built against (see {@link #waitingFluid}).
     * Such a pipe drains straight from the fluid the far end is holding for this side of the link, so a
     * link feeds a pipe exactly as it feeds a tank - the fluid simply waits in the far end's buffer until
     * the pipe asks for it, instead of being pushed across.
     */
    public static FluidStack extractFluid(EndpointBlockEntity local, @Nullable Direction face,
                                          FluidStack resource, int amount, boolean simulate) {
        if (amount <= 0 || !(local.getLevel() instanceof ServerLevel localLevel)) {
            return FluidStack.EMPTY;
        }
        IFluidHandler.FluidAction action =
                simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
        for (Direction inputFace : face == null ? Direction.values() : new Direction[]{face}) {
            Direction outputSide = inputFace.getOpposite();
            for (RemoteEndpoint remote : resolveRemotes(localLevel, local, inputFace, FaceMode.FLUID)) {
                IFluidHandler target = remote.level().getCapability(
                        Capabilities.FluidHandler.BLOCK,
                        remote.outputPos(outputSide),
                        outputSide.getOpposite()
                );
                if (target != null) {
                    FluidStack taken = resource.isEmpty()
                            ? target.drain(amount, action)
                            : target.drain(resource.copyWithAmount(amount), action);
                    if (!taken.isEmpty()) {
                        return taken;
                    }
                }
                FluidStack waiting = waitingFluid(remote, outputSide, resource, amount, action);
                if (!waiting.isEmpty()) {
                    return waiting;
                }
            }
        }
        return FluidStack.EMPTY;
    }

    /**
     * The fluid the far end is holding for the side of the link {@code outputSide} points at: fluid pushed
     * into it from over there that has not left yet. A tank (or a basin, or any other block with a tank of
     * its own) standing where the link hands its fluid over is filled by {@link #transportFluid} and never
     * lets anything wait; a Create pipe standing there instead is not, because a pipe is not a tank and
     * takes no fill at all - it pulls what it is built against. Taking the fluid out of the far end's
     * buffer is what lets a pipe pull a link along, and it is the same direction the push would have gone
     * in, so a link's fluid still only ever leaves through the side it was going to leave through.
     */
    private static FluidStack waitingFluid(RemoteEndpoint remote, Direction outputSide, FluidStack resource,
                                           int amount, IFluidHandler.FluidAction action) {
        if (!(remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity far)
                || far.getPendingFluid().isEmpty()
                || far.getPendingFluidInput() != outputSide) {
            return FluidStack.EMPTY;
        }
        FluidStack waiting = far.getPendingFluid();
        if (!resource.isEmpty() && !FluidStack.isSameFluidSameComponents(waiting, resource)) {
            return FluidStack.EMPTY;
        }
        FluidStack taken = waiting.copyWithAmount(Math.min(amount, waiting.getAmount()));
        if (action.execute()) {
            far.setFluidTransportResult(taken.getAmount());
        }
        return taken;
    }

    /**
     * Hands the far end what each face of this end is being fed with now, and takes back the faces that
     * went quiet. {@code inputPower} is the whole picture, not a change: an entrance block fed from two
     * sides at once keeps both, which is what stops one of them from silently going dark.
     *
     * <p>The faces this end was already driving are kept in the block entity, so the far end can still be
     * found - and turned back off - after the input that chose it has gone away.
     */
    public static void syncPower(EndpointBlockEntity local, int[] inputPower) {
        boolean wasPowered = local.isOutputPowered();
        int[] previous = local.getInputPowers();
        local.setInputPowers(inputPower);
        updateOutputState(local);
        if (local.isOutputPowered() != wasPowered) {
            LOGGER.info("Relayed redstone at {}: powered={}, in={}, out={}", local.getBlockPos(),
                    local.isOutputPowered(), describe(inputPower), describe(local.getOutputPowers()));
        } else if (!Arrays.equals(previous, inputPower)) {
            LOGGER.debug("Relayed redstone strength at {}: in={}, out={}", local.getBlockPos(),
                    describe(inputPower), describe(local.getOutputPowers()));
        }

        if (!(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        // A side that stopped being fed (or that was swapped for another one) lets go of its wall first,
        // or the whole side it used to light up would stay lit for good. Every side is handled on its own,
        // at its own strength: losing one input must not take the other one's wall down with it.
        for (Direction input : Direction.values()) {
            int was = previous[input.ordinal()];
            int now = inputPower[input.ordinal()];
            if (was != now) {
                driveRemotes(localLevel, local, input, now);
            }
        }
    }

    /**
     * Drives the far end of one side of a link with {@code power}, or lets go of it when that is zero.
     * {@code inputFace} is the face of this end the redstone is coming in through, and it is what picks
     * the side of the room the link answers on: the far end is driven on the opposite face, and every
     * barrier of that side's wall is set to the same strength.
     */
    private static void driveRemotes(ServerLevel localLevel, EndpointBlockEntity local, Direction inputFace,
                                     int power) {
        Direction driven = inputFace.getOpposite();
        for (RemoteEndpoint remote : resolveRemotes(localLevel, local, inputFace, FaceMode.REDSTONE)) {
            if (remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity endpoint) {
                endpoint.setOutputPower(driven, power);
                updateOutputState(endpoint);
            }
        }
    }

    /**
     * Re-reads the redstone around one end and relays it to the other. Called from
     * {@code neighborChanged} and right after an entrance block has been placed.
     */
    public static void updateFromNeighbours(EndpointBlockEntity local) {
        Level level = local.getLevel();
        BlockPos pos = local.getBlockPos();
        if (level == null || level.isClientSide) {
            return;
        }

        int[] inputs = readInputs(level, pos, local);
        if (Arrays.equals(inputs, local.getInputPowers())) {
            return;
        }
        syncPower(local, inputs);
    }

    /**
     * Looks at both ends of a channel again after the entrance block's face {@code face} has been given
     * another mode. Everything that uses a channel reads the mode live, but redstone is event driven: a
     * face that has just become a redstone face would otherwise sit with a signal wired against it and
     * never pick it up, and one that has just stopped being one would keep driving the wall it was feeding.
     * The wall's own barriers are looked at as well, which covers the other direction - a dust line inside
     * the room feeding a wall the player has just closed off is let go of.
     */
    public static void onFaceModeChanged(EndpointBlockEntity entrance, Direction face) {
        updateFromNeighbours(entrance);
        if (!(entrance instanceof RecursiveFactoryBlockEntity) || !entrance.hasFactoryId()
                || !(entrance.getLevel() instanceof ServerLevel entranceLevel)) {
            return;
        }
        MinecraftServer server = entranceLevel.getServer();
        ServerLevel room = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        FactoryData.FactoryRecord record = server == null
                ? null
                : FactoryData.get(server).factory(entrance.getFactoryId());
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.cellAt(entrance.getBlockPos());
        if (room == null || record == null || cell == null) {
            return;
        }
        for (BlockPos wall : FactoryDimension.wallLine(record, cell, face)) {
            if (room.getBlockEntity(wall) instanceof EndpointBlockEntity barrier) {
                updateFromNeighbours(barrier);
            }
        }
    }

    /**
     * The signal an end emits in {@code direction}. Only the far end of a link emits: the end a signal went
     * in through lights up and passes it on, but drives nothing itself, so a signal fed into the entrance
     * block from outside does not come back out of it and the wall never drives its own input. Beyond that
     * it only drives the faces it is wired to output on - one per side the signal came in through, each at
     * the strength it was fed with (see {@link EndpointBlockEntity#getOutputPower}); emitting on every face
     * would light up the rest of the wall and relay the whole room back into itself.
     */
    public static int emittedSignal(BlockState state, @Nullable BlockEntity blockEntity, Direction direction) {
        if (!(blockEntity instanceof EndpointBlockEntity endpoint)
                || !endpoint.isRemotePowered()
                || !state.hasProperty(BlockStateProperties.POWERED)
                || !state.getValue(BlockStateProperties.POWERED)) {
            return 0;
        }
        return endpoint.getOutputPower(direction.getOpposite());
    }

    /**
     * The strength feeding every face of this end, worked out the way a dust block works out its own
     * level: a dust hands the next dust one less than its own power - the link between the two ends
     * counts as one such dust, so a signal crossing it is worth exactly one dust block - while anything
     * else (a repeater, a torch, a lever, a redstone block) comes through at the strength it reports.
     *
     * <p>What is skipped: the faces this end is driving, because whatever sits there reports our own power
     * straight back and reading it back would latch us on; and neighbours that are themselves an end (a
     * barrier or an entrance block), because a closed wall of barriers would otherwise relay its own
     * signal around the ring of the room and stay lit for good. An end that is only passing something on
     * drives nothing, so for it there is nothing to skip.
     */
    private static int[] readInputs(Level level, BlockPos pos, EndpointBlockEntity local) {
        int[] inputs = new int[Direction.values().length];
        for (Direction direction : Direction.values()) {
            if (!faceCarries(local, direction, FaceMode.REDSTONE)) {
                // Not a redstone face: whatever is built against it is not wired to this factory.
                continue;
            }
            if (local.getOutputPower(direction) > 0) {
                continue;
            }
            BlockPos neighbourPos = pos.relative(direction);
            BlockState neighbourState = level.getBlockState(neighbourPos);
            if (isRelay(neighbourState)) {
                continue;
            }
            int power = level.getSignal(neighbourPos, direction);
            if (neighbourState.is(Blocks.REDSTONE_WIRE)) {
                power--;
            }
            inputs[direction.ordinal()] = Mth.clamp(power, 0, 15);
        }
        return inputs;
    }

    /** The live faces of a strength table, for the log lines. */
    private static String describe(int[] powers) {
        StringBuilder builder = new StringBuilder("[");
        for (Direction direction : Direction.values()) {
            int power = powers[direction.ordinal()];
            if (power > 0) {
                if (builder.length() > 1) {
                    builder.append(' ');
                }
                builder.append(direction.getSerializedName()).append('=').append(power);
            }
        }
        return builder.append(']').toString();
    }

    /**
     * Mirrors the end's state onto its block: whether it is lit, and the face its marker sits on. Only one
     * face fits in the block state, so an end that emits on several marks the strongest of them (see
     * {@link EndpointBlockEntity#markerFace()}); the redstone itself is not limited to it.
     */
    public static void updateOutputState(EndpointBlockEntity endpoint) {
        Level level = endpoint.getLevel();
        BlockPos pos = endpoint.getBlockPos();
        if (level == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        BlockState updated = state;
        boolean desired = endpoint.isOutputPowered();
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED) != desired) {
            updated = updated.setValue(BlockStateProperties.POWERED, desired);
        }
        Direction face = endpoint.markerFace();
        if (face != null && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING) != face) {
            updated = updated.setValue(BlockStateProperties.FACING, face);
        }
        if (updated != state) {
            level.setBlock(pos, updated, 3);
        }
    }

    private static boolean isRelay(BlockState state) {
        return state.getBlock() instanceof RecursiveFactoryBlock || state.getBlock() instanceof FactoryBarrierBlock;
    }

    /**
     * Whether the link carries {@code mode}'s kind of thing across {@code face} of {@code local}.
     *
     * <p>The modes live on the entrance block and name the sides of the room: its north face is the room's
     * north side. The barrier wall of a room has no modes of its own - the wall is not the gate, the face of
     * the entrance block that leads to it is - and a barrier's own face points into the room where the
     * matching face of the entrance block points out of it, so a barrier answers for the opposite face. An
     * item pushed at the room's north wall from inside therefore leaves by the entrance block's north face,
     * which is the face whose mode has to allow it.
     *
     * <p>A face nobody has said anything about - one whose entrance block cannot be reached, or a block that
     * is not part of a factory at all - counts as open: there is no gate to hold anything back, and the walk
     * to the far end will find nothing to hand it to anyway.
     */
    public static boolean faceCarries(EndpointBlockEntity local, @Nullable Direction face, FaceMode mode) {
        if (face == null) {
            return false;
        }
        if (local instanceof RecursiveFactoryBlockEntity entrance) {
            return entrance.faceCarries(face, mode);
        }
        EndpointBlockEntity entrance = entranceOf(local);
        return entrance == null || entrance.faceCarries(face.getOpposite(), mode);
    }

    /** The entrance block a room's barrier relays to, or null while it cannot be reached. */
    private static @Nullable EndpointBlockEntity entranceOf(EndpointBlockEntity barrier) {
        if (!(barrier.getLevel() instanceof ServerLevel level) || !barrier.hasFactoryId()) {
            return null;
        }
        MinecraftServer server = level.getServer();
        FactoryData.FactoryRecord record = server == null
                ? null
                : FactoryData.get(server)
                        .factory(barrier.getFactoryId());
        if (record == null || record.entranceDimension() == null) {
            return null;
        }
        ServerLevel entranceLevel = server.getLevel(dimensionKey(record.entranceDimension()));
        return entranceLevel != null
                && entranceLevel.getBlockEntity(record.entrancePos()) instanceof EndpointBlockEntity entrance
                ? entrance
                : null;
    }

    /**
     * The far end of a factory's link. A barrier in the room reaches the entrance block outside. The
     * entrance block reaches the room's wall on the side the thing came in through: {@code inputFace} is
     * the face of the entrance block the item or the signal entered on, and it is what picks the side of
     * the room the link answers on (see {@link FactoryDimension#wallLine}). Fed from the north, for
     * instance, things come out along the room's north wall, facing into the room.
     *
     * <p>Nothing comes back for a face that is not set to {@code mode}: a face carries one kind of thing
     * at a time (see {@link FaceMode}), so a mouth that is not the mouth for what is travelling is the
     * same as no mouth at all.
     */
    private static List<RemoteEndpoint> resolveRemotes(ServerLevel localLevel, EndpointBlockEntity local,
                                                       @Nullable Direction inputFace, FaceMode mode) {
        if (!faceCarries(local, inputFace, mode)) {
            return List.of();
        }
        MinecraftServer server = localLevel.getServer();
        if (server == null) {
            return List.of();
        }
        FactoryData.FactoryRecord record = FactoryData.get(server).factory(local.getFactoryId());
        if (record == null || record.cells().isEmpty()) {
            return List.of();
        }

        if (local.getBlockState().getBlock() instanceof FactoryBarrierBlock) {
            if (record.entranceDimension() == null) {
                return List.of();
            }
            ServerLevel entranceLevel = server.getLevel(dimensionKey(record.entranceDimension()));
            if (entranceLevel == null) {
                return List.of();
            }
            RemoteEndpoint entrance =
                    validEndpoint(entranceLevel, record.entrancePos(), RecursiveFactoryBlock.class);
            return entrance == null ? List.of() : List.of(entrance);
        }

        if (inputFace == null) {
            return List.of();
        }
        FactoryData.FactoryRecord.Cell cell = record.cellAt(local.getBlockPos());
        if (cell == null) {
            cell = record.anchorCell();
        }
        ServerLevel roomLevel = server.getLevel(FactoryDimension.LEVEL_KEY);
        if (cell == null || roomLevel == null) {
            return List.of();
        }
        List<RemoteEndpoint> remotes = new ArrayList<>();
        for (BlockPos wall : FactoryDimension.wallLine(record, cell, inputFace)) {
            RemoteEndpoint endpoint = validWall(roomLevel, record, wall, inputFace);
            if (endpoint != null) {
                remotes.add(endpoint);
            }
        }
        return remotes;
    }

    private static @Nullable RemoteEndpoint validEndpoint(ServerLevel level, BlockPos pos, Class<?> blockClass) {
        return blockClass.isInstance(level.getBlockState(pos).getBlock())
                ? new RemoteEndpoint(level, pos, null)
                : null;
    }

    /**
     * The room side of a link: a wall block, together with the free spot behind it that what comes in
     * through the wall lands on. The spot is walked out rather than stepped to, so a link never hands its
     * items to the wall of the other side at a corner (see {@link FactoryDimension#inward}).
     */
    private static @Nullable RemoteEndpoint validWall(ServerLevel level, FactoryData.FactoryRecord record,
                                                      BlockPos wall, Direction direction) {
        if (!(level.getBlockState(wall).getBlock() instanceof FactoryBarrierBlock)) {
            return null;
        }
        BlockPos entry = FactoryDimension.inward(record, wall, direction);
        return entry == null ? null : new RemoteEndpoint(level, wall, entry);
    }

    private static ResourceKey<Level> dimensionKey(ResourceLocation location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    public static Optional<EndpointBlockEntity> endpoint(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof EndpointBlockEntity endpoint ? Optional.of(endpoint) : Optional.empty();
    }

    /**
     * A far end, and where it puts what it is given: the free spot behind a wall block of the room, or -
     * for the entrance block outside, which has no room behind it - the block beside it that the thing is
     * travelling out towards.
     */
    private record RemoteEndpoint(ServerLevel level, BlockPos pos, @Nullable BlockPos entry) {
        BlockPos outputPos(Direction outputSide) {
            return entry == null ? pos.relative(outputSide) : entry;
        }
    }
}
