package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Samples the world surrounding a factory entrance so the client can draw it as a shell wrapping
 * around the factory platform. The outside world is drawn {@link #SCALE} times bigger than the
 * factory interior, so the whole 16x16 platform stands for a single outside block: the player looks
 * like they shrank down and stepped inside the factory block, with the enlarged world all around.
 *
 * <p>Blocks are stored in a coordinate space that only depends on the factory chunk, so the client
 * can place them without any extra anchor data: relative coordinates are outside blocks, one unit of
 * which maps to {@link #SCALE} blocks of the factory level. Relative x/z run from {@code 0} to
 * {@code 2 * RADIUS} around the entrance cell (which itself is left empty, the platform covers it),
 * and relative y runs from {@code 0} upwards, with the ground under the platform lining up with the
 * platform floor.
 *
 * <p>Only blocks that can actually be seen are sent: anything hidden behind opaque neighbours is
 * dropped, while fluid blocks (water, lava, modded fluids) are kept when a face of theirs is
 * exposed. Fluids are rendered by the vanilla fluid renderer on the client, exactly like in a real
 * world, so lakes and lava pools show up instead of holes.
 */
public final class FactoryEnclosure {
    /** How much bigger the outside world is drawn compared to the factory interior. */
    public static final int SCALE = 16;
    /** Horizontal radius of the sampled area, in outside blocks. */
    public static final int RADIUS = 10;
    /** How many outside blocks below the entrance block are sampled. */
    public static final int BELOW = 4;
    /** How many outside blocks above the entrance block are sampled. */
    public static final int ABOVE = 8;
    public static final int WIDTH = RADIUS * 2 + 1;
    public static final int HEIGHT = BELOW + ABOVE + 1;

    private FactoryEnclosure() {
    }

    /** World position the relative coordinate {@code (0, 0, 0)} maps to inside the factory level. */
    public static BlockPos origin(ChunkPos chunk) {
        return new BlockPos(
                chunk.getMinBlockX() - RADIUS * SCALE,
                FactoryData.FLOOR_Y + 1 - BELOW * SCALE,
                chunk.getMinBlockZ() - RADIUS * SCALE
        );
    }

    /**
     * Samples the external world around the record's entrance. The entrance cell itself is skipped,
     * the factory platform fills it.
     */
    public static List<EndpointBlockEntity.PreviewBlock> sample(
            ServerLevel externalLevel,
            FactoryData.FactoryRecord record
    ) {
        BlockPos entrance = record.entrancePos();
        Map<Integer, BlockState> sampled = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < WIDTH; z++) {
                    if (x == RADIUS && z == RADIUS) {
                        // The factory block's own cell, the platform stands in for it.
                        continue;
                    }

                    BlockState state = externalLevel.getBlockState(cursor.set(
                            entrance.getX() - RADIUS + x,
                            entrance.getY() - BELOW + y,
                            entrance.getZ() - RADIUS + z
                    ));
                    if (state.isAir() || state.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    sampled.put(key(x, y, z), state);
                }
            }
        }

        List<EndpointBlockEntity.PreviewBlock> visible = new ArrayList<>(sampled.size());
        for (Map.Entry<Integer, BlockState> entry : sampled.entrySet()) {
            int packed = entry.getKey();
            int x = packed & 63;
            int z = packed >> 6 & 63;
            int y = packed >> 12;
            BlockState state = entry.getValue();
            if (isFluidOnly(state)) {
                if (!fluidExposed(sampled, x, y, z, state)) {
                    continue;
                }
            } else if (surrounded(sampled, x, y, z)) {
                continue;
            }
            visible.add(new EndpointBlockEntity.PreviewBlock(x, y, z, state));
        }
        return List.copyOf(visible);
    }

    /** True for blocks that draw nothing themselves and are drawn by the fluid renderer instead. */
    private static boolean isFluidOnly(BlockState state) {
        return !state.canOcclude() && !state.getFluidState().isEmpty();
    }

    private static boolean surrounded(Map<Integer, BlockState> sampled, int x, int y, int z) {
        // The underside is never visible, so treat everything below the sample as solid.
        return occludes(sampled, x - 1, y, z)
                && occludes(sampled, x + 1, y, z)
                && occludes(sampled, x, y + 1, z)
                && occludes(sampled, x, y, z - 1)
                && occludes(sampled, x, y, z + 1)
                && (y <= 0 || occludes(sampled, x, y - 1, z));
    }

    /**
     * Fluid hidden inside more of the same fluid is invisible, no matter which way the fluid renderer
     * culls its faces.
     */
    private static boolean fluidExposed(Map<Integer, BlockState> sampled, int x, int y, int z, BlockState state) {
        FluidState fluid = state.getFluidState();
        for (Direction direction : Direction.values()) {
            BlockState neighbor = stateAt(
                    sampled,
                    x + direction.getStepX(),
                    y + direction.getStepY(),
                    z + direction.getStepZ()
            );
            if (neighbor == null || !neighbor.getFluidState().is(fluid.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Only blocks that occlude hide their neighbours. Fluids and other see-through blocks are not
     * occluders, so the terrain behind a lake still keeps its faces.
     */
    private static boolean occludes(Map<Integer, BlockState> sampled, int x, int y, int z) {
        BlockState state = stateAt(sampled, x, y, z);
        return state != null && state.canOcclude();
    }

    @Nullable
    private static BlockState stateAt(Map<Integer, BlockState> sampled, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= WIDTH || y >= HEIGHT || z >= WIDTH) {
            return null;
        }
        return sampled.get(key(x, y, z));
    }

    private static int key(int x, int y, int z) {
        return x & 63 | (z & 63) << 6 | y << 12;
    }
}