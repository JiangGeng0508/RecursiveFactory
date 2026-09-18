package com.zinzinc.recursivefactory.mixin.client;

import com.zinzinc.recursivefactory.client.FactoryRoomRenderRange;
import com.zinzinc.recursivefactory.world.FactoryPortalEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Widens the section radius Immersive Portals uses while it draws the room into a factory doorway.
 *
 * <p>VisibleSectionDiscovery drops every section farther than {@code viewDistance} sections (Chebyshev) from
 * the camera of the world being drawn, and for a portal it takes that radius from
 * PortalRenderer.getPortalRenderDistance:
 *
 * <pre>max(min(getDestAreaRadiusEstimation() * 1.4, 512.0) / 16, renderDistance)</pre>
 *
 * <p>The 512 is a hard 512-<em>block</em> ceiling, so the radius never exceeds 32 chunks however large the
 * portal's own estimate is; our FactoryPortalEntity already reports 384 and is clipped by it.
 *
 * <p>That ceiling only bites on scaled portals, because PortalRenderer.getPortalScaleMatrix puts 1/scale
 * into the model-view: the camera that draws our room stands {@code 16 x distance} blocks away from it, so
 * the room leaves the 512-block radius once the player is about 31 blocks from a doorway and the doorway
 * then shows nothing but the factory dimension's sky. Neither the frustum nor chunk loading is the limit -
 * the portal pass reuses the vanilla projection (512 view units, stretched to 8192 room blocks by that same
 * 1/16 model-view) and the client keeps the room's chunks loaded the whole time.
 *
 * <p>Immersive Portals offers no API or config for this radius, so the field is written directly, and only
 * while the portal being drawn is a scaled factory doorway: the ordinary world render keeps its own radius
 * (and therefore its own chunk loading) untouched. The portal stack is pushed by the time this runs -
 * discoverVisibleSections reads PortalRendering.getRenderingPortal() a few instructions later for the same
 * purpose.
 *
 * <p>The injection is optional on purpose ({@code require = 0}): if a future Immersive Portals moves or
 * renames the method, the mod keeps working exactly as before instead of failing to load, and the
 * "Portal view:" diagnostic reports that the override never ran.
 *
 * <p>Everything here is marked {@code remap = false} because Immersive Portals' own names are never
 * obfuscated, so nothing may be looked up in a refmap.
 */
@Mixin(value = VisibleSectionDiscovery.class, remap = false)
public abstract class VisibleSectionDiscoveryMixin {
    @Shadow(remap = false)
    private static int viewDistance;

    @Inject(method = "updateViewDistance", at = @At("TAIL"), remap = false, require = 0)
    private static void recursivefactory$widenFactoryRoomRange(CallbackInfo ci) {
        Portal renderingPortal = PortalRendering.getRenderingPortal();
        if (renderingPortal instanceof FactoryPortalEntity && renderingPortal.getScale() > 2.0D) {
            viewDistance = Math.max(viewDistance, FactoryRoomRenderRange.CHUNKS);
            FactoryRoomRenderRange.lastApplied = viewDistance;
        }
    }
}
