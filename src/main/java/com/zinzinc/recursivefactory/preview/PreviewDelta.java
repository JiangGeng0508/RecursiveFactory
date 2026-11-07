package com.zinzinc.recursivefactory.preview;

import java.util.Objects;
import net.minecraft.nbt.*;

/** Structural NBT patches retain unchanged geometry even inside nested factory snapshots. */
public final class PreviewDelta {
    private PreviewDelta() { }

    public static CompoundTag between(Tag before, Tag after) {
        CompoundTag patch = new CompoundTag();
        if (Objects.equals(before, after)) return patch;
        if (before instanceof CompoundTag old && after instanceof CompoundTag next) {
            CompoundTag fields = new CompoundTag();
            ListTag removed = new ListTag();
            for (String key : old.getAllKeys()) if (!next.contains(key)) removed.add(StringTag.valueOf(key));
            for (String key : next.getAllKeys()) {
                CompoundTag change = between(old.get(key), next.get(key));
                if (!change.isEmpty()) fields.put(key, change);
            }
            if (!fields.isEmpty()) patch.put("Fields", fields);
            if (!removed.isEmpty()) patch.put("Remove", removed);
        } else if (before instanceof ListTag old && after instanceof ListTag next && old.size() == next.size()
                && old.getElementType() == next.getElementType()) {
            CompoundTag entries = new CompoundTag();
            for (int i = 0; i < next.size(); i++) {
                CompoundTag change = between(old.get(i), next.get(i));
                if (!change.isEmpty()) entries.put(Integer.toString(i), change);
            }
            patch.put("Entries", entries);
        } else {
            patch.put("Value", after.copy());
        }
        return patch;
    }

    public static Tag apply(Tag before, CompoundTag patch) {
        if (patch.contains("Value")) return patch.get("Value").copy();
        if (patch.isEmpty()) return before.copy();
        if (before instanceof CompoundTag compound) {
            CompoundTag result = compound.copy();
            for (Tag key : patch.getList("Remove", Tag.TAG_STRING)) result.remove(key.getAsString());
            CompoundTag fields = patch.getCompound("Fields");
            for (String key : fields.getAllKeys()) result.put(key, apply(result.get(key), fields.getCompound(key)));
            return result;
        }
        if (before instanceof ListTag list) {
            ListTag result = list.copy();
            CompoundTag entries = patch.getCompound("Entries");
            for (String key : entries.getAllKeys()) {
                int index = Integer.parseInt(key);
                result.set(index, apply(result.get(index), entries.getCompound(key)));
            }
            return result;
        }
        throw new IllegalArgumentException("Invalid preview delta base");
    }
}
