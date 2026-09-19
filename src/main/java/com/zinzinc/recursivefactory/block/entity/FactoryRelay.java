package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.FactoryBarrierBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
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
 * <p>Redstone is mirrored the same way, and each end only ever drives the one face the signal is leaving
 * through. Only the far end drives it: the end the signal went in through lights up and passes it on, but
 * emits nothing itself, so a signal fed into the entrance block from outside never comes back out of it.
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

        List<RemoteEndpoint> remotes = resolveRemotes(localLevel, local, local.getPendingInput());
        if (remotes.isEmpty()) {
            return;
        }

        Direction outputSide = local.getPendingInput().getOpposite();
        for (RemoteEndpoint remote : remotes) {
            BlockPos outputPos = remote.pos().relative(outputSide);
            IItemHandler target = remote.level().getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    outputPos,
                    outputSide.getOpposite()
            );
            if (target == null) {
                continue;
            }

            ItemStack remainder = ItemHandlerHelper.insertItem(target, local.getPendingStack(), false);
            local.setTransportResult(remainder);
            local.noteBlockedTransport(null);
            return;
        }

        // Nowhere to put it, anywhere along the far end. The stack stays in the buffer, so the inserter
        // backs up instead of the item being lost, and the wait is reported once per face rather than once
        // per tick.
        if (local.noteBlockedTransport(outputSide)) {
            LOGGER.info("Item {} is waiting in {}: no item handler beside {} ({} positions tried)",
                    local.getPendingStack(), local.getBlockPos(),
                    remotes.get(0).pos().relative(outputSide), remotes.size());
        }
    }

    public static void syncPower(EndpointBlockEntity local, boolean localPowered, @Nullable Direction inputDirection) {
        boolean wasPowered = local.isOutputPowered();
        Direction previous = local.getOutputDirection();
        // The way the link runs: the opposite of the face a signal is coming in through, and the face this
        // end was already driving once the input is gone. Keeping it in the block entity is what lets the
        // far end still be found - and turned back off - after the input that chose it has gone away.
        Direction driven = localPowered && inputDirection != null
                ? inputDirection.getOpposite()
                : previous;
        local.setLocalPowered(localPowered);
        if (localPowered && inputDirection != null) {
            local.setOutputDirection(driven);
        } else if (!localPowered && !local.isRemotePowered()) {
            local.setOutputDirection(null);
        }
        updateOutputState(local);
        if (local.isOutputPowered() != wasPowered) {
            LOGGER.info("Relayed redstone at {}: powered={}, input={}, output face={}", local.getBlockPos(),
                    local.isOutputPowered(), inputDirection, local.getOutputDirection());
        }

        if (!(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        // The side the link answers on moved while the input stayed on: let go of the old one first, or
        // the whole wall it used to light up would stay lit for good.
        if (previous != null && previous != driven
                && local.getBlockState().getBlock() instanceof RecursiveFactoryBlock) {
            clearRemotes(localLevel, local, previous.getOpposite());
        }

        for (RemoteEndpoint remote : resolveRemotes(localLevel, local,
                driven == null ? null : driven.getOpposite())) {
            if (remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity remoteEndpoint) {
                remoteEndpoint.setRemotePowered(localPowered);
                if (localPowered && driven != null) {
                    remoteEndpoint.setOutputDirection(driven);
                } else if (!localPowered && !remoteEndpoint.isLocalPowered()) {
                    remoteEndpoint.setOutputDirection(null);
                }
                updateOutputState(remoteEndpoint);
            }
        }
    }

    /** Lets go of one side of a room, used when an entrance block answers on a different face instead. */
    private static void clearRemotes(ServerLevel localLevel, EndpointBlockEntity local,
                                     @Nullable Direction inputFace) {
        for (RemoteEndpoint remote : resolveRemotes(localLevel, local, inputFace)) {
            if (remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity endpoint) {
                if (!endpoint.isLocalPowered()) {
                    endpoint.setOutputDirection(null);
                }
                endpoint.setRemotePowered(false);
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

        // The face we are driving cannot be an input: whatever is sitting there (dust, a repeater, ...)
        // reports that power straight back, and reading it back would latch us on. An end that is only
        // passing something on drives nothing, so for it there is no face to skip.
        Direction emitting = local.isRemotePowered() ? local.getOutputDirection() : null;
        Direction input = strongestInputDirection(level, pos, emitting);
        boolean powered = input != null;
        boolean unchanged = powered == local.isLocalPowered()
                && (!powered || input.getOpposite() == local.getOutputDirection());
        if (unchanged) {
            return;
        }
        syncPower(local, powered, input);
    }

    /**
     * The signal an end emits in {@code direction}. Only the far end of a link emits: the end a signal went
     * in through lights up and passes it on, but drives nothing itself, so a signal fed into the entrance
     * block from outside does not come back out of it and the wall never drives its own input. Beyond that
     * it is a diode and only ever drives the one face it is wired to output on; emitting on every face
     * would light up the rest of the wall and relay the whole room back into itself.
     */
    public static int emittedSignal(BlockState state, @Nullable BlockEntity blockEntity, Direction direction) {
        if (!(blockEntity instanceof EndpointBlockEntity endpoint)
                || !endpoint.isRemotePowered()
                || !state.hasProperty(BlockStateProperties.POWERED)
                || !state.getValue(BlockStateProperties.POWERED)) {
            return 0;
        }
        return endpoint.getOutputDirection() == direction.getOpposite() ? 15 : 0;
    }

    /**
     * Mirrors the end's state onto its block: whether it is lit, and the face it outputs on. The face
     * is part of the block state as well so the block model can point at it.
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
        Direction face = endpoint.getOutputDirection();
        if (face != null && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING) != face) {
            updated = updated.setValue(BlockStateProperties.FACING, face);
        }
        if (updated != state) {
            level.setBlock(pos, updated, 3);
        }
    }

    /**
     * The direction of the strongest signal driving this position, or {@code null} when nothing does.
     * {@code ignoredFace} is skipped, and so is every neighbour that is itself an end (a barrier or an
     * entrance block): a closed wall of barriers would otherwise relay its own signal around the ring
     * of the room and stay lit forever.
     */
    public static @Nullable Direction strongestInputDirection(Level level, BlockPos pos,
                                                              @Nullable Direction ignoredFace) {
        Direction strongestDirection = null;
        int strongestSignal = 0;
        for (Direction direction : Direction.values()) {
            if (direction == ignoredFace) {
                continue;
            }
            BlockPos neighbourPos = pos.relative(direction);
            if (isRelay(level.getBlockState(neighbourPos))) {
                continue;
            }
            int signal = level.getSignal(neighbourPos, direction);
            if (signal > strongestSignal) {
                strongestDirection = direction;
                strongestSignal = signal;
            }
        }
        return strongestDirection;
    }

    private static boolean isRelay(BlockState state) {
        return state.getBlock() instanceof RecursiveFactoryBlock || state.getBlock() instanceof FactoryBarrierBlock;
    }

    /**
     * The far end of a factory's link. A barrier in the room reaches the entrance block outside. The
     * entrance block reaches the room's wall on the side the thing came in through: {@code inputFace} is
     * the face of the entrance block the item or the signal entered on, and it is what picks the side of
     * the room the link answers on (see {@link FactoryDimension#wallLine}). Fed from the north, for
     * instance, things come out along the room's north wall, facing into the room.
     */
    private static List<RemoteEndpoint> resolveRemotes(ServerLevel localLevel, EndpointBlockEntity local,
                                                       @Nullable Direction inputFace) {
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
            RemoteEndpoint endpoint = validEndpoint(roomLevel, wall, FactoryBarrierBlock.class);
            if (endpoint != null) {
                remotes.add(endpoint);
            }
        }
        return remotes;
    }

    private static @Nullable RemoteEndpoint validEndpoint(ServerLevel level, BlockPos pos, Class<?> blockClass) {
        return blockClass.isInstance(level.getBlockState(pos).getBlock())
                ? new RemoteEndpoint(level, pos)
                : null;
    }

    private static ResourceKey<Level> dimensionKey(ResourceLocation location) {
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    public static Optional<EndpointBlockEntity> endpoint(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof EndpointBlockEntity endpoint ? Optional.of(endpoint) : Optional.empty();
    }

    private record RemoteEndpoint(ServerLevel level, BlockPos pos) {
    }
}
