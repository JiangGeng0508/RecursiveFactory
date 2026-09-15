package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.RecursiveFactory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;

/**
 * The Immersive Portals side of a factory.
 *
 * <p>Each face of the entrance block opens onto the matching face of the factory room: the block's
 * north face looks at the room's north side, its top face at the room's ceiling, and so on. That gives
 * the room five openings (four sides and a ceiling; the floor is sealed with bedrock instead) which are
 * the same openings you see from the outside on the block, so the block's faces and the room are
 * "connected" in both directions - you can walk or fall in from any of them, and look through either
 * side.
 *
 * <p>Each pair is built by placing the plane on the entrance block and letting Immersive Portals derive
 * the reverse plane, which is the same approach the reference mod uses and gets the paired orientation
 * and the reverse link right without guessing axes for the far side.
 *
 * <p>Geometry note: a portal with {@code scaling = S} makes the far side look {@code S} times smaller,
 * so a one block face shows {@code S x S} blocks of the room, and the room in turn stands in for a
 * {@code S} times larger piece of the world. That is what makes the factory the shrunk side.
 */
public final class FactoryPortal {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How many blocks of the world one block of the room stands in for: the far side of every portal looks
     * this many times smaller, which is what makes the factory the shrunk side of the relationship.
     *
     * <p>It also sizes the room side, because Immersive Portals derives that plane from the entrance
     * block's face: the derived plane is always the face size multiplied by this.
     */
    public static final double SCALE = 16.0D;

    /** The room is one chunk across and {@code FLOOR_Y..CEILING_Y} tall, and its openings span all of it. */
    private static final double ROOM_WIDTH = 16.0D;
    /**
     * The room's height, which has to match what Immersive Portals derives for the room side. It derives
     * that plane from the entrance block's one block face, so the plane comes out {@code PLANE_SIZE * SCALE}
     * = 16 blocks, and the box has to be that tall for the four sides and the ceiling to meet with no gaps.
     * That is why the ceiling is at y=80: raising this means growing the entrance block's side faces too.
     */
    private static final double ROOM_HEIGHT = FactoryData.CEILING_Y - FactoryData.FLOOR_Y;

    /** Whether walking into a plane moves the player. */
    public static final boolean TELEPORTABLE = true;

    /** Faces of the entrance block that open into the room. No bottom face: the floor is bedrock. */
    private static final Direction[] OPEN_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    /** The entrance block is one block, so every one of its faces is one block square. */
    private static final double PLANE_SIZE = 1.0D;
    /**
     * Room side planes are grown by this much beyond the box.
     *
     * <p>The box's edges fall exactly on the chunk boundary and on the ceiling level, which is where the
     * border blocks are, so a plane that stops exactly at the box edge shares its edge with a block face and
     * the two eat a hairline out of each other. Growing each plane slightly tucks its edge inside the border
     * blocks, out of sight, and stretches the view by about a third of a percent, which does not show.
     */
    private static final double EDGE_OVERLAP = 0.05D;
    /** Pushes a plane just clear of the block face it belongs to instead of z-fighting with it. */
    private static final double FACE_OFFSET = 0.001D;

    private static final Vec3 UNIT_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 UNIT_NEG_X = new Vec3(-1.0D, 0.0D, 0.0D);
    private static final Vec3 UNIT_Y = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 UNIT_Z = new Vec3(0.0D, 0.0D, 1.0D);
    private static final Vec3 UNIT_NEG_Z = new Vec3(0.0D, 0.0D, -1.0D);

    private static final String TAG_PREFIX = RecursiveFactory.MODID + ":factory_";
    private static final int ROOM_CHUNK_LOAD_RADIUS = 0;

    private FactoryPortal() {
    }

    public static String tag(int factoryId) {
        return TAG_PREFIX + factoryId;
    }

    /** Builds the portals for a factory, replacing whatever was there before. */
    public static void ensure(Level entranceLevel, int factoryId) {
        if (!(entranceLevel instanceof ServerLevel serverEntrance) || serverEntrance.getServer() == null) {
            return;
        }
        try {
            ensureInternal(serverEntrance, factoryId);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not build the portals for factory #{}", factoryId, exception);
        }
    }

