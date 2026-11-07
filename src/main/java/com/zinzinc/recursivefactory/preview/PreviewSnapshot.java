package com.zinzinc.recursivefactory.preview;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity.PreviewBlock;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** Owned display data shared by every view of the same room cell; never persisted. */
public record PreviewSnapshot(List<PreviewBlock> blocks, List<CompoundTag> entities,
                              List<CompoundTag> blockEntities, CompoundTag wires,
                              List<CompoundTag> bodies, List<CompoundTag> ropes) {
    public PreviewSnapshot {
        blocks = List.copyOf(blocks);
        entities = List.copyOf(entities);
        blockEntities = List.copyOf(blockEntities);
        wires = wires.copy();
        bodies = List.copyOf(bodies);
        ropes = List.copyOf(ropes);
    }

    public static PreviewSnapshot empty() {
        return new PreviewSnapshot(List.of(), List.of(), List.of(), new CompoundTag(), List.of(), List.of());
    }

    public CompoundTag tag() {
        return PreviewCodec.writePreview(blocks, entities, blockEntities, wires, bodies, ropes);
    }
}
