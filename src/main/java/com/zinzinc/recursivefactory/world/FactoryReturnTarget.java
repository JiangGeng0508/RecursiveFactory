package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Bounded safe landing search shared by normal exits and orphaned-room recovery. */
public record FactoryReturnTarget(ServerLevel level, Vec3 position, float yaw, float pitch) {
    public static @Nullable FactoryReturnTarget near(ServerPlayer player, ServerLevel level,
                                                     Vec3 requested, float yaw, float pitch) {
        Vec3 center = SablePhysicsBodies.worldPosition(level, requested);
        if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)) return null;
        if (safe(player, level, center)) return new FactoryReturnTarget(level, center, yaw, pitch);
        BlockPos origin = BlockPos.containing(center);
        for (int radius = 0; radius <= 8; radius++) {
            for (int dy = -4; dy <= 8; dy++) {
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    Vec3 candidate = Vec3.atBottomCenterOf(origin.offset(dx, dy, dz));
                    if (safe(player, level, candidate)) return new FactoryReturnTarget(level, candidate, yaw, pitch);
                }
            }
        }
        return null;
    }

    public static @Nullable FactoryReturnTarget spawn(ServerPlayer player, ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        FactoryReturnTarget nearby = near(player, level, Vec3.atBottomCenterOf(spawn), 0, player.getXRot());
        if (nearby != null) return nearby;
        // A configured spawn can be underground or floating. Search surface columns without changing terrain.
        for (BlockPos column : BlockPos.spiralAround(spawn, 32, Direction.EAST, Direction.SOUTH)) {
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            Vec3 candidate = Vec3.atBottomCenterOf(surface);
            if (safe(player, level, candidate)) return new FactoryReturnTarget(level, candidate, 0, player.getXRot());
        }
        return null;
    }

    private static boolean safe(ServerPlayer player, ServerLevel level, Vec3 pos) {
        BlockPos feet = BlockPos.containing(pos);
        if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(feet.above())
                || !level.getWorldBorder().isWithinBounds(feet)) return false;
        if (level.dimension().equals(FactoryDimension.LEVEL_KEY)
                && FactoryData.get(level.getServer()).factoryAt(feet) == null) return false;
        var floor = level.getBlockState(feet.below());
        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP) || floor.is(Blocks.MAGMA_BLOCK)
                || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE) || floor.is(Blocks.CACTUS)) return false;
        var box = player.getBoundingBox().move(pos.subtract(player.position()));
        return level.noCollision(player, box) && !level.containsAnyLiquid(box)
                && !level.getBlockState(feet).is(Blocks.FIRE) && !level.getBlockState(feet).is(Blocks.SOUL_FIRE);
    }
}
