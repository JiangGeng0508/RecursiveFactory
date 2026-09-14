package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ReturnStackData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public final class FactoryTeleporter {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Long enough that landing beside the entrance block does not immediately pull the player back in
     * while they are still sneaking.
     */
    private static final long AUTO_ENTER_COOLDOWN_TICKS = 100L;
    private static final int SAFE_SPOT_RADIUS = 2;
    private static final int[] SAFE_SPOT_Y_OFFSETS = {0, 1, -1, 2, 3, 4};
    private static final Map<UUID, Long> AUTO_ENTER_COOLDOWN_UNTIL = new HashMap<>();

    private FactoryTeleporter() {
    }

    public static boolean canAutoEnter(ServerPlayer player) {
        long cooldownUntil = AUTO_ENTER_COOLDOWN_UNTIL.getOrDefault(player.getUUID(), 0L);
        return player.level().getGameTime() >= cooldownUntil;
    }

    public static boolean enter(ServerPlayer player, int factoryId) {
        MinecraftServer server = player.getServer();
        ServerLevel targetLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        FactoryData.FactoryRecord record = server == null ? null : FactoryData.get(server).factory(factoryId);
        if (targetLevel == null || record == null) {
            player.sendSystemMessage(Component.translatable("message.recursivefactory.missing"));
            return false;
        }

        FactoryDimension.prepare(targetLevel, record);
        pushReturnPoint(player);

        BlockPos preferred = FactoryDimension.entryTarget(record, player.getX(), player.getZ());
        BlockPos target = standableSpot(targetLevel, preferred);
        if (target == null) {
            target = preferred;
        }

        LOGGER.info("Entering factory #{} at {} from {}", factoryId, target, player.blockPosition());
        player.teleportTo(
                targetLevel,
                target.getX() + 0.5D,
                target.getY() + 0.1D,
                target.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot()
        );
        player.sendSystemMessage(Component.translatable("message.recursivefactory.entered", factoryId));
        return true;
    }

    public static boolean exit(ServerPlayer player, int factoryId) {
        MinecraftServer server = player.getServer();
        FactoryData.FactoryRecord record = server == null ? null : FactoryData.get(server).factory(factoryId);
        if (server == null || record == null) {
            player.sendSystemMessage(Component.translatable("message.recursivefactory.missing"));
            return false;
        }

        ReturnStackData stack = player.getData(ModAttachments.RETURN_STACK.get());
        ReturnStackData.ReturnPoint returnPoint = stack.isEmpty() ? null : stack.peek();
        if (!stack.isEmpty()) {
            player.setData(ModAttachments.RETURN_STACK.get(), stack.pop());
        }

        ResourceKey<Level> entranceKey = record.entranceDimension() == null
                ? Level.OVERWORLD
                : ResourceKey.create(Registries.DIMENSION, record.entranceDimension());
        ServerLevel entranceLevel = server.getLevel(entranceKey);
        BlockPos target = entranceLevel == null
                ? null
                : standableSpot(entranceLevel, FactoryDimension.exitTarget(record, player.getX(), player.getZ()));

        ServerLevel targetLevel;
        double x;
        double y;
        double z;
        if (target != null) {
            targetLevel = entranceLevel;
            x = target.getX() + 0.5D;
            y = target.getY() + 0.1D;
            z = target.getZ() + 0.5D;
        } else {
            // The entrance is walled in, unloaded or gone, so fall back to where the player came from.
            ServerLevel remembered = returnPoint == null ? null : server.getLevel(returnPoint.dimensionKey());
            if (remembered != null) {
                targetLevel = remembered;
                x = returnPoint.x();
                y = returnPoint.y();
                z = returnPoint.z();
            } else {
                targetLevel = server.overworld();
                BlockPos spawn = targetLevel.getSharedSpawnPos();
                x = spawn.getX() + 0.5D;
                y = spawn.getY() + 0.1D;
                z = spawn.getZ() + 0.5D;
            }
        }

        LOGGER.info("Leaving factory #{} at {} from {}", factoryId, target, player.blockPosition());
        player.teleportTo(targetLevel, x, y, z, player.getYRot(), player.getXRot());
        AUTO_ENTER_COOLDOWN_UNTIL.put(
                player.getUUID(),
                targetLevel.getGameTime() + AUTO_ENTER_COOLDOWN_TICKS
        );
        player.sendSystemMessage(Component.translatable("message.recursivefactory.exited", factoryId));
        return true;
    }

    public static boolean exitFromEdge(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.level() != server.getLevel(FactoryDimension.LEVEL_KEY)) {
            return false;
        }

        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord record = data.factoryAt(player.blockPosition());
        if (record == null) {
            record = FactoryDimension.nearestFactory(data, player.blockPosition());
        }
        if (record == null) {
            return false;
        }

        return exit(player, record.id());
    }

    private static void pushReturnPoint(ServerPlayer player) {
        ReturnStackData stack = player.getData(ModAttachments.RETURN_STACK.get());
        player.setData(
                ModAttachments.RETURN_STACK.get(),
                stack.push(ReturnStackData.ReturnPoint.capture(player))
        );
    }

    /**
     * Closest position to {@code preferred} that a player fits in, preferring one with ground beneath
     * it. Returns {@code null} when the whole neighbourhood is blocked.
     */
    @Nullable
    private static BlockPos standableSpot(ServerLevel level, BlockPos preferred) {
        for (boolean requireGround : new boolean[] {true, false}) {
            for (int yOffset : SAFE_SPOT_Y_OFFSETS) {
                for (int radius = 0; radius <= SAFE_SPOT_RADIUS; radius++) {
                    for (BlockPos candidate : ring(preferred, radius)) {
                        BlockPos feet = candidate.offset(0, yOffset, 0);
                        if (isClear(level, feet)
                                && isClear(level, feet.above())
                                && (!requireGround || !isClear(level, feet.below()))) {
                            return feet;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<BlockPos> ring(BlockPos center, int radius) {
        if (radius == 0) {
            return List.of(center);
        }
        List<BlockPos> ring = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                    ring.add(center.offset(dx, 0, dz));
                }
            }
        }
        return ring;
    }

    private static boolean isClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }
}
