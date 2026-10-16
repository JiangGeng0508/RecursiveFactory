package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.foundation.utility.BlockHelper;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.*;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Exercise the real disk/debug NBT, initial sync, preview packets and nested sampling paths. */
final class PreviewPersistenceChecks {
    private static final String CACHE = EndpointBlockEntity.PREVIEW_BLOCKS_TAG;

    static void run(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        var entrance = door(outside, new BlockPos(1600, 200, 100));
        entrance.activateRedstone(Direction.NORTH);
        entrance.offer(new ItemStack(Items.DIAMOND, 3), Direction.NORTH);
        entrance.offerFluid(new FluidStack(Fluids.WATER, 500), Direction.SOUTH);
        var before = entrance.saveWithoutMetadata(outside.registryAccess());
        var blocks = new ArrayList<EndpointBlockEntity.PreviewBlock>();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 16; z++)
            blocks.add(new EndpointBlockEntity.PreviewBlock(x, y, z, Blocks.GOLD_BLOCK.defaultBlockState()));
        CompoundTag entity = marker("entity"), machine = marker("machine"), wires = marker("wires");
        CompoundTag body = marker("body"), rope = marker("rope");
        var chunk = outside.getChunkAt(entrance.getBlockPos());
        chunk.setUnsaved(false);
        entrance.updatePreview(blocks, List.of(entity), List.of(machine), wires, List.of(body), List.of(rope));
        check.accept(!chunk.isUnsaved(), "changing preview geometry does not dirty its entrance chunk");
        var movedEntity = entity.copy();
        movedEntity.putInt("Frame", 2);
        entrance.updatePreview(blocks, List.of(movedEntity), List.of(machine), wires, List.of(body), List.of(rope));
        check.accept(!chunk.isUnsaved() && entrance.getPreviewEntities().getFirst().getInt("Frame") == 2,
                "changing preview entities updates the cache without dirtying its entrance chunk");
        chunk.setUnsaved(true); // Preserve the fixture's actual placement when this isolated server saves.
        var disk = entrance.saveWithoutMetadata(outside.registryAccess());
        var snapshot = entrance.previewTag();
        check.accept(before.equals(disk) && !disk.contains(CACHE),
                "debug-query and persistent NBT stay identical after adding a 2048-block preview");
        check.accept(!entrance.saveWithFullMetadata(outside.registryAccess()).contains(CACHE)
                        && !entrance.saveWithId(outside.registryAccess()).contains(CACHE),
                "chunk and structure serialization omit preview caches");
        var oldStyle = disk.copy();
        oldStyle.put(CACHE, snapshot);
        System.out.println("[rfaudit] preview NBT chars: old=" + oldStyle.toString().length() + " persistent=" + disk.toString().length());

        var loaded = new RecursiveFactoryBlockEntity(entrance.getBlockPos(), entrance.getBlockState());
        loaded.loadWithComponents(oldStyle, outside.registryAccess());
        check.accept(empty(loaded) && !loaded.saveWithoutMetadata(outside.registryAccess()).contains(CACHE),
                "legacy saved previews are ignored and disappear from the next save");
        check.accept(loaded.getFactoryId() == entrance.getFactoryId() && loaded.isRedstoneEnabled(Direction.NORTH)
                        && loaded.getPendingStack().is(Items.DIAMOND) && loaded.getPendingStack().getCount() == 3
                        && loaded.getPendingFluid().getAmount() == 500,
                "discarding old caches preserves binding, installed redstone, items and fluid");
        var initial = entrance.getUpdateTag(outside.registryAccess());
        loaded.handleUpdateTag(initial, outside.registryAccess());
        check.accept(snapshot.equals(loaded.previewTag()), "initial chunk sync restores all six preview channels");
        var packet = entrance.getUpdatePacket();
        loaded.clearPreview();
        loaded.handleUpdateTag(packet.getTag(), outside.registryAccess());
        check.accept(snapshot.equals(loaded.previewTag()), "block entity update packets retain the complete preview");
        var stateOnly = initial.copy();
        stateOnly.remove(CACHE);
        loaded.handleUpdateTag(stateOnly, outside.registryAccess());
        check.accept(snapshot.equals(loaded.previewTag()), "state-only packets do not erase a previously received preview");
        loaded.clearPreview();
        var buffer = Unpooled.buffer();
        try {
            var sent = new EndpointPreviewPackets.Sync(entrance.getBlockPos(), snapshot);
            EndpointPreviewPackets.Sync.STREAM_CODEC.encode(buffer, sent);
            var received = EndpointPreviewPackets.Sync.STREAM_CODEC.decode(buffer);
            loaded.acceptPreviewTag(received.previewTag(), outside.registryAccess());
            check.accept(received.pos().equals(entrance.getBlockPos()) && snapshot.equals(loaded.previewTag()),
                    "dedicated preview packets round-trip blocks, entities, machines, wires, bodies and ropes");
        } finally { buffer.release(); }
        var cleared = initial.copy();
        entrance.clearPreview();
        cleared.put(CACHE, entrance.previewTag());
        loaded.handleUpdateTag(cleared, outside.registryAccess());
        check.accept(empty(loaded), "an explicitly empty preview clears all client cache channels");

