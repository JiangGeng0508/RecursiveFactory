package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ReturnStackData;
import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

        // A room that is being read out for a blueprint, or printed from one, has to stand still: a player
        // walking in would be walking into a copy while it is being taken.
        if (FactoryLocks.isBeingCopied(factoryId)) {
            player.sendSystemMessage(Component.translatable("message.recursivefactory.copying"));
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

    /** Return points remain usable even after every entrance and room has been removed. */
    public static boolean exit(ServerPlayer player, int factoryId) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ReturnStackData remaining = player.getData(ModAttachments.RETURN_STACK.get());
        FactoryReturnTarget target = null;
        while (!remaining.isEmpty() && target == null) {
            ReturnStackData.ReturnPoint point = remaining.peek();
            remaining = remaining.pop();
            ServerLevel level = server.getLevel(point.dimensionKey());
            if (level != null) target = FactoryReturnTarget.near(player, level,
                    new Vec3(point.x(), point.y(), point.z()), point.yRot(), point.xRot());
        }
        FactoryData.FactoryRecord record = FactoryData.get(server).factory(factoryId);
        if (target == null && record != null && record.entranceDimension() != null && !record.cells().isEmpty()) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, record.entranceDimension()));
            if (level != null) target = FactoryReturnTarget.near(player, level,
                    Vec3.atBottomCenterOf(record.entrancePos().above()), player.getYRot(), player.getXRot());
        }
        if (target == null) target = FactoryReturnTarget.spawn(player, server.overworld());
        if (target == null) return false;
        player.teleportTo(target.level(), target.position().x, target.position().y, target.position().z,
                target.yaw(), target.pitch());
        player.setData(ModAttachments.RETURN_STACK.get(), remaining);
        player.fallDistance = 0;
        player.sendSystemMessage(Component.translatable(factoryId > 0
                ? "message.recursivefactory.exited" : "message.recursivefactory.recovered", factoryId));
        return true;
    }

    /** No nearest unrelated factory is needed to recover an orphaned player. */
    public static boolean exitFromEdge(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || player.level() != server.getLevel(FactoryDimension.LEVEL_KEY)) return false;
        FactoryData.FactoryRecord record = FactoryData.get(server).factoryAt(player.blockPosition());
        return exit(player, record == null ? -1 : record.id());
    }

    private static void pushReturnPoint(ServerPlayer player) {
        ReturnStackData stack = player.getData(ModAttachments.RETURN_STACK.get());
        player.setData(
                ModAttachments.RETURN_STACK.get(),
                stack.push(ReturnStackData.ReturnPoint.capture(player))
        );
    }
}
