package com.zinzinc.recursivefactory.preview;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Client-visible data only. Inventory renderers (belts, depots, basins) keep their update payload. */
public final class PreviewDisplayData {
    public static CompoundTag sample(BlockEntity entity) {
        CompoundTag tag;
        if (entity instanceof EndpointBlockEntity endpoint) {
            tag = new CompoundTag();
            tag.putInt("FactoryId", endpoint.getFactoryId());
            tag.putFloat("Speed", endpoint.getSpeed());
        } else {
            tag = entity.getUpdateTag(entity.getLevel().registryAccess()).copy();
        }
        if (entity instanceof BaseContainerBlockEntity || entity instanceof ItemVaultBlockEntity) {
            tag.remove("Items"); tag.remove("Inventory"); tag.remove("LootTable"); tag.remove("LootTableSeed");
        }
        tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
        return tag;
    }

    /** Queued nested jobs can contain older snapshots; bound those trees too, not just the call stack. */
    public static void trimNested(CompoundTag root, Set<Integer> ancestors, int depth) {
        for (Tag item : root.getList("BlockEntities", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) item;
            if (!block.contains(EndpointBlockEntity.PREVIEW_BLOCKS_TAG)) continue;
            int id = block.getInt("FactoryId");
            if (depth >= PreviewRecursion.MAX_DEPTH || ancestors.contains(id)) {
                block.put(EndpointBlockEntity.PREVIEW_BLOCKS_TAG, new CompoundTag());
            } else {
                Set<Integer> next = new HashSet<>(ancestors); next.add(id);
                trimNested(block.getCompound(EndpointBlockEntity.PREVIEW_BLOCKS_TAG), next, depth + 1);
            }
        }
    }

    /** Known renderer-only state does not invalidate the cell's baked geometry. */
    public static int geometryHash(List<CompoundTag> samples) {
        int hash = 1;
        for (CompoundTag sample : samples) {
            CompoundTag geometry = sample.copy();
            String id = sample.getString("id");
            if (id.startsWith("recursivefactory:")) {
                geometry.remove(EndpointBlockEntity.PREVIEW_BLOCKS_TAG);
                geometry.remove("RecursiveFactoryPreviewInterval");
            }
            if (id.startsWith("create:") || id.startsWith("recursivefactory:")) {
                for (String key : List.of("Speed", "Network", "Source", "Capacity", "Stress", "Overstressed")) geometry.remove(key);
            }
            if (id.equals("create:belt")) geometry.remove("Inventory");
            if (id.equals("create:fluid_tank")) { geometry.remove("TankContent"); geometry.remove("Luminosity"); }
            hash = 31 * hash + geometry.hashCode();
        }
        return hash;
    }
}