        var room = FactoryData.get(outside.getServer()).factory(entrance.getFactoryId());
        var origin = FactoryBlueprint.origin(room.anchorCell());
        inside.setBlockAndUpdate(origin.offset(4, 0, 4), Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) inside.getBlockEntity(origin.offset(4, 0, 4));
        chest.setItem(0, new ItemStack(Items.EMERALD, 7));
        entrance.loadWithComponents(oldStyle, outside.registryAccess());
        check.accept(empty(entrance), "loading an old entrance starts with an empty display cache");
        entrance.refreshPreviewSnapshot();
        check.accept(entrance.getPreviewBlocks().stream().anyMatch(block -> block.state().is(Blocks.CHEST))
                        && !entrance.getPreviewBlockEntities().isEmpty()
                        && entrance.getPreviewBlocks().stream().noneMatch(block -> block.state().is(Blocks.GOLD_BLOCK)),
                "the first preview refresh rebuilds from live room contents instead of stale saved geometry");

        var inner = door(inside, origin.offset(7, 0, 7));
        var innerRoom = FactoryData.get(outside.getServer()).factory(inner.getFactoryId());
        inside.setBlockAndUpdate(FactoryBlueprint.origin(innerRoom.anchorCell()).offset(3, 0, 3), Blocks.DIAMOND_BLOCK.defaultBlockState());
        inner.loadWithComponents(inner.saveWithoutMetadata(inside.registryAccess()), inside.registryAccess());
        check.accept(empty(inner), "a reloaded nested entrance has no saved preview to fall back to");
        entrance.refreshPreviewSnapshot();
        var nested = entrance.getPreviewBlockEntities().stream()
                .filter(tag -> tag.getString("id").equals("recursivefactory:recursive_factory")).findFirst().orElseThrow();
        var virtual = new RecursiveFactoryBlockEntity(BlockPos.ZERO, inner.getBlockState());
        virtual.loadWithComponents(nested, outside.registryAccess());
        virtual.acceptPreviewTag(nested.getCompound(CACHE), outside.registryAccess());
        check.accept(virtual.getPreviewBlocks().stream().anyMatch(block -> block.state().is(Blocks.DIAMOND_BLOCK))
                        && !virtual.saveWithoutMetadata(outside.registryAccess()).contains(CACHE),
                "nested entrances receive explicit display data while their own save NBT stays cache-free");
        var blueprint = FactoryBlueprint.capture(inside, FactoryData.get(outside.getServer()), room, room.anchorCell(), "preview-cache");
        check.accept(!containsCache(blueprint.serialize(outside.registryAccess())),
                "nested factory blueprints contain no preview cache at any depth");
        var template = new StructureTemplate();
        template.fillFromWorld(outside, entrance.getBlockPos(), new Vec3i(1, 1, 1), false, null);
        check.accept(!containsCache(template.save(new CompoundTag())), "vanilla structure capture excludes preview data too");

        // Remote displays already persist only a source link. Keep their initial sync working as well.
        BlockPos displayPos = entrance.getBlockPos().east(5);
        outside.setBlockAndUpdate(displayPos, ModBlocks.FACTORY_PREVIEW.get().defaultBlockState());
        var display = (FactoryPreviewBlockEntity) outside.getBlockEntity(displayPos);
        var link = GlobalPos.of(outside.dimension(), entrance.getBlockPos());
        var displayItem = ModBlocks.FACTORY_PREVIEW_ITEM.toStack();
        displayItem.set(ModDataComponents.PREVIEW_SOURCE.get(), link);
        display.applyComponentsFromItemStack(displayItem);
        display.refreshPreviewSnapshot();
        var displaySave = display.saveWithoutMetadata(outside.registryAccess());
        var restoredDisplay = new FactoryPreviewBlockEntity(displayPos, display.getBlockState());
        restoredDisplay.loadWithComponents(displaySave, outside.registryAccess());
        check.accept(link.equals(restoredDisplay.source()) && empty(restoredDisplay) && !containsCache(displaySave),
                "remote displays persist their source link without any sampled preview");
        restoredDisplay.handleUpdateTag(display.getUpdateTag(outside.registryAccess()), outside.registryAccess());
        check.accept(restoredDisplay.previewTag().equals(display.previewTag()) && !empty(restoredDisplay),
                "remote display initial sync still delivers the rebuilt entrance preview");
    }

    private static CompoundTag marker(String text) {
        var tag = new CompoundTag(); tag.putString("Marker", text); return tag;
    }

    private static boolean empty(FactoryPreviewSource source) {
        return source.getPreviewBlocks().isEmpty() && source.getPreviewEntities().isEmpty()
                && source.getPreviewBlockEntities().isEmpty() && source.getPreviewWires().isEmpty()
                && source.getPreviewBodies().isEmpty() && source.getPreviewRopes().isEmpty();
    }

    private static boolean containsCache(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains(CACHE) || compound.contains("Preview")) return true;
            for (String key : compound.getAllKeys()) if (containsCache(compound.get(key))) return true;
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) if (containsCache(element)) return true;
        }
        return false;
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }
}
