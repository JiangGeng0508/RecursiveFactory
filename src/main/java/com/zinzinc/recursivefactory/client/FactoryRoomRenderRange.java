package com.zinzinc.recursivefactory.client;

/**
 * How far the factory room keeps being drawn through a doorway, and whether that override is live.
 *
 * <p>See {@code mixin.client.VisibleSectionDiscoveryMixin} for why this needs a mixin at all. The value is
 * in chunks because that is the unit of Immersive Portals' section radius; for our doorway (scale 16) one
 * chunk of radius is exactly one block of distance from the entrance, so {@link #CHUNKS} is also roughly
 * "how many blocks away the room stays visible".
 */
public final class FactoryRoomRenderRange {
    /**
     * Section radius used while drawing the room. Immersive Portals' own ceiling is 32 (a hard 512 blocks
     * for any scaled portal), which with our scale of 16 leaves the room visible only up to about 31
     * blocks. The limit here is the frustum, not this number: the portal pass reuses the vanilla projection
     * (far plane 512 view units) and the model-view carries 1/scale, so the room stays inside the frustum
     * out to 512 x 16 blocks and fog is the next thing to show up, around 128 blocks.
     */
    public static final int CHUNKS = 128;

    /** The radius the mixin last wrote, or -1 while it has not run at all. */
    public static int lastApplied = -1;

    private FactoryRoomRenderRange() {
    }
}
