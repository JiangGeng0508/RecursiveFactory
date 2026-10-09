package com.zinzinc.recursivefactory.world;

import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Shared checks before a room allocation or expansion can write to the factory dimension. */
public final class FactoryRoomLayout {
    private FactoryRoomLayout() {}

    public static void validate(List<BlockPos> cells) {
        if (cells.isEmpty() || !cells.contains(BlockPos.ZERO) || new HashSet<>(cells).size() != cells.size()) {
            throw invalid();
        }
        for (BlockPos cell : cells) {
            if (cell.getX() % FactoryData.CELL_SIZE != 0 || cell.getY() % FactoryData.CELL_SIZE != 0
                    || cell.getZ() % FactoryData.CELL_SIZE != 0
                    || Math.abs((long) cell.getX()) > 29_999_968 || Math.abs((long) cell.getZ()) > 29_999_968) {
                throw invalid();
            }
        }
    }

    /** Keep the picked cell at the usual height when possible; shift tall copies as a whole. */
    public static int baseRoomY(ServerLevel level, List<BlockPos> cells) {
        validate(cells);
        int low = cells.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int high = cells.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        long minimum = (long) level.getMinBuildHeight() + 1 - FactoryData.BASE_Y - low;
        long maximum = (long) level.getMaxBuildHeight() - 1 - FactoryData.CEILING_Y - high;
        // Cells stay on the same 16-block grid, including the bedrock below the lowest cell.
        long alignedMinimum = -Math.floorDiv(-minimum, FactoryData.CELL_SIZE) * FactoryData.CELL_SIZE;
        long alignedMaximum = Math.floorDiv(maximum, FactoryData.CELL_SIZE) * FactoryData.CELL_SIZE;
        if (alignedMinimum > alignedMaximum) throw invalid();
        return (int) Math.clamp(0L, alignedMinimum, alignedMaximum);
    }

    public static boolean fits(ServerLevel level, int x, int y, int z) {
        return (long) y + FactoryData.BASE_Y - 1 >= level.getMinBuildHeight()
                && (long) y + FactoryData.CEILING_Y < level.getMaxBuildHeight()
                && x >= -29_999_984 && x <= 29_999_968 && z >= -29_999_984 && z <= 29_999_968;
    }

    public static FactoryBlueprint.Refusal invalid() {
        return FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.room.invalid"));
    }
}
