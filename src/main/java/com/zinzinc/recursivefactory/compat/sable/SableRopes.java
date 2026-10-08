package com.zinzinc.recursivefactory.compat.sable;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.world.WirePreviewGeometry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Vector3dc;

/** Simulated owns the ropes in the Sable/Aeronautics pack, separately from block entities and plots. */
public final class SableRopes {
    private static Access access;
    private static boolean broken;

    private SableRopes() {}

    public static List<CompoundTag> sample(ServerLevel level, BlockPos center) {
        if (broken || !ModList.get().isLoaded("simulated") || !SablePhysicsBodies.available()) return List.of();
        try {
            if (access == null) access = new Access();
            Object manager = access.manager.invoke(null, level);
            if (manager == null) return List.of();
            Vec3 origin = Vec3.atLowerCornerOf(center.below());
            List<CompoundTag> result = new ArrayList<>();
            for (Object strand : (Iterable<?>) access.strands.invoke(manager)) {
                // Read the world-space physics pose even when no player tracks the room itself.
                if (Boolean.TRUE.equals(access.active.invoke(strand))) access.updatePose.invoke(strand);
                List<Vec3> points = new ArrayList<>();
                for (Object value : (Iterable<?>) access.points.invoke(strand)) {
                    Vector3dc point = (Vector3dc) value;
                    points.add(new Vec3(point.x(), point.y(), point.z()).subtract(origin));
                }
                boolean visible = false;
                for (int i = 1; i < points.size(); i++) {
                    if (WirePreviewGeometry.clip(points.get(i - 1), points.get(i)) != null) {
                        visible = true;
                        break;
                    }
                }
                if (!visible) continue;
                CompoundTag sample = new CompoundTag();
                sample.putUUID("Id", (UUID) access.id.invoke(strand));
                ListTag positions = new ListTag();
                for (Vec3 point : points) {
                    CompoundTag position = new CompoundTag();
                    position.putDouble("X", point.x);
                    position.putDouble("Y", point.y);
                    position.putDouble("Z", point.z);
                    positions.add(position);
                }
                sample.put("Points", positions);
                result.add(sample);
            }
            result.sort(Comparator.comparing(tag -> tag.getUUID("Id")));
            return List.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LogUtils.getLogger().warn("Sable factory preview could not read Simulated ropes", failure);
            broken = true;
            return List.of();
        }
    }

    private static final class Access {
        final Method manager, strands, id, points, active, updatePose;

        Access() throws ReflectiveOperationException {
            Class<?> managers = Class.forName("dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager");
            manager = managers.getMethod("getOrCreate", Level.class);
            strands = managers.getMethod("getAllStrands");
            Class<?> strand = Class.forName("dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand");
            id = strand.getMethod("getUUID");
            points = strand.getMethod("getPoints");
            active = strand.getMethod("isActive");
            updatePose = strand.getMethod("updatePose");
        }
    }
}
