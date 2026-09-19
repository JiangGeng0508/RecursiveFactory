package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ReturnStackData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class FactoryTeleporter {
    private FactoryTeleporter() {
    }

    /** Drops the player into the room cell that the given entrance block stands for. */
    public static boolean enter(ServerPlayer player, int factoryId, BlockPos entrancePos) {
        MinecraftServer server = player.getServer();
        ServerLevel targetLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        FactoryData.FactoryRecord record = server == null ? null : FactoryData.get(server).factory(factoryId);
        if (targetLevel == null || record == null) {
            player.sendSystemMessage(Component.translatable("message.recursivefactory.missing"));
            return false;
        }

        FactoryData.FactoryRecord.Cell cell = record.cellAt(entrancePos);
        if (cell == null) {
            cell = record.anchorCell();
        }
        if (cell == null) {
            player.sendSystemMessage(Component.translatable("message.recursivefactory.missing"));
            return false;
        }

        FactoryDimension.prepare(targetLevel, record);
        pushReturnPoint(player);

        BlockPos target = FactoryDimension.entryTarget(cell);
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

    /** Sends the player back where they came from, or beside the entrance block if that is unknown. */
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

        ServerLevel targetLevel;
        double x;
        double y;
        double z;
        float yRot;
        if (returnPoint != null) {
            ResourceKey<Level> dimensionKey = returnPoint.dimensionKey();
            targetLevel = server.getLevel(dimensionKey);
            x = returnPoint.x();
            y = returnPoint.y();
            z = returnPoint.z();
            yRot = returnPoint.yRot();
        } else {
            ResourceKey<Level> dimensionKey = record.entranceDimension() == null
                    ? Level.OVERWORLD
                    : ResourceKey.create(Registries.DIMENSION, record.entranceDimension());
            targetLevel = server.getLevel(dimensionKey);
            BlockPos fallback = record.entrancePos().above();
            x = fallback.getX() + 0.5D;
            y = fallback.getY() + 0.1D;
            z = fallback.getZ() + 0.5D;
            yRot = player.getYRot();
        }

        if (targetLevel == null) {
            targetLevel = server.overworld();
            BlockPos spawn = targetLevel.getSharedSpawnPos();
            x = spawn.getX() + 0.5D;
            y = spawn.getY() + 0.1D;
            z = spawn.getZ() + 0.5D;
            yRot = 0.0F;
        }

        player.teleportTo(targetLevel, x, y, z, yRot, player.getXRot());
        player.sendSystemMessage(Component.translatable("message.recursivefactory.exited", factoryId));
        return true;
    }

    /** A player who ends up in the factory dimension but not in any room is sent back out. */
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
}