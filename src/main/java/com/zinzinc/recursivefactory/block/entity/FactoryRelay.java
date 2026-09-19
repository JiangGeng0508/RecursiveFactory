package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.FactoryBarrierBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
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
 * <p>An item pushed into an endpoint is pushed out beside the other one, on the opposite face: a hopper
 * feeding a barrier from inside the room drops its items next to the entrance block outside, and a
 * hopper feeding the entrance block outside drops its items next to the room's port barrier.
 *
 * <p>Redstone is mirrored the same way. Each end only ever drives the single face the signal left
 * through -- like a diode, nothing is emitted back at the input.
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

        RemoteEndpoint remote = resolveRemote(localLevel, local);
        if (remote == null) {
            return;
        }

        Direction outputSide = local.getPendingInput().getOpposite();
        BlockPos outputPos = remote.pos().relative(outputSide);
        IItemHandler target = remote.level().getCapability(
                Capabilities.ItemHandler.BLOCK,
                outputPos,
                outputSide.getOpposite()
        );
        if (target == null) {
            return;
        }

        ItemStack remainder = ItemHandlerHelper.insertItem(target, local.getPendingStack(), false);
        local.setTransportResult(remainder);
    }

    public static void syncPower(EndpointBlockEntity local, boolean localPowered, @Nullable Direction inputDirection) {
        boolean wasPowered = local.isOutputPowered();
        local.setLocalPowered(localPowered);
        if (localPowered && inputDirection != null) {
            local.setOutputDirection(inputDirection.getOpposite());
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

        RemoteEndpoint remote = resolveRemote(localLevel, local);
        if (remote == null) {
            return;
        }

        if (remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity remoteEndpoint) {
            remoteEndpoint.setRemotePowered(localPowered);
            if (localPowered && inputDirection != null) {
                remoteEndpoint.setOutputDirection(inputDirection.getOpposite());
            } else if (!localPowered && !remoteEndpoint.isLocalPowered()) {
                remoteEndpoint.setOutputDirection(null);
            }
            updateOutputState(remoteEndpoint);
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

        // The face we are emitting from cannot be an input: whatever it drives there (dust, a
        // repeater, ...) reports that power straight back, and reading it back would latch us on.
        Direction emitting = local.isOutputPowered() ? local.getOutputDirection() : null;
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
     * The signal an end emits in {@code direction}. Like a diode it only ever drives the one face it
     * is wired to output on; emitting on every face would light up the rest of the wall (and its own
     * input) and relay the whole room back into itself.
     */
    public static int emittedSignal(BlockState state, @Nullable BlockEntity blockEntity, Direction direction) {
        if (!(blockEntity instanceof EndpointBlockEntity endpoint)
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
     * The far end of a factory's link. A barrier in the room reaches the entrance block outside; the
     * entrance block reaches the room's single port barrier, which is the one place the return path can
     * land no matter how many barrier blocks the wall has.
     */
    private static @Nullable RemoteEndpoint resolveRemote(ServerLevel localLevel, EndpointBlockEntity local) {
        MinecraftServer server = localLevel.getServer();
        if (server == null) {
            return null;
        }
        FactoryData.FactoryRecord record = FactoryData.get(server).factory(local.getFactoryId());
        if (record == null || record.cells().isEmpty()) {
            return null;
        }

        if (local.getBlockState().getBlock() instanceof FactoryBarrierBlock) {
            if (record.entranceDimension() == null) {
                return null;
            }
            ServerLevel entranceLevel = server.getLevel(dimensionKey(record.entranceDimension()));
            return entranceLevel == null
                    ? null
                    : validEndpoint(entranceLevel, record.entrancePos(), RecursiveFactoryBlock.class);
        }

        BlockPos port = FactoryDimension.portPos(record);
        if (port == null) {
            return null;
        }
        ServerLevel roomLevel = server.getLevel(FactoryDimension.LEVEL_KEY);
        return roomLevel == null
                ? null
                : validEndpoint(roomLevel, port, FactoryBarrierBlock.class);
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
