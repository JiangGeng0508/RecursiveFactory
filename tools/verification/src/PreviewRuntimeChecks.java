package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.foundation.utility.BlockHelper;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.preview.*;
import com.zinzinc.recursivefactory.world.*;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = "recursivefactory")
public final class PreviewRuntimeChecks {
    private static RecursiveFactoryBlockEntity outer, inner;
    private static FactoryPreviewBlockEntity display;
    private static BlockPos changed;
    private static BiConsumer<Boolean, String> check;
    private static Runnable finished;
    private static int started;
    private static long stoppedSamples;

    static void start(MinecraftServer server, BiConsumer<Boolean, String> checks, Runnable finish) {
        check = checks; finished = finish; started = server.getTickCount();
        ServerLevel outside = server.overworld(), inside = server.getLevel(FactoryDimension.LEVEL_KEY);
        outer = door(outside, new BlockPos(320, 160, 320));
        var outerRoom = FactoryData.get(server).factory(outer.getFactoryId());
        inner = door(inside, FactoryBlueprint.origin(outerRoom.anchorCell()).offset(5, 0, 5));
        changed = FactoryBlueprint.origin(FactoryData.get(server).factory(inner.getFactoryId()).anchorCell()).offset(3, 0, 3);
        inside.setBlockAndUpdate(changed, Blocks.DIAMOND_BLOCK.defaultBlockState());
        outside.setBlockAndUpdate(outer.getBlockPos().east(3), ModBlocks.FACTORY_PREVIEW.get().defaultBlockState());
        display = (FactoryPreviewBlockEntity) outside.getBlockEntity(outer.getBlockPos().east(3));
        var item = ModBlocks.FACTORY_PREVIEW_ITEM.toStack();
        item.set(ModDataComponents.PREVIEW_SOURCE.get(), GlobalPos.of(outside.dimension(), outer.getBlockPos()));
        display.applyComponentsFromItemStack(item);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if (finished == null) return;
        MinecraftServer server = event.getServer();
        int elapsed = server.getTickCount() - started;
        try {
            if (elapsed < 65) { PreviewScheduler.watch(outer, 0); PreviewScheduler.watch(display, 0); }
            if (elapsed == 20) server.getLevel(FactoryDimension.LEVEL_KEY).setBlockAndUpdate(changed, Blocks.EMERALD_BLOCK.defaultBlockState());
            if (elapsed == 44) {
                var tag = outer.getPreviewBlockEntities().stream().filter(value -> value.contains("PreviewBlocks")).findFirst().orElseThrow();
                var blocks = PreviewCodec.readPreviewBlocks(tag.getCompound("PreviewBlocks"), server.registryAccess());
                check.accept(blocks.stream().anyMatch(block -> block.state().is(Blocks.EMERALD_BLOCK)),
                        "real server ticks propagate changed nested contents with no players in either room");
                check.accept(display.previewTag().equals(outer.previewTag()), "remote display shares the scheduled entrance snapshot");
                CompoundTag full = PreviewTransport.full(outer);
                check.accept(PreviewTransport.receive(display, full).equals(outer.previewTag()), "full preview establishes a client stream base");
                CompoundTag next = outer.previewTag(); next.putString("RuntimeMarker", "updated");
                CompoundTag patch = new CompoundTag(); patch.putLong("Base", full.getLong("Revision"));
                patch.putLong("Revision", full.getLong("Revision") + 1); patch.putInt("Interval", 4);
                patch.put("Data", PreviewDelta.between(outer.previewTag(), next));
                check.accept(PreviewTransport.receive(display, patch).equals(next) && PreviewTransport.interval(display) == 4,
                        "versioned delta updates the client stream and its interpolation interval");
                CompoundTag invalid = new CompoundTag(); invalid.putLong("Base", -9); invalid.putLong("Revision", 999);
                check.accept(PreviewTransport.receive(display, invalid) == null && !PreviewTransport.synchronizedWithServer(display),
                        "mismatched delta invalidates the stream and requires a full snapshot");
                check.accept(PreviewTransport.receive(display, full).equals(outer.previewTag()), "full preview restores a desynchronized stream");
                inner.setFactoryId(outer.getFactoryId());
            }
            if (elapsed == 64) check.accept(outer.previewTag().toString().length() < 300000,
                    "malformed cyclic room binding stays bounded across repeated scheduled updates");
            if (elapsed == 68) stoppedSamples = PreviewScheduler.get(server).dynamicSamples;
            if (elapsed >= 72) {
                check.accept(stoppedSamples == PreviewScheduler.get(server).dynamicSamples,
                        "server stops sampling after the last entrance and remote-display demand expires");
                Runnable done = finished; finished = null; done.run();
            }
        } catch (Throwable error) {
            check.accept(false, "scheduled preview runtime check: " + error); error.printStackTrace();
            Runnable done = finished; finished = null; done.run();
        }
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }
}
