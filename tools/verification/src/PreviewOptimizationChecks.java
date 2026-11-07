package com.zinzinc.recursivefactory.probe;

import com.zinzinc.recursivefactory.preview.*;
import com.zinzinc.recursivefactory.client.render.PreviewEntityStep;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.nbt.*;

final class PreviewOptimizationChecks {
    static void run(BiConsumer<Boolean, String> check) {
        for (int count : new int[]{1, 16, 64}) {
            PreviewBudget<Integer> queue = new PreviewBudget<>();
            int geometry = 0, dynamic = 0;
            int[] visits = new int[count];
            boolean bounded = true;
            long start = System.nanoTime();
            for (int tick = 0; tick < 120; tick++) {
                for (int id = 0; id < count; id++) {
                    queue.demand(id, tick, id % 2 == 0 ? 0 : 120, id % 2 == 0 ? 10 : 40, id % 2 == 0 ? 1 : 10);
                    queue.demand(id, tick, 140, 40, 10);
                }
                var work = queue.take(tick, 4, 32);
                int staticCount = (int) work.stream().filter(PreviewBudget.Work::geometry).count();
                bounded &= staticCount <= 4 && work.size() <= 32
                        && work.stream().map(PreviewBudget.Work::key).distinct().count() == work.size();
                geometry += staticCount; dynamic += work.size();
                for (var item : work) visits[item.key()]++;
            }
            check.accept(bounded && Arrays.stream(visits).allMatch(value -> value > 1),
                    count + " preview sources respect both budgets, deduplicate viewers and never starve distant sources");
            check.accept(queue.take(121, 4, 32).isEmpty(), "preview work stops without viewing demand: " + count);
            System.out.println("[rfaudit] preview-workload sources=" + count + " ticks=120 legacyScans=" + count * 120
                    + " staticScans=" + geometry + " dynamicSamples=" + dynamic + " queueNanos=" + (System.nanoTime() - start));
        }
        Object[] chain = new Object[9];
        boolean limited = true;
        try {
            for (int i = 0; i < chain.length; i++) { chain[i] = new Object(); limited &= PreviewRecursion.enter(chain[i]) == (i < 8); }
            limited &= !PreviewRecursion.enter(chain[0]);
        } finally { for (Object item : chain) PreviewRecursion.leave(item); }
        check.accept(limited, "nested sampling rejects cycles and nesting beyond eight active sources");
        CompoundTag cyclic = new CompoundTag();
        CompoundTag child = new CompoundTag(); child.putInt("FactoryId", 1); child.put("PreviewBlocks", new CompoundTag());
        ListTag children = new ListTag(); children.add(child); cyclic.put("BlockEntities", children);
        child.getCompound("PreviewBlocks").putString("OldGeometry", "must be removed");
        PreviewDisplayData.trimNested(cyclic, Set.of(1), 1);
        check.accept(child.getCompound("PreviewBlocks").isEmpty(), "queued snapshots cannot retain an ancestor's older preview");
        CompoundTag before = new CompoundTag();
        ListTag blocks = new ListTag();
        for (int i = 0; i < 2048; i++) { CompoundTag block = new CompoundTag(); block.putInt("X", i); block.putString("State", "minecraft:stone"); blocks.add(block); }
        before.put("Blocks", blocks);
        CompoundTag nested = before.copy();
        CompoundTag mob = new CompoundTag(); mob.putDouble("X", 1); nested.put("Mob", mob);
        before.put("Nested", nested);
        CompoundTag after = before.copy(); after.getCompound("Nested").getCompound("Mob").putDouble("X", 2);
        var delta = PreviewDelta.between(before, after);
        check.accept(PreviewDelta.apply(before, delta).equals(after) && delta.toString().length() < after.toString().length() / 100,
                "nested dynamic update patches exactly without retransmitting either static block list");
        after.remove("Blocks"); after.getCompound("Nested").getList("Blocks", Tag.TAG_COMPOUND).remove(0);
        check.accept(PreviewDelta.apply(before, PreviewDelta.between(before, after)).equals(after),
                "delta handles removed channels and changing list lengths");
        System.out.println("[rfaudit] preview-payload fullChars=" + before.toString().length() + " deltaChars=" + delta.toString().length());
        CompoundTag belt = new CompoundTag(); belt.putString("id", "create:belt"); belt.putInt("Inventory", 1);
        int hash = PreviewDisplayData.geometryHash(List.of(belt)); belt.putInt("Inventory", 2); belt.putFloat("Speed", 16);
        check.accept(hash == PreviewDisplayData.geometryHash(List.of(belt)), "belt inventory and rotation do not invalidate geometry");
        belt.putInt("x", 2);
        check.accept(hash != PreviewDisplayData.geometryHash(List.of(belt)), "machine placement changes still invalidate geometry");
        PreviewEntityStep step = new PreviewEntityStep(); step.setInterval(10); step.note(List.of(mob), 100);
        check.accept(step.fraction(105) == 0.5f && step.fraction(110) == 1,
                "preview interpolation spans the actual ten-tick sampling interval");
        step.note(List.of(mob), 105);
        check.accept(step.fraction(106) == 0.6f, "repeated entity data does not restart interpolation");
    }
}
