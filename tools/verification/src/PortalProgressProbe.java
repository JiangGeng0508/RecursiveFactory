package com.zinzinc.recursivefactory.probe;

import com.mojang.authlib.GameProfile;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryPortal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import qouteall.imm_ptl.core.portal.Portal;

/** Runs only when explicitly added by verify.init.gradle, in a fresh disposable world. */
@EventBusSubscriber(modid = "recursivefactory")
public final class PortalProgressProbe {
    private static int ticks;
    private static int checks;
    private static int failures;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        ticks++;
        if (ticks == 20) run("portal warmup", () -> preparePortalRooms(event.getServer()));
        if (ticks != 60) return;
        MinecraftServer server = event.getServer();
        run("buffers", () -> buffers(server.overworld()));
        run("allocation", () -> allocation(server));
        run("preview", () -> preview(server));
        run("transport", () -> transport(server));
        run("floors and portals", () -> floors(server));
        System.out.println("[ip-audit] COMPLETE checks=" + checks + " failures=" + failures);
        server.halt(false);
    }

    private static void run(String name, CheckedRunnable test) {
        try { test.run(); }
        catch (Throwable error) {
            failures++;
            System.out.println("[ip-audit] FAIL " + name + ": " + error);
            error.printStackTrace();
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) failures++;
        System.out.println("[ip-audit] " + (condition ? "PASS " : "FAIL ") + description);
    }

    private static RecursiveFactoryBlockEntity emptyEndpoint() {
        var endpoint = new RecursiveFactoryBlockEntity(BlockPos.ZERO, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState());
        endpoint.setFactoryId(1);
        return endpoint;
    }

    private static void buffers(ServerLevel level) {
        var endpoint = emptyEndpoint();
        ItemStack oversized = new ItemStack(Items.IRON_INGOT, 80);
        check(endpoint.offer(0, oversized, Direction.NORTH, true).getCount() == 16, "simulation respects slot capacity");
        check(endpoint.getPendingStack(0).isEmpty() && oversized.getCount() == 80, "simulation changes neither buffer nor caller");
        check(endpoint.offer(0, oversized, Direction.NORTH, false).getCount() == 16, "execution matches simulation");
        check(endpoint.offer(1, new ItemStack(Items.GOLD_INGOT, 3), Direction.WEST, false).isEmpty(), "mixed stream occupies another slot");
        check(endpoint.offer(1, new ItemStack(Items.GOLD_INGOT), Direction.EAST, true).getCount() == 1, "buffer keeps its input face");
        check(endpoint.offer(2, new ItemStack(Items.DIAMOND_SWORD, 2), Direction.SOUTH, false).getCount() == 1, "unstackable item capacity is one");
        check(!endpoint.offer(3, new ItemStack(Items.STONE), null, false).isEmpty(), "unsided input cannot lose its routing face");
        check(!endpoint.offer(-1, new ItemStack(Items.STONE), Direction.UP, false).isEmpty(), "negative slot rejected");
        check(!endpoint.offer(9, new ItemStack(Items.STONE), Direction.UP, false).isEmpty(), "out of range slot rejected");
        check(endpoint.offer(new ItemStack(Items.IRON_INGOT, 2), Direction.NORTH).isEmpty()
                && endpoint.getPendingStack(3).getCount() == 2, "full matching slot does not hide empty buffers");
        check(endpoint.offer(new ItemStack(Items.GOLD_INGOT, 2), Direction.WEST).isEmpty()
                && endpoint.getPendingStack(1).getCount() == 5 && endpoint.getPendingStack(4).isEmpty(), "merge before allocating an empty buffer");
        var restored = emptyEndpoint();
        restored.loadWithComponents(endpoint.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        for (int slot = 0; slot < EndpointBlockEntity.PENDING_SLOTS; slot++) {
            check(ItemStack.matches(endpoint.getPendingStack(slot), restored.getPendingStack(slot))
                    && endpoint.getPendingInput(slot) == restored.getPendingInput(slot), "buffer round trip slot " + slot);
        }
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("FactoryId", 7);
        legacy.put("PendingStack", new ItemStack(Items.DIAMOND, 12).save(level.registryAccess()));
        legacy.putString("PendingInput", "down");
        restored.loadWithComponents(legacy, level.registryAccess());
        check(restored.getFactoryId() == 7 && restored.getPendingStack(0).getCount() == 12
                && restored.getPendingInput(0) == Direction.DOWN, "legacy buffer and routing migrate to slot zero");
        check(restored.getPendingStack(1).isEmpty(), "reload clears previous remaining slots");
    }

    private static FactoryData reload(FactoryData data, MinecraftServer server) throws Exception {
        Method load = FactoryData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        return (FactoryData) load.invoke(null, data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
    }

    private static void allocation(MinecraftServer server) throws Exception {
        FactoryData data = new FactoryData();
        var dimension = server.overworld().dimension().location();
        var first = data.create(null);
        data.bindEntrance(first.id(), dimension, new BlockPos(0, 100, 0));
        check(data.addEntrance(first.id(), dimension, new BlockPos(32, 100, 0), 528, 16), "room expands into the next allocation slot");
        var next = data.create(null);
        check(next.baseChunk().getMinBlockX() == 1040, "new room allocation skips expanded factory footprint");
        data.bindEntrance(next.id(), dimension, new BlockPos(100, 100, 0));
        check(!data.addEntrance(first.id(), dimension, new BlockPos(64, 100, 0), 1040, 16), "growth refuses another factory's room");
        check(!data.addEntrance(first.id(), dimension, new BlockPos(32, 100, 0), 544, 16), "duplicate entrance refused");
        check(!data.addEntrance(first.id(), dimension, new BlockPos(33, 100, 0), 528, 16), "duplicate room cell refused");
        check(!data.canOccupy(first.id(), 30_000_000, 16) && !data.canOccupy(first.id(), -30_000_000, 16), "both world bounds enforced");
        check(!data.canOccupy(first.id(), 1041, 17), "overlapping columns rejected even when not aligned");
        var restored = reload(data, server);
        check(restored.factory(first.id()).cells().equals(data.factory(first.id()).cells()), "room layout survives persistence");
        data.markFloorLaid(first.id(), 16, 16);
        check(reload(data, server).factory(first.id()).cellAt(new BlockPos(0, 100, 0)).floorLaid(), "floor generation flag persists");
        CompoundTag old = data.save(new CompoundTag(), server.registryAccess());
        for (Tag factory : old.getList("Factories", Tag.TAG_COMPOUND)) {
            for (Tag cell : ((CompoundTag) factory).getList("Cells", Tag.TAG_COMPOUND)) ((CompoundTag) cell).remove("FloorLaid");
        }
        old.putInt("NextFactoryId", 1);
        old.putInt("NextSlotIndex", 0);
        Method load = FactoryData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        var legacy = (FactoryData) load.invoke(null, old, server.registryAccess());
        check(legacy.factory(first.id()).cells().stream().allMatch(FactoryData.FactoryRecord.Cell::floorLaid), "legacy floors treated as existing builds");
        var fresh = legacy.create(null);
        check(fresh.id() > next.id() && fresh.baseChunk().getMinBlockX() == 1552, "stale saved counters cannot overwrite existing factories");
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(3, 4, 5);
        var cell = new FactoryData.FactoryRecord.Cell(mutable, 16, 16);
        mutable.set(9, 9, 9);
        check(cell.entrance().equals(new BlockPos(3, 4, 5)), "cell freezes mutable entrance positions");
    }

    private static void preview(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos pos = new BlockPos(300, 100, 300);
        level.setBlock(pos, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), 3);
        var endpoint = (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
        endpoint.setFactoryId(41);
        endpoint.offer(new ItemStack(Items.IRON_INGOT, 5), Direction.EAST);
        var blocks = List.of(new EndpointBlockEntity.PreviewBlock(1, 2, 3, Blocks.STONE.defaultBlockState()));
        var chunk = level.getChunkAt(pos);
        chunk.setUnsaved(false);
        endpoint.updatePreview(blocks);
        check(!chunk.isUnsaved(), "preview refresh does not dirty the chunk");
        CompoundTag saved = endpoint.saveWithoutMetadata(server.registryAccess());
        check(!saved.contains("PreviewBlocks"), "chunk and debug NBT omit the preview cache");
        check(saved.getInt("FactoryId") == 41 && saved.contains("PendingItems"), "persistent binding and items retained");
        CompoundTag update = endpoint.getUpdateTag(server.registryAccess());
        check(EndpointBlockEntity.readPreviewBlocks(update, server.registryAccess()).equals(blocks), "initial update still includes display snapshot");
        check(EndpointBlockEntity.readPreviewBlocks(EndpointBlockEntity.writePreviewBlocks(blocks), server.registryAccess()).equals(blocks), "dedicated preview payload round trip");
        var restored = emptyEndpoint();
        restored.loadWithComponents(update, server.registryAccess());
        check(restored.getPreviewBlocks().isEmpty(), "old embedded snapshots ignored when loading a save");
        restored.handleUpdateTag(update, server.registryAccess());
        check(restored.getPreviewBlocks().equals(blocks), "network update accepts display snapshot");
        restored.handleUpdateTag(saved, server.registryAccess());
        check(restored.getPreviewBlocks().equals(blocks), "state-only update preserves separate preview stream");
        var viewer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "PortalAudit"));
        viewer.setPos(pos.getX() + .5, 101, pos.getZ() + .5);
        check(EndpointPreviewPackets.allowRequest(viewer, endpoint), "nearby preview request allowed");
        check(!EndpointPreviewPackets.allowRequest(viewer, endpoint), "repeat preview request throttled");
        server.getWorldData().overworldData().setGameTime(level.getGameTime() + 40);
        check(EndpointPreviewPackets.allowRequest(viewer, endpoint), "preview request allowed after cooldown");
        var far = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "PortalFar"));
        far.setPos(1000, 100, 1000);
        check(!EndpointPreviewPackets.allowRequest(far, endpoint), "distant preview request rejected");
        var otherWorld = new FakePlayer(server.getLevel(FactoryDimension.LEVEL_KEY), new GameProfile(UUID.randomUUID(), "PortalOther"));
        otherWorld.setPos(pos.getX() + .5, 101, pos.getZ() + .5);
        check(!EndpointPreviewPackets.allowRequest(otherWorld, endpoint), "other dimension preview request rejected");
    }

    private static Pair pair(MinecraftServer server, BlockPos outsidePos, BlockPos insidePos) {
        ServerLevel outside = server.overworld();
        ServerLevel inside = server.getLevel(FactoryDimension.LEVEL_KEY);
        FactoryData data = FactoryData.get(server);
        var record = data.create(null);
        outside.setBlock(outsidePos, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), 3);
        inside.setBlock(insidePos, ModBlocks.MIRROR_FACTORY.get().defaultBlockState(), 3);
        var entrance = (RecursiveFactoryBlockEntity) outside.getBlockEntity(outsidePos);
        var mirror = (MirrorFactoryBlockEntity) inside.getBlockEntity(insidePos);
        entrance.setFactoryId(record.id());
        mirror.setFactoryId(record.id());
        data.bindEntrance(record.id(), outside.dimension().location(), outsidePos);
        data.bindMirror(record.id(), inside.dimension().location(), insidePos);
        return new Pair(entrance, mirror);
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        return (ChestBlockEntity) level.getBlockEntity(pos);
    }

    private static IItemHandler handler(EndpointBlockEntity endpoint, Direction face) {
        return endpoint.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, endpoint.getBlockPos(), face);
    }

    private static void transport(MinecraftServer server) {
        ServerLevel inside = server.getLevel(FactoryDimension.LEVEL_KEY);
        ServerLevel outside = server.overworld();
        Pair pair = pair(server, new BlockPos(400, 100, 400), new BlockPos(10000, 80, 10000));
        for (Direction input : Direction.values()) {
            var target = chest(inside, pair.mirror().getBlockPos().relative(input.getOpposite()));
            IItemHandler cap = handler(pair.entrance(), input);
            check(cap.getSlots() == 36, "all 27 remote chest slots exposed on " + input);
            check(cap.insertItem(0, new ItemStack(Items.IRON_INGOT, 7), false).isEmpty()
                    && cap.insertItem(1, new ItemStack(Items.GOLD_INGOT, 3), false).isEmpty(), "mixed insertion on " + input);
            FactoryRelay.transport(pair.entrance());
            check(target.getItem(0).is(Items.IRON_INGOT) && target.getItem(0).getCount() == 7
                    && target.getItem(1).is(Items.GOLD_INGOT), "both materials arrive on matching remote face " + input);
            target.setItem(26, new ItemStack(Items.DIAMOND, 11));
            check(cap.getStackInSlot(35).is(Items.DIAMOND), "filtered extraction can see last remote slot " + input);
            check(cap.extractItem(35, 4, true).getCount() == 4 && target.getItem(26).getCount() == 11, "remote extraction simulation is read only " + input);
            check(cap.extractItem(35, 4, false).getCount() == 4 && target.getItem(26).getCount() == 7, "remote extraction addresses the advertised slot " + input);
            check(cap.extractItem(0, 64, false).isEmpty() && !cap.insertItem(35, new ItemStack(Items.STONE), false).isEmpty(), "buffers are input-only and remote slots output-only " + input);
            target.clearContent();
            var reverse = chest(outside, pair.entrance().getBlockPos().relative(input.getOpposite()));
            pair.mirror().offer(new ItemStack(Items.EMERALD, 2), input);
            FactoryRelay.transport(pair.mirror());
            check(reverse.getItem(0).is(Items.EMERALD), "reverse transport works on " + input);
            inside.removeBlock(target.getBlockPos(), false);
            outside.removeBlock(reverse.getBlockPos(), false);
        }
        // One destination may block while another face continues to move a different material.
        var target = chest(inside, pair.mirror().getBlockPos().east());
        for (int slot = 0; slot < 27; slot++) target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        pair.entrance().offer(0, new ItemStack(Items.IRON_INGOT, 7), Direction.WEST, false);
        var free = chest(inside, pair.mirror().getBlockPos().south());
        pair.entrance().offer(1, new ItemStack(Items.GOLD_INGOT, 3), Direction.NORTH, false);
        FactoryRelay.transport(pair.entrance());
        check(pair.entrance().getPendingStack(0).getCount() == 7 && free.getItem(0).is(Items.GOLD_INGOT), "blocked face does not stall a different face");
        inside.getChunkAt(pair.mirror().getBlockPos()).setUnsaved(false);
        outside.getChunkAt(pair.entrance().getBlockPos()).setUnsaved(false);
        FactoryRelay.transport(pair.entrance());
        check(!outside.getChunkAt(pair.entrance().getBlockPos()).isUnsaved(), "unchanged blocked buffer does not dirty chunk each tick");
        target.clearContent();
        FactoryRelay.transport(pair.entrance());
        check(target.getItem(0).getCount() == 7 && pair.entrance().getPendingStack(0).isEmpty(), "blocked stream resumes without losing items");
        // Walk through another endpoint, then close the route into a cycle.
        inside.removeBlock(target.getBlockPos(), false);
        Pair nested = pair(server, new BlockPos(500, 100, 500), new BlockPos(11000, 80, 11000));
        BlockPos nestedEntrancePos = pair.mirror().getBlockPos().east();
        inside.setBlock(nestedEntrancePos, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), 3);
        var nestedEntrance = (RecursiveFactoryBlockEntity) inside.getBlockEntity(nestedEntrancePos);
        nestedEntrance.setFactoryId(nested.entrance().getFactoryId());
        FactoryData.get(server).bindEntrance(nestedEntrance.getFactoryId(), inside.dimension().location(), nestedEntrancePos);
        var nestedChest = chest(inside, nested.mirror().getBlockPos().east());
        nestedChest.setItem(20, new ItemStack(Items.NETHERITE_INGOT, 8));
        var route = handler(pair.entrance(), Direction.WEST);
        check(route.getStackInSlot(29).is(Items.NETHERITE_INGOT)
                && route.extractItem(29, 3, false).getCount() == 3, "nested route reaches a non-first inventory slot");
        inside.removeBlock(nestedChest.getBlockPos(), false);
        inside.setBlock(nestedChest.getBlockPos(), ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), 3);
        ((EndpointBlockEntity) inside.getBlockEntity(nestedChest.getBlockPos())).setFactoryId(pair.entrance().getFactoryId());
        check(route.getSlots() == EndpointBlockEntity.PENDING_SLOTS, "cyclic inventory route terminates without exposing buffers");
    }

    private static void floors(MinecraftServer server) {
        ServerLevel inside = server.getLevel(FactoryDimension.LEVEL_KEY);
        ServerLevel outside = server.overworld();
        FactoryData data = FactoryData.get(server);
        for (boolean shortRoom : new boolean[]{false, true}) {
            BlockPos pos = new BlockPos(shortRoom ? 650 : 600, 100, 600);
            var block = shortRoom ? ModBlocks.RECURSIVE_FACTORY_SHORT.get() : ModBlocks.RECURSIVE_FACTORY.get();
            var endpoint = (RecursiveFactoryBlockEntity) outside.getBlockEntity(pos);
            var record = data.factory(endpoint.getFactoryId());
            var cell = record.cellAt(pos);
            BlockPos marker = new BlockPos(cell.roomX() + 8, FactoryData.FLOOR_Y, cell.roomZ() + 8);
            BlockPos hole = marker.east(2);
            inside.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            inside.removeBlock(hole, false);
            FactoryDimension.prepare(inside, record);
            check(inside.getBlockState(marker).is(Blocks.DIAMOND_BLOCK) && inside.getBlockState(hole).isAir(), "modified floor and hole survive prepare (short=" + shortRoom + ")");
            FactoryConfig.REPAIR_BROKEN_FLOOR.set(true);
            FactoryDimension.prepare(inside, record);
            FactoryConfig.REPAIR_BROKEN_FLOOR.set(false);
            check(!inside.getBlockState(hole).isAir() && inside.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "floor repair fills only missing blocks (short=" + shortRoom + ")");
            check(FactoryDimension.roomHeight(server, record) == (shortRoom ? 16 : 32), "portal room height preserved (short=" + shortRoom + ")");
            check(data.factory(record.id()).cellAt(pos).floorLaid(), "generated floor recorded (short=" + shortRoom + ")");
            long portals = portalCount(outside, record.id()) + portalCount(inside, record.id());
            check(portals == 10, "five pairs of portal entities created (short=" + shortRoom + ", actual=" + portals + ")");
            FactoryPortal.ensure(outside, record.id());
            check(portalCount(outside, record.id()) + portalCount(inside, record.id()) == portals, "portal rebuild does not duplicate planes (short=" + shortRoom + ")");
            BlockPos grownPos = pos.east();
            outside.setBlock(grownPos, block.defaultBlockState(), 3);
            block.setPlacedBy(outside, grownPos, outside.getBlockState(grownPos), null, new ItemStack(block));
            check(((EndpointBlockEntity) outside.getBlockEntity(grownPos)).getFactoryId() == record.id()
                    && data.factory(record.id()).cells().size() == 2, "horizontal portal expansion preserved (short=" + shortRoom + ")");
            check(inside.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "growth preserves existing floor edits (short=" + shortRoom + ")");
        }
    }

    private static void preparePortalRooms(MinecraftServer server) {
        // New chunks do not expose their entity lookup until the chunk manager ticks. Construct
        // rooms in an earlier tick, just as normal gameplay does before inspecting the portal entities.
        ServerLevel outside = server.overworld();
        for (boolean shortRoom : new boolean[]{false, true}) {
            BlockPos pos = new BlockPos(shortRoom ? 650 : 600, 100, 600);
            outside.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            var block = shortRoom ? ModBlocks.RECURSIVE_FACTORY_SHORT.get() : ModBlocks.RECURSIVE_FACTORY.get();
            outside.setBlock(pos, block.defaultBlockState(), 3);
            block.setPlacedBy(outside, pos, outside.getBlockState(pos), null, new ItemStack(block));
        }
    }

    private static long portalCount(ServerLevel level, int factoryId) {
        long count = 0;
        for (var entity : level.getAllEntities()) if (entity instanceof Portal portal && !entity.isRemoved()
                && FactoryPortal.tag(factoryId).equals(portal.portalTag)) count++;
        return count;
    }

    private record Pair(RecursiveFactoryBlockEntity entrance, MirrorFactoryBlockEntity mirror) { }
    private interface CheckedRunnable { void run() throws Exception; }
}
