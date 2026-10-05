package com.zinzinc.recursivefactory.compat.sable;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Transaction around Sable's move, including assembly, disassembly and plot relocation. */
public final class SableAssembly implements AutoCloseable {
    private static final ThreadLocal<SableAssembly> ACTIVE = new ThreadLocal<>();
    private final SableAssembly previous;
    private final ServerLevel source;
    private final ServerLevel target;
    private final Map<BlockPos, BlockPos> entrances = new HashMap<>();

    public SableAssembly(ServerLevel source, Object transform, Iterable<BlockPos> blocks) {
        this.source = source;
        try {
            var apply = transform.getClass().getMethod("apply", BlockPos.class);
            var destination = transform.getClass().getDeclaredField("resultingLevel");
            destination.setAccessible(true);
            target = (ServerLevel) destination.get(transform);
            for (BlockPos pos : blocks) {
                if (source.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity endpoint
                        && endpoint.hasFactoryId()) {
                    entrances.put(pos.immutable(), (BlockPos) apply.invoke(transform, pos));
                }
            }
        } catch (ReflectiveOperationException failure) {
            // Stop before Sable removes any blocks if its API changed; never silently lose a room.
            throw new IllegalStateException("Cannot preserve factory entrances during Sable assembly", failure);
        }
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    public static boolean isMoving(Level level, BlockPos pos) {
        for (SableAssembly move = ACTIVE.get(); move != null; move = move.previous) {
            if (move.source == level && move.entrances.containsKey(pos)) return true;
        }
        return false;
    }

    public void finish() {
        Map<BlockPos, BlockPos> completed = new HashMap<>();
        entrances.forEach((from, to) -> {
            var record = FactoryData.get(source.getServer()).factoryWithEntranceCell(source.dimension().location(), from);
            if (record != null && target.getBlockEntity(to) instanceof RecursiveFactoryBlockEntity endpoint
                    && endpoint.getFactoryId() == record.id()) completed.put(from, to);
        });
        FactoryData.get(source.getServer()).moveEntrances(source.dimension().location(), target.dimension().location(), completed);
        if (!completed.isEmpty()) LogUtils.getLogger().info("Preserved {} factory entrance links during Sable move", completed.size());
    }

    @Override
    public void close() {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
