package com.zinzinc.recursivefactory.client.render;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The factory room's sky, minus its clouds.
 *
 * <p>The room keeps the overworld's sky colours, fog and brightness, because the room is meant to read as an
 * ordinary place. Its clouds are the one part of it that has to go: Immersive Portals draws a doorway with
 * fuseView, which suppresses the destination dimension's sky - PortalRenderer sets doRenderSky to
 * !isFuseView and MixinLevelRenderer honours it - but clouds are drawn by a separate mixin
 * (MixinLevelRenderer_Clouds) that swaps the cloud buffer per portal layer and never looks at doRenderSky.
 * So the room's clouds stayed visible through every doorway, hanging at the room's own cloud height of 192
 * inside the overworld's sky, and they outlived the room itself once Immersive Portals culled its sections.
 *
 * <p>Vanilla skips cloud rendering outright for a dimension whose effects report a NaN cloud height - that is
 * how the nether has none - so overriding the height is enough, and it holds in the portal pass as well:
 * Immersive Portals swaps LevelRenderer's level field to the destination world for that pass, and its cloud
 * mixin reads the cloud height from that same field. If clouds ever reappear through a doorway, the blunt
 * instrument is to also override {@code renderClouds} to return true, which stops vanilla cloud rendering
 * before it starts.
 */
@OnlyIn(Dist.CLIENT)
public class FactoryDimensionEffects extends DimensionSpecialEffects.OverworldEffects {
    @Override
    public float getCloudHeight() {
        return Float.NaN;
    }
}
