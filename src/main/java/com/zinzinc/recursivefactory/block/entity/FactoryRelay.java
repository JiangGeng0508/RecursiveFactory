package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.MirrorFactoryBlock;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.simibubi.create.api.packager.InventoryIdentifier;
import net.createmod.catnip.math.BlockFace;
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
        if (!(local.getLevel() instanceof ServerLevel localLevel) || !local.hasFactoryId()) return;
        RemoteEndpoint remote = resolveRemote(localLevel, local.getFactoryId(), isMirror(local));
        if (remote == null) return;
        for (int slot = 0; slot < EndpointBlockEntity.PENDING_SLOTS; slot++) {
            ItemStack waiting = local.getPendingStack(slot);
            Direction input = local.getPendingInput(slot);
            if (waiting.isEmpty() || input == null) continue;
            BlockPos outputPos = remote.pos().relative(input.getOpposite());
            if (!remote.level().isLoaded(outputPos) || remote.level().getBlockEntity(outputPos) == local) continue;
            IItemHandler target = remote.level().getCapability(Capabilities.ItemHandler.BLOCK, outputPos, input);
            if (target != null) local.setTransportResult(slot, ItemHandlerHelper.insertItem(target, waiting, false));
        }
    }

    static List<IItemHandler> itemSources(EndpointBlockEntity local, @Nullable Direction face) {
        List<ItemSource> sources = new ArrayList<>();
        collectItemSources(local, face, new HashSet<>(), sources);
        return sources.stream().map(ItemSource::handler).toList();
    }

    private record ItemRoute(EndpointBlockEntity endpoint, Direction face) { }
    private record ItemSource(ServerLevel level, InventoryIdentifier inventory, IItemHandler handler) { }

    private static void collectItemSources(EndpointBlockEntity local, @Nullable Direction face,
                                           Set<ItemRoute> path, List<ItemSource> sources) {
        if (!(local.getLevel() instanceof ServerLevel localLevel)) {
            return;
        }
        for (Direction inputFace : face == null ? Direction.values() : new Direction[]{face}) {
            ItemRoute route = new ItemRoute(local, inputFace);
            if (!path.add(route)) {
                continue;
            }
            Direction outputSide = inputFace.getOpposite();
            RemoteEndpoint resolved = resolveRemote(localLevel, local.getFactoryId(), isMirror(local));
            for (RemoteEndpoint remote : resolved == null ? List.<RemoteEndpoint>of() : List.of(resolved)) {
                BlockPos outputPos = remote.pos().relative(outputSide);
                if (!remote.level().isLoaded(outputPos)) continue;
                IItemHandler target = remote.level().getCapability(
                        Capabilities.ItemHandler.BLOCK,
                        outputPos,
                        outputSide.getOpposite()
                );
                if (target == null) {
                    continue;
                }
                if (remote.level().getBlockEntity(outputPos) instanceof EndpointBlockEntity next) {
                    // Follow nested links without exposing their incoming buffers or recursing in a loop.
                    collectItemSources(next, outputSide.getOpposite(), path, sources);
                } else {
                    BlockFace access = new BlockFace(outputPos, outputSide.getOpposite());
                    boolean duplicate = sources.stream().anyMatch(source -> source.level() == remote.level()
                            && (source.handler().equals(target) || source.inventory().contains(access)));
                    if (!duplicate) {
                        // A double chest or vault can touch several wall positions. Exposing it twice
                        // would let exact-count extraction simulations spend the same items twice.
                        InventoryIdentifier inventory = InventoryIdentifier.get(remote.level(), access);
                        if (inventory == null) {
                            inventory = new InventoryIdentifier.MultiFace(outputPos, Set.of(access.getFace()));
                        }
                        sources.add(new ItemSource(remote.level(), inventory, target));
                    }
                }
            }
            path.remove(route);
        }
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
        if (remoteLevel == null || !remoteLevel.isLoaded(pos)) {
            return null;
        }

        BlockState state = remoteLevel.getBlockState(pos);
        boolean valid = localIsMirror
                ? state.getBlock() instanceof RecursiveFactoryBlock
                : state.getBlock() instanceof MirrorFactoryBlock;
        return valid && remoteLevel.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint
                && endpoint.getFactoryId() == factoryId ? new RemoteEndpoint(remoteLevel, pos) : null;
    }

    public static Optional<EndpointBlockEntity> endpoint(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof EndpointBlockEntity endpoint ? Optional.of(endpoint) : Optional.empty();
    }

    private record RemoteEndpoint(ServerLevel level, BlockPos pos) {
    }
}
