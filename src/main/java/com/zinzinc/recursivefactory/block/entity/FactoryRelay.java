package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.MirrorFactoryBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
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

public final class FactoryRelay {
    private FactoryRelay() {
    }

    public static void transport(EndpointBlockEntity local) {
        if (local.getPendingStack().isEmpty() || local.getPendingInput() == null || !(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        RemoteEndpoint remote = resolveRemote(localLevel, local.getFactoryId(), isMirror(local));
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
        local.setLocalPowered(localPowered);
        if (localPowered && inputDirection != null) {
            local.setOutputDirection(inputDirection.getOpposite());
        }
        updateOutputState(local);

        if (!(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }

        RemoteEndpoint remote = resolveRemote(localLevel, local.getFactoryId(), isMirror(local));
        if (remote == null) {
            return;
        }

        if (remote.level().getBlockEntity(remote.pos()) instanceof EndpointBlockEntity remoteEndpoint) {
            remoteEndpoint.setRemotePowered(localPowered);
            if (localPowered && inputDirection != null) {
                remoteEndpoint.setOutputDirection(inputDirection.getOpposite());
            }
            updateOutputState(remoteEndpoint);
        }
    }

    public static void updateOutputState(EndpointBlockEntity endpoint) {
        Level level = endpoint.getLevel();
        BlockPos pos = endpoint.getBlockPos();
        if (level == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        boolean desired = endpoint.isOutputPowered();
        if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED) != desired) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, desired), 3);
        }
    }

    public static @Nullable Direction strongestInputDirection(Level level, BlockPos pos) {
        Direction strongestDirection = null;
        int strongestSignal = 0;
        for (Direction direction : Direction.values()) {
            int signal = level.getSignal(pos.relative(direction), direction);
            if (signal > strongestSignal) {
                strongestDirection = direction;
                strongestSignal = signal;
            }
        }
        return strongestDirection;
    }

    private static boolean isMirror(EndpointBlockEntity endpoint) {
        return endpoint instanceof MirrorFactoryBlockEntity;
    }

    private static @Nullable RemoteEndpoint resolveRemote(ServerLevel localLevel, int factoryId, boolean localIsMirror) {
        MinecraftServer server = localLevel.getServer();
        FactoryData.FactoryRecord record = FactoryData.get(server).factory(factoryId);
        if (record == null) {
            return null;
        }

        ResourceKey<Level> dimensionKey;
        BlockPos pos;
        if (localIsMirror) {
            if (record.entranceDimension() == null) {
                return null;
            }
            dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, record.entranceDimension());
            pos = record.entrancePos();
        } else {
            if (record.mirrorDimension() == null) {
                return null;
            }
            dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, record.mirrorDimension());
            pos = record.mirrorPos();
        }

        ServerLevel remoteLevel = server.getLevel(dimensionKey);
        if (remoteLevel == null) {
            return null;
        }

        BlockState state = remoteLevel.getBlockState(pos);
        boolean valid = localIsMirror
                ? state.getBlock() instanceof RecursiveFactoryBlock
                : state.getBlock() instanceof MirrorFactoryBlock;
        return valid ? new RemoteEndpoint(remoteLevel, pos) : null;
    }

    public static Optional<EndpointBlockEntity> endpoint(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof EndpointBlockEntity endpoint ? Optional.of(endpoint) : Optional.empty();
    }

    private record RemoteEndpoint(ServerLevel level, BlockPos pos) {
    }
}
