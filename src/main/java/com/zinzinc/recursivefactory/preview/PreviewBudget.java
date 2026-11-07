package com.zinzinc.recursivefactory.preview;

import java.util.*;

/** Oldest due work wins, with distance breaking ties; repeated requests never spend extra budget. */
public final class PreviewBudget<K> {
    private final Map<K, Entry> entries = new LinkedHashMap<>();
    public void demand(K key, long now, double distance, int geometryInterval, int dynamicInterval) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (entry.demand != now) { entry.geometryInterval = geometryInterval; entry.dynamicInterval = dynamicInterval; }
        entry.distance = entry.demand == now ? Math.min(entry.distance, distance) : distance;
        entry.demand = now;
        entry.geometryInterval = Math.min(entry.geometryInterval, geometryInterval);
        entry.dynamicInterval = Math.min(entry.dynamicInterval, dynamicInterval);
    }

    public List<Work<K>> take(long now, int geometryBudget, int dynamicBudget) {
        entries.values().removeIf(entry -> entry.demand != now);
        var ordered = new ArrayList<>(entries.entrySet());
        ordered.sort(Comparator.<Map.Entry<K, Entry>>comparingLong(entry -> entry.getValue().geometry)
                .thenComparingDouble(entry -> entry.getValue().distance));
        List<Work<K>> result = new ArrayList<>();
        Set<K> selected = new HashSet<>();
        for (var item : ordered) {
            Entry entry = item.getValue();
            if (geometryBudget <= 0 || dynamicBudget <= 0) break;
            if (now - entry.geometry < entry.geometryInterval) continue;
            result.add(new Work<>(item.getKey(), true, interval(now, entry)));
            selected.add(item.getKey());
            entry.geometry = entry.dynamic = now;
            geometryBudget--; dynamicBudget--;
        }
        ordered.sort(Comparator.<Map.Entry<K, Entry>>comparingLong(entry -> entry.getValue().dynamic)
                .thenComparingDouble(entry -> entry.getValue().distance));
        for (var item : ordered) {
            Entry entry = item.getValue();
            if (dynamicBudget <= 0) break;
            if (selected.contains(item.getKey()) || entry.geometry < 0 || now - entry.dynamic < entry.dynamicInterval) continue;
            result.add(new Work<>(item.getKey(), false, interval(now, entry)));
            entry.dynamic = now;
            dynamicBudget--;
        }
        return result;
    }

    private int interval(long now, Entry entry) {
        return entry.dynamic < 0 ? entry.dynamicInterval : (int) Math.max(1, Math.min(40, now - entry.dynamic));
    }
    private static final class Entry {
        long demand = Long.MIN_VALUE / 2, geometry = Long.MIN_VALUE / 2, dynamic = Long.MIN_VALUE / 2;
        int geometryInterval = Integer.MAX_VALUE, dynamicInterval = Integer.MAX_VALUE;
        double distance;
    }
    public record Work<K>(K key, boolean geometry, int interval) { }
}
