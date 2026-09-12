package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber
public final class PlayerFactoryEvents {
    private static final double SNEAK_ENTER_RANGE = 2.0D;

    private PlayerFactoryEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }

        if (player.level().dimension() == FactoryDimension.LEVEL_KEY) {
            if (player.level().getServer() == null) {
                return;
            }
            FactoryData data = FactoryData.get(player.level().getServer());
            FactoryData.FactoryRecord record = data.factoryAt(player.blockPosition());
            if (record == null) {
                FactoryTeleporter.exitFromEdge(player);
            }
            return;
        }

        if (player.isShiftKeyDown() && FactoryTeleporter.canAutoEnter(player)) {
            findNearbyFactory(player).ifPresent(entry -> {
                Direction approachDirection = FactoryDimension.horizontalDirectionFrom(
                        entry.pos(),
                        player.getX(),
                        player.getZ()
                );
                FactoryTeleporter.enter(player, entry.factoryId(), approachDirection);
            });
        }
    }

    private static Optional<FactoryEntry> findNearbyFactory(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        return BlockPos.betweenClosedStream(center.offset(-2, -1, -2), center.offset(2, 1, 2))
                .filter(pos -> player.position().closerThan(
                        net.minecraft.world.phys.Vec3.atCenterOf(pos),
                        SNEAK_ENTER_RANGE
                ))
                .filter(pos -> player.level().getBlockState(pos).is(ModBlocks.RECURSIVE_FACTORY.get()))
                .map(pos -> {
                    BlockEntity blockEntity = player.level().getBlockEntity(pos);
                    if (blockEntity instanceof RecursiveFactoryBlockEntity factory
                            && factory.hasFactoryId()) {
                        return new FactoryEntry(pos.immutable(), factory.getFactoryId());
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingDouble(entry -> player.position().distanceToSqr(
                        net.minecraft.world.phys.Vec3.atCenterOf(entry.pos())
                )));
    }

    private record FactoryEntry(BlockPos pos, int factoryId) {
    }
}