    /** Removes everything this class built for a factory, used when the entrance block is broken. */
    public static void remove(Level entranceLevel, int factoryId) {
        if (!(entranceLevel instanceof ServerLevel serverEntrance) || serverEntrance.getServer() == null) {
            return;
        }
        MinecraftServer server = serverEntrance.getServer();
        String tag = tag(factoryId);
        int removed = discard(serverEntrance, tag);
        ServerLevel factoryLevel = server.getLevel(FactoryDimension.LEVEL_KEY);
        if (factoryLevel != null) {
            removed += discard(factoryLevel, tag);
        }
        if (removed > 0) {
            LOGGER.info("Removed {} portal(s) of factory #{}", removed, factoryId);
        }
    }

    private static void ensureInternal(ServerLevel entranceLevel, int factoryId) {
        MinecraftServer server = entranceLevel.getServer();
        FactoryData.FactoryRecord record = FactoryData.get(server).factory(factoryId);
        if (record == null || record.entranceDimension() == null || record.mirrorDimension() == null) {
            return;
        }
        if (!record.entranceDimension().equals(entranceLevel.dimension().location())) {
            return;
        }
        ServerLevel factoryLevel = server.getLevel(FactoryDimension.LEVEL_KEY);
        if (factoryLevel == null) {
            return;
        }

        // Rebuild from scratch so the portals always match the geometry in this class. Portals left
        // behind by an earlier build would otherwise never be corrected or cleaned up.
        String tag = tag(factoryId);
        discard(entranceLevel, tag);
        discard(factoryLevel, tag);

        BlockPos entrance = record.entrancePos();
        int openings = 0;

        for (Direction face : OPEN_FACES) {
            Vec3[] axes = faceAxes(face);
            boolean upright = face.getAxis() != Direction.Axis.Y;
            Portal outside = createPlane(
                    entranceLevel,
                    faceCentre(entrance, face),
                    factoryLevel.dimension(),
                    roomFaceCentre(record, face),
                    axes[0],
                    axes[1],
                    PLANE_SIZE,
                    PLANE_SIZE,
                    tag,
                    TELEPORTABLE
            );
            if (outside == null) {
                LOGGER.error("Could not create the {} opening of factory #{}", face, factoryId);
                continue;
            }

            // Immersive Portals derives the room side from this one: same plane, opposite face, and the
            // two are linked to each other. Doing it by hand is what got the orientation wrong before.
            Portal inside = PortalManipulation.createReversePortal(outside, Portal.ENTITY_TYPE);
            if (inside == null) {
                LOGGER.error("Immersive Portals returned no reverse plane for the {} opening of factory #{}",
                        face, factoryId);
                continue;
            }
            inside.portalTag = tag;
            // Spelled out to match the box the destination describes: the four sides span the room's full
            // height and the ceiling covers its whole footprint, so the five planes meet at the edges and
            // the portals are the only way out. Oversized by EDGE_OVERLAP so the joints do not show a seam.
            inside.setWidth(ROOM_WIDTH + EDGE_OVERLAP);
            inside.setHeight((upright ? ROOM_HEIGHT : ROOM_WIDTH) + EDGE_OVERLAP);
            // Render flags, matching the reference mod: the side you look at from outside is fused into the
            // world view and both sides are mergeable. Left at the defaults the plane is drawn through its
            // own pass with its own culling, and it drops out of view when the camera turns.
            outside.setFuseView(true);
            outside.renderingMergable = true;
            inside.renderingMergable = true;
            inside.setTeleportable(TELEPORTABLE);
            inside.setTeleportChangesScale(false);
            // When used with Iris the reverse side would otherwise render the player onto itself.
            inside.setDoRenderPlayer(false);
            // Nudges the player out of a block if the scaled arrival point lands inside one.
            PortalExtension.get(outside).adjustPositionAfterTeleport = true;
            PortalExtension.get(inside).adjustPositionAfterTeleport = true;

            McHelper.spawnServerEntity(outside);
            McHelper.spawnServerEntity(inside);
            openings++;
        }

        keepRoomLoaded(server, record);

        LOGGER.info("Built factory #{} portals: {} openings between the entrance block and the room at scale {}",
                factoryId, openings, SCALE);
    }

    /**
     * Keeps the room's chunk loaded through Immersive Portals as well as the vanilla force load. Without
     * it, walking into a portal can be refused with "the chunk on the other side is not loaded".
     */
    private static void keepRoomLoaded(MinecraftServer server, FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        ChunkLoader loader = new ChunkLoader(
                FactoryDimension.LEVEL_KEY, chunk.x, chunk.z, ROOM_CHUNK_LOAD_RADIUS
        );
        PortalAPI.removeGlobalChunkLoader(server, loader);
        PortalAPI.addGlobalChunkLoader(server, loader);
    }

