package com.zinzinc.recursivefactory.compat.sable;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniondc;

/** Optional, server-only access to Sable plots. Plain Create packs need no Sable classes. */
public final class SablePhysicsBodies {
    private static Access access;
    private static boolean broken;
    private static final int MAX_BODIES = 32, MAX_BLOCKS = 4096, MAX_VISITS = 65536;

    private SablePhysicsBodies() {}

    public static boolean available() {
        return ModList.get().isLoaded("sable");
    }

    private static Access api() throws ReflectiveOperationException {
        if (access == null) access = new Access();
        return access;
    }

    private static List<?> bodies(Level level) throws ReflectiveOperationException {
        Object container = api().container.invoke(level);
        return container == null ? List.of() : (List<?>) api().all.invoke(container);
    }

    public static List<AABB> bodiesIn(Level level) {
        if (!available() || broken || !(level instanceof ServerLevel)) return List.of();
        try {
            List<AABB> result = new ArrayList<>();
            for (Object body : bodies(level))
                if (!Boolean.TRUE.equals(api().removed.invoke(body))) result.add(bounds(body));
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(failure);
            return List.of();
        }
    }

    /** Plot storage positions must never become vanilla chunk tickets or teleport destinations. */
    public static Vec3 worldPosition(ServerLevel level, Vec3 pos) {
        if (!available() || broken) return pos;
        try {
            for (Object body : bodies(level)) {
                if (Boolean.TRUE.equals(api().removed.invoke(body))) continue;
                Object plot = api().plot.invoke(body);
                if (Boolean.TRUE.equals(api().contains.invoke(plot, pos)))
                    return (Vec3) api().transform.invoke(api().pose.invoke(body), pos);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(failure);
        }
        return pos;
    }

    /** Geometry stays in plot-local coordinates; its world pose travels separately for interpolation. */
    public static List<CompoundTag> sample(ServerLevel level, BlockPos center, int size, int height) {
        if (!available() || broken) return List.of();
        try {
            Vec3 origin = Vec3.atLowerCornerOf(center.below());
            AABB room = new AABB(origin.x - size / 2, origin.y, origin.z - size / 2,
                    origin.x + size - size / 2, origin.y + height, origin.z + size - size / 2);
            List<CompoundTag> result = new ArrayList<>();
            int remaining = MAX_BLOCKS, visits = MAX_VISITS;
            for (Object body : bodies(level)) {
                if (result.size() >= MAX_BODIES || remaining <= 0 || visits <= 0) break;
                if (Boolean.TRUE.equals(api().removed.invoke(body)) || !bounds(body).intersects(room)) continue;
                Object plot = api().plot.invoke(body), pose = api().pose.invoke(body);
                Object box = api().plotBounds.invoke(plot);
                BlockPos anchor = new BlockPos(integer(box, 0), integer(box, 1), integer(box, 2));
                // Bound the scan by the inverse image of this room, even for a huge plot.
                AABB local = transformedBox(room, api().inverse, pose);
                int minX = Math.max(anchor.getX(), (int) Math.floor(local.minX));
                int minY = Math.max(anchor.getY(), (int) Math.floor(local.minY));
                int minZ = Math.max(anchor.getZ(), (int) Math.floor(local.minZ));
                int maxX = Math.min(integer(box, 3), (int) Math.floor(local.maxX));
                int maxY = Math.min(integer(box, 4), (int) Math.floor(local.maxY));
                int maxZ = Math.min(integer(box, 5), (int) Math.floor(local.maxZ));
                List<EndpointBlockEntity.PreviewBlock> blocks = new ArrayList<>();
                if (minX > maxX || minY > maxY || minZ > maxZ) continue;
                for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
                    if (--visits < 0 || remaining <= 0) break;
                    var state = level.getBlockState(pos);
                    if (state.isAir() || state.is(Blocks.BEDROCK) || state.is(ModBlocks.FACTORY_BARRIER.get())) continue;
                    Vec3 world = (Vec3) api().transform.invoke(pose, Vec3.atCenterOf(pos));
                    if (!room.contains(world)) continue;
                    blocks.add(new EndpointBlockEntity.PreviewBlock(pos.getX() - anchor.getX(),
                            pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ(), state));
                    remaining--;
                }
                if (blocks.isEmpty()) continue;
                CompoundTag sample = new CompoundTag();
                sample.putUUID("Id", (java.util.UUID) api().id.invoke(body));
                Vec3 offset = ((Vec3) api().transform.invoke(pose, Vec3.atLowerCornerOf(anchor))).subtract(origin);
                sample.putDouble("X", offset.x);
                sample.putDouble("Y", offset.y);
                sample.putDouble("Z", offset.z);
                Quaterniondc rotation = (Quaterniondc) api().orientation.invoke(pose);
                sample.putDouble("QX", rotation.x());
                sample.putDouble("QY", rotation.y());
                sample.putDouble("QZ", rotation.z());
                sample.putDouble("QW", rotation.w());
                sample.put("Geometry", EndpointBlockEntity.writePreview(blocks, List.of(),
                        EndpointBlockEntity.samplePreviewBlockEntities(level, anchor.above(), blocks), new CompoundTag()));
                result.add(sample);
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(failure);
            return List.of();
        }
    }

    private static AABB transformedBox(AABB box, Method transform, Object pose) throws ReflectiveOperationException {
        AABB result = null;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = (Vec3) transform.invoke(pose, new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ));
            AABB point = new AABB(corner, corner);
            result = result == null ? point : result.minmax(point);
        }
        return result;
    }

    private static int integer(Object bounds, int index) throws ReflectiveOperationException {
        return ((Number) api().ints[index].invoke(bounds)).intValue();
    }

    private static AABB bounds(Object body) throws ReflectiveOperationException {
        Object box = api().plotBounds.invoke(api().plot.invoke(body));
        // Sampling must not call updateBoundingBox: that overwrites Sable's previous physics bounds.
        AABB local = new AABB(integer(box, 0), integer(box, 1), integer(box, 2),
                integer(box, 3) + 1.0, integer(box, 4) + 1.0, integer(box, 5) + 1.0);
        return transformedBox(local, api().transform, api().pose.invoke(body));
    }

    private static void report(Throwable failure) {
        if (!broken) LogUtils.getLogger().warn("Sable factory integration could not read physics plots", failure);
        broken = true;
    }

    private static final class Access {
        final Method container, all, removed, plot, pose, id;
        final Method contains, plotBounds, transform, inverse, orientation;
        final Method[] ints = new Method[6];

        Access() throws ReflectiveOperationException {
            // The base container's signatures include ClientLevel: never reflect on it on a server.
            container = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder")
                    .getMethod("sable$getPlotContainer");
            all = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer").getDeclaredMethod("getAllSubLevels");
            Class<?> body = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            removed = body.getMethod("isRemoved");
            plot = body.getMethod("getPlot");
            pose = body.getMethod("logicalPose");
            id = body.getMethod("getUniqueId");
            Class<?> plotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            contains = plotClass.getMethod("contains", Vec3.class);
            plotBounds = plotClass.getMethod("getBoundingBox");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            transform = poseClass.getMethod("transformPosition", Vec3.class);
            inverse = poseClass.getMethod("transformPositionInverse", Vec3.class);
            orientation = poseClass.getMethod("orientation");
            Class<?> intBox = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
            String[] names = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
            for (int i = 0; i < names.length; i++) {
                ints[i] = intBox.getMethod(names[i]);
            }
        }
    }
}
