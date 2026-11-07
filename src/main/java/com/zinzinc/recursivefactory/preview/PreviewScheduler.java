package com.zinzinc.recursivefactory.preview;

import com.zinzinc.recursivefactory.block.entity.FactoryPreviewBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** One server-wide budget; remote displays and nested entrances share the same cell job. */
public final class PreviewScheduler {
    private static final Map<MinecraftServer, PreviewScheduler> SERVERS = new WeakHashMap<>();
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    private final Map<RecursiveFactoryBlockEntity, Job> jobs = new LinkedHashMap<>();
    private final Map<FactoryPreviewBlockEntity, Display> displays = new WeakHashMap<>();
    private final PreviewBudget<RecursiveFactoryBlockEntity> budget = new PreviewBudget<>();
    public long staticSamples, dynamicSamples, sampleNanos;

    public static PreviewScheduler get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new PreviewScheduler());
    }

    public static void reset(MinecraftServer server) { SERVERS.remove(server); }
    public static boolean sampling() { return CURRENT.get() != null; }

    public static void nested(RecursiveFactoryBlockEntity child) {
        Job parent = CURRENT.get();
        if (parent != null) parent.children.add(child);
    }

    public static void watch(RecursiveFactoryBlockEntity source, double distance) {
        if (!(source.getLevel() instanceof ServerLevel level)) return;
        Job job = get(level.getServer()).jobs.computeIfAbsent(source, Job::new);
        long tick = level.getServer().getTickCount();
        job.distance = job.demandTick == tick ? Math.min(distance, job.distance) : distance;
        job.demandTick = tick;
    }

    public static void watch(FactoryPreviewBlockEntity display, double distance) {
        if (!(display.getLevel() instanceof ServerLevel level)) return;
        RecursiveFactoryBlockEntity entrance = display.resolveSource();
        if (entrance == null) { display.show(PreviewSnapshot.empty()); return; }
        watch(entrance, distance);
        get(level.getServer()).displays.put(display, new Display(entrance, level.getServer().getTickCount()));
    }

    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        jobs.entrySet().removeIf(entry -> entry.getKey().isRemoved());
        Map<RecursiveFactoryBlockEntity, Demand> active = new LinkedHashMap<>();
        ArrayDeque<Demand> pending = new ArrayDeque<>();
        for (Job job : jobs.values()) if (now - job.demandTick <= 1)
            pending.add(new Demand(job.source, job.distance, 0));
        while (!pending.isEmpty()) {
            Demand next = pending.remove();
            if (next.depth >= PreviewRecursion.MAX_DEPTH || next.source.isRemoved()) continue;
            Demand previous = active.get(next.source);
            if (previous != null && previous.distance <= next.distance && previous.depth <= next.depth) continue;
            active.put(next.source, next);
            Job job = jobs.computeIfAbsent(next.source, Job::new);
            for (RecursiveFactoryBlockEntity child : job.children)
                pending.add(new Demand(child, next.distance, next.depth + 1));
        }
        for (Demand demand : active.values()) {
            FactoryDimension.keepLoaded(demand.source.getFactoryId());
            budget.demand(demand.source, now, demand.distance,
                    FactoryConfig.previewInterval(demand.distance, true),
                    FactoryConfig.previewInterval(demand.distance, false));
        }
        var work = budget.take(now, FactoryConfig.previewBudget(true), FactoryConfig.previewBudget(false));
        for (var item : work) {
            Job job = jobs.get(item.key());
            if (job == null) continue;
            if (item.geometry()) job.children.clear();
            CURRENT.set(job);
            long start = System.nanoTime();
            try {
                PreviewTransport.noteSample(job.source, now, item.interval());
                job.source.samplePreview(item.geometry());
                if (item.geometry()) staticSamples++;
                dynamicSamples++;
            } finally {
                sampleNanos += System.nanoTime() - start;
                CURRENT.remove();
            }
        }
        displays.entrySet().removeIf(entry -> {
            if (entry.getKey().isRemoved() || now - entry.getValue().tick > 1) return true;
            PreviewTransport.copyTiming(entry.getValue().source, entry.getKey());
            entry.getKey().show(entry.getValue().source.previewSnapshot());
            return false;
        });
        jobs.keySet().retainAll(active.keySet());
    }

    private static final class Job {
        final RecursiveFactoryBlockEntity source;
        final Set<RecursiveFactoryBlockEntity> children = new LinkedHashSet<>();
        long demandTick = Long.MIN_VALUE / 2;
        double distance;
        Job(RecursiveFactoryBlockEntity source) { this.source = source; }
    }
    private record Demand(RecursiveFactoryBlockEntity source, double distance, int depth) { }
    private record Display(RecursiveFactoryBlockEntity source, long tick) { }
}
