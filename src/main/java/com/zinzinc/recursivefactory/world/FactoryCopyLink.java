package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Resolves a coordinate link against the current world, without creating a blueprint file. */
public final class FactoryCopyLink {
    private FactoryCopyLink() {}

    public static Source resolve(MinecraftServer server, GlobalPos target) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || !level.isInWorldBounds(target.pos())) throw missing();
        // A saved link is also usable after its source chunk has unloaded.
        level.getChunk(target.pos().getX() >> 4, target.pos().getZ() >> 4);
        if (!(level.getBlockEntity(target.pos()) instanceof RecursiveFactoryBlockEntity entrance)) throw missing();
        FactoryData data = FactoryData.get(server);
        var record = data.factory(entrance.getFactoryId());
        var cell = record == null ? null : record.cellAt(target.pos());
        ServerLevel roomLevel = server.getLevel(FactoryDimension.LEVEL_KEY);
        if (record == null || cell == null || roomLevel == null
                || !target.dimension().location().equals(record.entranceDimension())) throw missing();
        List<BlockPos> doors = record.connectedBoundCells(cell).stream()
                .map(door -> door.entrance().subtract(target.pos()))
                .filter(offset -> !offset.equals(BlockPos.ZERO)).toList();
        return new Source(entrance, roomLevel, data, record, cell, doors);
    }

    private static FactoryBlueprint.Refusal missing() {
        return FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.copy_source.missing"));
    }

    public record Source(RecursiveFactoryBlockEntity entrance, ServerLevel roomLevel, FactoryData data,
                         FactoryData.FactoryRecord record, FactoryData.FactoryRecord.Cell cell,
                         List<BlockPos> doors) {
        public Prepared capture() {
            return FactoryLocks.whileLocked(record.id(), () -> {
                FactoryBlueprint blueprint = FactoryBlueprint.capture(roomLevel, data, record, cell,
                        "coordinate link at " + cell.entrance());
                for (var room : blueprint.rooms()) FactoryRoomLayout.baseRoomY(roomLevel, room.cells());
                return new Prepared(blueprint, doors, entrance.blueprintEntranceNodes());
            });
        }
    }

    /** Lives only during one placement; never stored on the held item or written to disk. */
    public record Prepared(FactoryBlueprint blueprint, List<BlockPos> doors, CompoundTag entranceNodes) {}
}