    private static Portal createPlane(ServerLevel originLevel, Vec3 originPos, ResourceKey<Level> destDimension,
                                      Vec3 destPos, Vec3 axisW, Vec3 axisH, double width, double height,
                                      String tag, boolean teleportable) {
        Portal portal = Portal.ENTITY_TYPE.create(originLevel);
        if (portal == null) {
            return null;
        }
        portal.setOriginPos(originPos);
        portal.setDestinationDimension(destDimension);
        portal.setDestination(destPos);
        portal.setOrientation(axisW, axisH);
        portal.setWidth(width);
        portal.setHeight(height);
        portal.setScaling(SCALE);
        // Portal defaults to changing the traveller's body size, which made the room look eight times
        // too big once inside. The scale belongs to the view only.
        portal.setTeleportChangesScale(false);
        portal.setCrossPortalCollisionEnabled(teleportable);
        portal.setInteractable(teleportable);
        portal.setTeleportable(teleportable);
        portal.portalTag = tag;
        portal.setPos(originPos.x, originPos.y, originPos.z);
        return portal;
    }

    /**
     * The centre of one face of a block's cell, pushed just clear of the block.
     *
     * <p>It stays centred on the face on purpose. Lifting the side planes clear of the ring's arms looks
     * tidier, but the room side is derived from this plane at {@link #SCALE} times its size, so any lift here
     * comes back multiplied: a quarter block lift put the world seen through the walls four room blocks below
     * the world seen through the ceiling, which is what made the ceiling look misplaced. Keeping the plane
     * centred is what makes the five views line up.
     */
    private static Vec3 faceCentre(BlockPos block, Direction face) {
        Vec3 centre = new Vec3(block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
        return centre.add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5D + FACE_OFFSET));
    }

    /** The centre of the matching face of the room box. */
    private static Vec3 roomFaceCentre(FactoryData.FactoryRecord record, Direction face) {
        AABB room = roomBox(record);
        Vec3 outward = Vec3.atLowerCornerOf(face.getNormal());
        return room.getCenter().add(outward.scale(lengthAlong(room, outward) / 2.0D));
    }

    /**
     * The two axes of a block face, chosen so the first is horizontal, the second is vertical (where the
     * face is upright) and their cross product is the face's outward normal, so the content is visible
     * from outside the block. Immersive Portals treats the first axis as the plane's width and the second
     * as its height, which is why the vertical one must come second.
     */
    private static Vec3[] faceAxes(Direction face) {
        return switch (face) {
            case UP -> new Vec3[] {UNIT_X, UNIT_NEG_Z};
            case NORTH -> new Vec3[] {UNIT_NEG_X, UNIT_Y};
            case SOUTH -> new Vec3[] {UNIT_X, UNIT_Y};
            case WEST -> new Vec3[] {UNIT_Z, UNIT_Y};
            case EAST -> new Vec3[] {UNIT_NEG_Z, UNIT_Y};
            case DOWN -> new Vec3[] {UNIT_X, UNIT_Z};
        };
    }

    /**
     * The box the openings enclose: one chunk across and the room's full height, with its bottom on the
     * platform floor, so that the five planes meet at the corners and leave no way out.
     */
    private static AABB roomBox(FactoryData.FactoryRecord record) {
        ChunkPos chunk = record.baseChunk();
        return new AABB(
                chunk.getMinBlockX(),
                FactoryData.FLOOR_Y,
                chunk.getMinBlockZ(),
                chunk.getMinBlockX() + ROOM_WIDTH,
                FactoryData.FLOOR_Y + ROOM_HEIGHT,
                chunk.getMinBlockZ() + ROOM_WIDTH
        );
    }

    private static double lengthAlong(AABB box, Vec3 direction) {
        if (direction.x != 0.0D) {
            return box.getXsize();
        }
        if (direction.y != 0.0D) {
            return box.getYsize();
        }
        return box.getZsize();
    }

    private static List<Portal> tagged(ServerLevel level, String tag) {
        List<Portal> portals = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Portal portal && tag.equals(portal.portalTag)) {
                portals.add(portal);
            }
        }
        return portals;
    }

    private static int discard(ServerLevel level, String tag) {
        int removed = 0;
        for (Portal portal : tagged(level, tag)) {
            portal.discard();
            removed++;
        }
        return removed;
    }
}
