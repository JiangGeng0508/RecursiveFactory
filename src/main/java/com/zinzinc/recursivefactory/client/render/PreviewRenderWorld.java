package com.zinzinc.recursivefactory.client.render;

import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

/**
 * The throwaway world one preview's blocks are drawn in: no sky, everything lit from above, and one biome
 * throughout.
 *
 * <p>The blocks in it were sampled inside the factory dimension, so every colour a biome decides - grass,
 * leaves, water - has to come out of that dimension's own biome. A {@link VirtualRenderWorld} on its own
 * answers biome lookups out of the level it wraps, at the sample's own local coordinates, which put every
 * preview at the same spot in the player's world - the patches of whatever biome lies there were drawn
 * into all of them, wherever the room stood and whatever it was built of.
 */
public final class PreviewRenderWorld {
    private PreviewRenderWorld() {
    }

    /**
     * Wraps {@code level} so the block renderer can be run on the sample. Only the light and the biome are
     * read out of it: the blocks themselves were already put into the returned world.
     */
    public static VirtualRenderWorld create(Level level, int worldMinY, int worldHeight) {
        Holder<Biome> biome = FactoryDimension.previewBiome(level.registryAccess());
        return new VirtualRenderWorld(level, worldMinY, worldHeight, BlockPos.ZERO, () -> {
        }) {
            @Override
            public boolean supportsVisualization() {
                return false;
            }

            @Override
            public int getBrightness(LightLayer lightLayer, BlockPos pos) {
                return 15;
            }

            @Override
            public int getRawBrightness(BlockPos pos, int amount) {
                return 15;
            }

            /** One biome for the whole sample: the room's own. */
            @Override
            public Holder<Biome> getBiome(BlockPos pos) {
                return biome;
            }

            @Override
            public Holder<Biome> getNoiseBiome(int x, int y, int z) {
                return biome;
            }

            @Override
            public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
                return biome;
            }
        };
    }
}