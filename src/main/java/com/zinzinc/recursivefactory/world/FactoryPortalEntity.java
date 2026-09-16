package com.zinzinc.recursivefactory.world;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The portal entity a factory's openings are made of.
 *
 * <p>It only exists to answer one question differently than Immersive Portals would by default: how much of
 * the room has to be kept loaded and rendered for the doorway view. Everything else is inherited.
 */
public class FactoryPortalEntity extends Portal {
    /**
     * How deep into the room the doorway view has to reach, in room blocks, before the scale is applied.
     *
     * <p>The room is {@code ROOM_WIDTH} blocks across, and someone looking through a doorway stands a few
     * blocks in front of it, so the view has to cover the room's whole depth plus that distance.
     */
    private static final double ROOM_VIEW_DEPTH = 24.0D;

    public FactoryPortalEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Tells Immersive Portals how far the far side of this portal reaches, which is what it uses to size the
     * view into the room.
     *
     * <p>Without this the portals came out unusable at this scale. Immersive Portals defaults to
     * {@code max(width, height) * scaling}, which for a doorway two blocks tall is 32 - and it turns that into
     * a render distance of {@code estimate * 1.4 / 16}, two chunks. A scaled portal is viewed from a virtual
     * camera pushed {@code SCALE} times deeper into the room, so the room's own depth arrives multiplied by
     * sixteen: at two chunks only the near corner of the room survives, the rest is beyond the render
     * distance and drops out - and it drops out back to front, which is exactly how it looked. The nether
     * portal never had the problem because its scale is one, and the reference mod fixes it the same way,
     * with a hard coded {@code 12 * 16} (its box depth times its scale) on its own portal subclass.
     */
    @Override
    public double getDestAreaRadiusEstimation() {
        return FactoryPortal.SCALE * ROOM_VIEW_DEPTH;
    }
}
