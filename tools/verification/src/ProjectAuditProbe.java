package com.zinzinc.recursivefactory.probe;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.compat.FactorySchematicMaterials;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.network.FactoryPickBlockPacket;
import com.zinzinc.recursivefactory.world.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Integration checks against real registered blocks, capabilities, saved data and blueprint NBT. */
@EventBusSubscriber(modid = "recursivefactory")
public final class ProjectAuditProbe {
    private static int checks, failures;
    private static void check(boolean ok, String description) {
        checks++;
        System.out.println("[rfaudit] " + (ok ? "PASS " : "FAIL ") + description);
        if (!ok) failures++;
    }
    private static void run(String name, Runnable work) {
        try { work.run(); } catch (Throwable error) { failures++; System.out.println("[rfaudit] FAIL " + name); error.printStackTrace(); }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        ServerLevel outside = event.getServer().overworld();
        ServerLevel inside = event.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        run("item tooltips", () -> tooltips(outside, inside));
        run("layout", () -> layout(inside));
        run("copy", () -> copy(outside, inside));
        run("fluid cycle", () -> fluids(outside, inside));
        run("item and redstone relays", () -> relays(outside, inside));
        run("requests and placement", () -> requests(outside, inside));
        run("materials", () -> materials(outside));
        run("cannon", () -> {
            if (com.zinzinc.recursivefactory.RecursiveFactory.powerAvailable()) CannonChecks.cannon(outside, inside);
        });
        run("cannon floors", () -> {
            if (com.zinzinc.recursivefactory.RecursiveFactory.powerAvailable()) CannonChecks.cannonFloors(outside, inside);
        });
        System.out.println("[rfaudit] COMPLETE checks=" + checks + " failures=" + failures);
        event.getServer().halt(false);
    }

    private static void tooltips(ServerLevel outside, ServerLevel inside) {
        for (boolean preview : new boolean[]{true, false}) {
            var component = preview ? ModDataComponents.PREVIEW_SOURCE.get() : ModDataComponents.COPY_SOURCE.get();
            String key = preview ? "item.recursivefactory.factory_preview.source" : "item.recursivefactory.copy_source";
            for (TooltipFlag flag : new TooltipFlag[]{TooltipFlag.NORMAL, TooltipFlag.ADVANCED}) {
                for (ServerLevel sourceLevel : new ServerLevel[]{null, outside, inside}) {
                    ItemStack stack = preview ? ModBlocks.FACTORY_PREVIEW_ITEM.toStack() : ModBlocks.RECURSIVE_FACTORY_ITEM.toStack();
                    GlobalPos source = sourceLevel == null ? null : GlobalPos.of(sourceLevel.dimension(), new BlockPos(-12, 94, 34));
                    if (source != null) stack.set(component, source);
                    var lines = stack.getTooltipLines(Item.TooltipContext.of(outside), null, flag);
                    var sourceLines = lines.stream().map(Component::getContents)
                            .filter(contents -> contents instanceof TranslatableContents text && text.getKey().equals(key))
                            .map(contents -> (TranslatableContents) contents).toList();
                    for (Component line : lines) {
                        line.getString();
                        Component.Serializer.toJson(line, outside.registryAccess());
                    }
                    boolean correct = source == null ? sourceLines.isEmpty() : sourceLines.size() == 1
                            && Arrays.equals(sourceLines.getFirst().getArgs(), new Object[]{
                                    source.dimension().location().toString(), -12, 94, 34});
                    check(correct && Objects.equals(source, stack.get(component)),
                            (preview ? "preview" : "copy") + " tooltip builds and serializes without changing its binding: "
                                    + (sourceLevel == null ? "unbound" : sourceLevel.dimension().location())
                                    + ", advanced=" + flag.isAdvanced());
                }
            }
        }
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }
    private static FactoryData.FactoryRecord record(ServerLevel level, int id) { return FactoryData.get(level.getServer()).factory(id); }
    private static void refused(Runnable work, String message) {
        boolean refused = false;
        try { work.run(); } catch (FactoryBlueprint.Refusal expected) { refused = true; }
        check(refused, message);
    }
    private static void layout(ServerLevel level) {
        var data = new FactoryData();
        data.create(null, 0, List.of(BlockPos.ZERO, new BlockPos(512, 0, 0)), 0);
        var next = data.create(null, 0);
        check(next.anchorCell().roomX() == 1040, "new factory skips a slot already occupied by an expanded room");
        var shapes = new FactoryData();
        shapes.create(null, 0);
        var copy = shapes.create(null, 0, List.of(new BlockPos(-512, 0, 0), BlockPos.ZERO), 0);
        check(copy.anchorCell().roomX() == 1040 && copy.cells().get(1).roomX() == 528,
                "allocation checks the entire copied footprint and anchors at offset zero");
        check(!data.canOccupy(next.id(), 528, 16), "growth cannot overlap another factory");
        check(!FactoryRoomLayout.fits(level, 16, -64, 16) && !FactoryRoomLayout.fits(level, 16, 192, 16),
                "room bounds include the complete shell and bedrock floor");
        check(FactoryRoomLayout.baseRoomY(level, List.of(BlockPos.ZERO, new BlockPos(0, -80, 0))) == 32,
                "copying from an upper cell shifts the room to preserve lower cells");
        refused(() -> FactoryRoomLayout.baseRoomY(level, List.of(BlockPos.ZERO, new BlockPos(0, 240, 0))),
                "an oversized vertical layout is rejected before allocation");
        refused(() -> FactoryRoomLayout.validate(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0))), "unaligned cells rejected");
        refused(() -> FactoryRoomLayout.validate(List.of(BlockPos.ZERO, BlockPos.ZERO)), "duplicate cells rejected");
        var gap = new FactoryData.FactoryRecord(1, null, 0, 0, 0, null, BlockPos.ZERO,
                List.of(new FactoryData.FactoryRecord.Cell(BlockPos.ZERO, 16, 0, 16, true),
                        new FactoryData.FactoryRecord.Cell(BlockPos.ZERO, 16, 32, 16, true)), -1);
        check(FactoryDimension.shellSide(gap, new BlockPos(24, 79, 24)) == Direction.UP
                        && FactoryDimension.shellSide(gap, new BlockPos(24, 96, 24)) == Direction.DOWN,
                "disconnected vertical surfaces have their own up/down channels");
        check(!FactoryDimension.isFreeSpace(gap, new BlockPos(24, 79, 24)), "intermediate ceiling is not treated as room interior");
        var saved = FactoryData.get(level.getServer());
        int sparse = FactoryDimension.newRoom(level, saved, null, 0,
                List.of(BlockPos.ZERO, new BlockPos(8192, 0, 8192)));
        var sparseRoom = saved.factory(sparse);
        check(sparseRoom.cells().size() == 2 && sparseRoom.cells().stream().allMatch(cell ->
                level.getBlockState(new BlockPos(cell.roomX(), cell.baseY(), cell.roomZ())).is(ModBlocks.FACTORY_BARRIER.get())),
                "sparse room builds only its occupied cells, not 67 million intervening columns");
    }

    private static void copy(ServerLevel outside, ServerLevel inside) {
        var entrance = door(outside, new BlockPos(100, 200, 100));
        door(outside, entrance.getBlockPos().above());
        var data = FactoryData.get(outside.getServer());
        var original = record(outside, entrance.getFactoryId());
        BlockPos origin = FactoryBlueprint.origin(original.anchorCell());
        inside.setBlockAndUpdate(origin.offset(4, 13, 4), Blocks.DIAMOND_BLOCK.defaultBlockState());
        inside.setBlockAndUpdate(origin.offset(4, 14, 4), Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) inside.getBlockEntity(origin.offset(4, 14, 4))).setItem(0, new ItemStack(Items.EMERALD, 7));
        var nested = door(inside, origin.offset(4, 15, 4));
        BlockPos nestedOrigin = FactoryBlueprint.origin(record(outside, nested.getFactoryId()).anchorCell());
        inside.setBlockAndUpdate(nestedOrigin.offset(3, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState());
        // Floor changes travel with the room: one tile the player swapped out and one hole dug into the
        // checkerboard. The rest of that layer is the tile the room generated and is not written down.
        BlockPos rebuiltFloor = origin.offset(2, -1, 2);
        BlockPos clearedFloor = origin.offset(3, -1, 3);
        check(inside.getBlockState(rebuiltFloor).equals(FactoryDimension.floorState(rebuiltFloor.getX(), rebuiltFloor.getZ()))
                        && inside.getBlockState(clearedFloor).equals(FactoryDimension.floorState(clearedFloor.getX(), clearedFloor.getZ())),
                "test room starts on the checkerboard the room is built with");
        inside.setBlockAndUpdate(rebuiltFloor, Blocks.GOLD_BLOCK.defaultBlockState());
        inside.setBlockAndUpdate(clearedFloor, Blocks.AIR.defaultBlockState());
        var blueprint = FactoryBlueprint.capture(inside, data, original, original.anchorCell(), "audit.nbt");
        check(blueprint.blocks().size() == 5 && blueprint.rooms().size() == 2,
                "room contents, floor changes and nested factory are captured");
        CompoundTag serialized = blueprint.serialize(outside.registryAccess());
        var restored = FactoryBlueprint.read(outside.getServer(), "audit.nbt", serialized);
        check(restored != null && restored.size() == 6 && restored.rooms().size() == 2, "complete nested snapshot survives NBT round trip");
        BlockPos target = new BlockPos(120, 200, 100);
        outside.setBlockAndUpdate(target, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState());
        int copied = FactoryBlueprint.build(outside, target, restored, null);
        BlockPos to = FactoryBlueprint.origin(record(outside, copied).anchorCell());
        check(inside.getBlockState(to.offset(4, 13, 4)).is(Blocks.DIAMOND_BLOCK), "copy places former ceiling content");
        check(inside.getBlockState(to.offset(2, -1, 2)).is(Blocks.GOLD_BLOCK), "copy keeps a floor tile the player replaced");
        check(inside.getBlockState(to.offset(3, -1, 3)).isAir() && !inside.getBlockState(to.offset(2, -1, 3)).isAir(),
                "copy keeps a hole dug into the floor and leaves the rest of the checkerboard alone");
        check(inside.getBlockEntity(to.offset(4, 14, 4)) instanceof ChestBlockEntity chest
                && chest.getItem(0).getCount() == 7, "copy preserves seam container contents");
        var copiedNested = (RecursiveFactoryBlockEntity) inside.getBlockEntity(to.offset(4, 15, 4));
        check(copiedNested.getFactoryId() != nested.getFactoryId()
                && inside.getBlockState(FactoryBlueprint.origin(record(outside, copiedNested.getFactoryId()).anchorCell()).offset(3, 0, 3))
                        .is(Blocks.GOLD_BLOCK), "seam entrance binds an independent nested factory");
        check(((ChestBlockEntity) inside.getBlockEntity(origin.offset(4, 14, 4))).getItem(0).getCount() == 7,
                "source inventory remains unchanged");
        var before = record(outside, copied);
        refused(() -> FactoryDimension.sizeRoom(inside, data, copied, List.of(BlockPos.ZERO, new BlockPos(16, 0, 0),
                new BlockPos(0, 240, 0))), "invalid expansion rejected");
        check(record(outside, copied).equals(before), "invalid expansion leaves saved room layout unchanged");
        CompoundTag bad = serialized.copy();
        var rooms = bad.getCompound("RecursiveFactory").getList("Rooms", Tag.TAG_COMPOUND);
        rooms.getCompound(0).getList("blocks", Tag.TAG_COMPOUND).getCompound(0).put("pos", ints(100000, 0, 0));
        check(FactoryBlueprint.read(outside.getServer(), "bad-offset", bad) == null, "blueprint cannot write outside its declared room");
        bad = serialized.copy();
        bad.getCompound("RecursiveFactory").getList("Rooms", Tag.TAG_COMPOUND).getCompound(1).putInt("Parent", 999);
        check(FactoryBlueprint.read(outside.getServer(), "bad-parent", bad) == null, "bad nested parent rejects the entire blueprint");
        check(FactoryBlueprint.read(outside.getServer(), "bad\u0000name") == null, "invalid filesystem name fails without an exception");
        check(FactoryBlueprint.read(outside.getServer(), "../../outside.nbt") == null, "blueprint path traversal rejected");
        var loaded = new FactoryData();
        try {
            var method = FactoryData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
            method.setAccessible(true);
            loaded = (FactoryData) method.invoke(null, data.save(new CompoundTag(), outside.registryAccess()), outside.registryAccess());
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
        check(loaded.factory(copied).equals(record(outside, copied)), "expanded copied room survives SavedData reload");
    }
    private static ListTag ints(int... values) { var list = new ListTag(); for (int value : values) list.add(IntTag.valueOf(value)); return list; }

    private static void fluids(ServerLevel outside, ServerLevel inside) {
        var a = door(outside, new BlockPos(200, 200, 100));
        var data = FactoryData.get(outside.getServer());
        var room = record(outside, a.getFactoryId());
        BlockPos wall = FactoryDimension.wallLine(room, room.anchorCell(), Direction.NORTH).getFirst();
        BlockPos inlet = FactoryDimension.inward(room, wall, Direction.NORTH);
        inside.setBlockAndUpdate(inlet, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState());
        var cycle = (RecursiveFactoryBlockEntity) inside.getBlockEntity(inlet);
        cycle.setFactoryId(a.getFactoryId());
        data.addEntrance(a.getFactoryId(), outside.dimension().location(), inlet,
                room.anchorCell().roomX(), room.anchorCell().roomY(), room.anchorCell().roomZ());
        check(FactoryRelay.extractFluid(a, Direction.NORTH, FluidStack.EMPTY, 1000, true).isEmpty(),
                "cyclic fluid simulation terminates without overflowing the server stack");
        check(FactoryRelay.extractFluid(a, Direction.NORTH, FluidStack.EMPTY, 1000, false).isEmpty(),
                "cyclic fluid extraction terminates and releases its guard");
        cycle.setFactoryId(-1);
        inside.removeBlock(inlet, false);
        // A normal remote buffer remains available after a cyclic query.
        var barrier = (EndpointBlockEntity) inside.getBlockEntity(wall);
        barrier.offerFluid(new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000), Direction.SOUTH);
        check(FactoryRelay.extractFluid(a, Direction.NORTH, FluidStack.EMPTY, 250, true).getAmount() == 250
                && barrier.getPendingFluid().getAmount() == 1000, "normal fluid simulation does not consume buffer");
        check(FactoryRelay.extractFluid(a, Direction.NORTH, FluidStack.EMPTY, 250, false).getAmount() == 250
                && barrier.getPendingFluid().getAmount() == 750, "normal extraction consumes exactly the returned amount");
    }

    private static void requests(ServerLevel outside, ServerLevel inside) {
        var entrance = door(outside, new BlockPos(300, 200, 100));
        var player = new FakePlayer(outside, new GameProfile(UUID.randomUUID(), "audit"));
        player.setGameMode(GameType.CREATIVE);
        player.setPos(300, 200, 104);
        check(EndpointPreviewPackets.allowRequest(player, entrance), "nearby entrance preview request allowed");
        check(!EndpointPreviewPackets.allowRequest(player, entrance), "duplicate request throttled per viewer and endpoint");
        var distant = door(outside, new BlockPos(600, 200, 100));
        check(!EndpointPreviewPackets.allowRequest(player, distant), "remote loaded entrance cannot be queried");
        var foreign = door(inside, new BlockPos(-100, 200, -100));
        check(!EndpointPreviewPackets.allowRequest(player, foreign), "preview requests cannot cross player dimensions");
        var data = FactoryData.get(outside.getServer());
        var record = record(outside, entrance.getFactoryId());
        data.addEntrance(record.id(), outside.dimension().location(), entrance.getBlockPos().above(10),
                record.anchorCell().roomX(), 176, record.anchorCell().roomZ());
        BlockPos tooHigh = entrance.getBlockPos().above(11);
        check(!RecursiveFactoryBlock.canExpandAt(outside, tooHigh), "player expansion checks internal height independently of outside height");
        ItemStack ordinary = ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(2);
        player.setItemInHand(InteractionHand.MAIN_HAND, ordinary);
        var result = ((BlockItem) ordinary.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, ordinary,
                new BlockHitResult(Vec3.atCenterOf(tooHigh), Direction.UP, tooHigh, false)));
        check(!result.consumesAction() && ordinary.getCount() == 2 && outside.getBlockState(tooHigh).isAir(),
                "rejected expansion leaves both world and held stack unchanged");
        var source = door(outside, new BlockPos(400, 200, 100));
        door(outside, source.getBlockPos().above());
        var stack = FactoryPickBlockPacket.copyStack(outside, source.getBlockPos(), "audit");
        // Spawn chunks are already entity-tracked during ServerStartedEvent. A merely generated,
        // distant chunk does not expose newly added entities to collision queries until it ticks.
        BlockPos anchor = outside.getSharedSpawnPos().atY(200);
        var pig = net.minecraft.world.entity.EntityType.PIG.create(outside);
        pig.setPos(anchor.getX() + .5, anchor.getY() + 1, anchor.getZ() + .5);
        outside.addFreshEntity(pig);
        check(!outside.isUnobstructed(ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), anchor.above(),
                net.minecraft.world.phys.shapes.CollisionContext.empty()), "test entity participates in placement collision queries");
        player.setPos(anchor.getX(), anchor.getY(), anchor.getZ() + 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var copyResult = ((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false)));
        check(!copyResult.consumesAction() && outside.getBlockState(anchor).isAir() && stack.getCount() == 1,
                "an entity obstructing an extra copied entrance prevents the whole placement");
        pig.discard();
    }

    private static void relays(ServerLevel outside, ServerLevel inside) {
        var entrance = door(outside, new BlockPos(700, 200, 100));
        var room = record(outside, entrance.getFactoryId());
        BlockPos wall = FactoryDimension.wallLine(room, room.anchorCell(), Direction.NORTH).getFirst();
        BlockPos receive = FactoryDimension.inward(room, wall, Direction.NORTH);
        inside.setBlockAndUpdate(receive, Blocks.CHEST.defaultBlockState());
        var input = outside.getCapability(Capabilities.ItemHandler.BLOCK, entrance.getBlockPos(), Direction.NORTH);
        check(input.insertItem(0, new ItemStack(Items.STONE, 12), true).isEmpty() && !entrance.hasPendingItems(),
                "item capability simulation leaves the endpoint unchanged");
        input.insertItem(0, new ItemStack(Items.STONE, 12), false);
        input.insertItem(1, new ItemStack(Items.DIAMOND, 3), false);
        FactoryRelay.transport(entrance);
        var chest = (ChestBlockEntity) inside.getBlockEntity(receive);
        check(chest.getItem(0).is(Items.STONE) && chest.getItem(0).getCount() == 12
                && chest.getItem(1).is(Items.DIAMOND) && chest.getItem(1).getCount() == 3 && !entrance.hasPendingItems(),
                "mixed items reach the matching inner wall without loss or duplication");
        var remote = (EndpointBlockEntity) inside.getBlockEntity(wall);
        int[] signals = new int[6];
        signals[Direction.NORTH.ordinal()] = 9;
        FactoryRelay.syncPower(entrance, signals);
        check(remote.getOutputPower(Direction.SOUTH) == 9 && remote.getOutputPower(Direction.NORTH) == 0,
                "redstone strength reaches the matching wall and does not feed backwards");
        FactoryRelay.syncPower(entrance, new int[6]);
        check(remote.getOutputPower(Direction.SOUTH) == 0, "removing input clears the remote redstone output");
    }

    private static void materials(ServerLevel level) {
        var inventory = new ItemStackHandler(2);
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(tool.getMaxDamage() - 1);
        inventory.setStackInSlot(0, tool);
        var requirement = new ItemRequirement(List.of(new ItemRequirement.StackRequirement(new ItemStack(Items.IRON_PICKAXE),
                ItemRequirement.ItemUseType.DAMAGE)));
        check(FactorySchematicMaterials.pay(requirement, List.of(inventory), level, BlockPos.ZERO).isEmpty()
                && inventory.getStackInSlot(0).isEmpty(), "printing breaks a tool exactly at maximum damage");
        inventory.setStackInSlot(0, new ItemStack(Items.STONE, 2));
        var missing = new ItemRequirement(List.of(new ItemRequirement.StackRequirement(new ItemStack(Items.STONE),
                ItemRequirement.ItemUseType.CONSUME), new ItemRequirement.StackRequirement(new ItemStack(Items.DIAMOND),
                ItemRequirement.ItemUseType.CONSUME)));
        check(!FactorySchematicMaterials.pay(missing, List.of(inventory), level, BlockPos.ZERO).isEmpty()
                && inventory.getStackInSlot(0).getCount() == 2, "missing materials do not partially debit inventories");
    }

    // Keep CEE's synthetic lambda signatures out of the event subscriber. NeoForge reflects over
    // every subscriber method on startup, even when this optional test would not be executed.
    private static final class CannonChecks {
        private static void cannon(ServerLevel outside, ServerLevel inside) {
            try {
                BlockPos source = new BlockPos(800, 200, 100), target = source.east(20);
                outside.getChunk(target);
                outside.getChunk(target.south(4));
                var entrance = door(outside, source);
                BlockPos origin = FactoryBlueprint.origin(record(outside, entrance.getFactoryId()).anchorCell());
                BlockPos support = origin.offset(3, 0, 3);
                inside.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                var grid = com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData.load(inside);
                grid.createDetachedNode(com.george_vi.electroenergetics.simulation.infrastructure.detached_nodes.DetachedNodeType.FIXED,
                        Vec3.atCenterOf(support).add(.52, 0, 0));
                var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
                template.fillFromWorld(outside, source, new Vec3i(1, 1, 1), false, null);
                var path = java.nio.file.Path.of("schematics/uploaded/audit/cannon.nbt");
                java.nio.file.Files.createDirectories(path.getParent());
                NbtIo.writeCompressed(template.save(new CompoundTag()), path);
                var stack = com.simibubi.create.content.schematics.SchematicItem.create(outside, "cannon.nbt", "audit");
                stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR, target);
                stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, true);
                var inventory = new ItemStackHandler(3);
                inventory.setStackInSlot(0, ModBlocks.RECURSIVE_FACTORY_ITEM.toStack());
                inventory.setStackInSlot(1, new ItemStack(Items.STONE));
                inventory.setStackInSlot(2, com.george_vi.electroenergetics.CEEBlocks.CONNECTOR.asStack());
                var cannon = new TestCannon(target.south(4));
                cannon.setLevel(outside);
                cannon.inventory.setStackInSlot(0, stack);
                cannon.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
                cannon.remainingFuel = 100;
                cannon.step();
                cannon.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
                cannon.attachedInventories.add(inventory);
                cannon.step();
                land(cannon, outside);
                int copied = ((RecursiveFactoryBlockEntity) outside.getBlockEntity(target)).getFactoryId();
                BlockPos destination = FactoryBlueprint.origin(record(outside, copied).anchorCell()).offset(3, 0, 3);
                cannon.step();
                check(!cannon.flyingBlocks.isEmpty() && inside.getBlockState(destination).isAir(),
                        "interior support is paid and in flight before placement");
                int fuel = cannon.remainingFuel;
                for (int i = 0; i < 5; i++) cannon.step();
                CompoundTag progress = cannon.saveWithFullMetadata(outside.registryAccess());
                var roomProgress = progress.getCompound("FactoryPrinting").getList("Factories", Tag.TAG_COMPOUND)
                        .getCompound(0).getList("Rooms", Tag.TAG_COMPOUND).getCompound(0);
                check(roomProgress.getInt("WireCursor") == 0 && cannon.remainingFuel == fuel
                        && inventory.getStackInSlot(2).getCount() == 1, "in-flight support does not skip nodes or consume extra fuel");
                var restored = new TestCannon(target.south(4));
                restored.setLevel(outside);
                restored.loadWithComponents(progress, outside.registryAccess());
                land(restored, outside);
                restored.step();
                restored.attachedInventories.add(inventory);
                restored.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
                for (int i = 0; i < 20 && !restored.statusMsg.equals("finished"); i++) { restored.step(); land(restored, outside); }
                Vec3 node = Vec3.atCenterOf(destination).add(.52, 0, 0);
                check(inside.getBlockState(destination).is(Blocks.STONE)
                        && grid.getDynamicNodes().stream().anyMatch(value -> value.getGlobalPos().distanceToSqr(node) < 1e-8),
                        "saved in-flight support lands and its node is restored afterwards");
                check(inventory.getStackInSlot(0).isEmpty() && inventory.getStackInSlot(1).isEmpty()
                        && inventory.getStackInSlot(2).isEmpty() && restored.statusMsg.equals("finished"),
                        "resumed cannon completes and charges each material once");
            } catch (Exception error) { throw new RuntimeException(error); }
        }
        /**
         * A room whose only contents are floor changes: a tile the player replaced and a hole dug into the
         * checkerboard. A cannon builds its own room with a floor of its own, so both have to be printed
         * over it, and neither may cost the material of the generated tile it replaces.
         */
        private static void cannonFloors(ServerLevel outside, ServerLevel inside) {
            try {
                BlockPos source = new BlockPos(1400, 200, 100), target = source.east(20);
                outside.getChunk(target);
                outside.getChunk(target.south(4));
                var entrance = door(outside, source);
                BlockPos origin = FactoryBlueprint.origin(record(outside, entrance.getFactoryId()).anchorCell());
                inside.setBlockAndUpdate(origin.offset(2, -1, 2), Blocks.GOLD_BLOCK.defaultBlockState());
                inside.setBlockAndUpdate(origin.offset(3, -1, 3), Blocks.AIR.defaultBlockState());
                var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
                template.fillFromWorld(outside, source, new Vec3i(1, 1, 1), false, null);
                var path = java.nio.file.Path.of("schematics/uploaded/audit/cannon-floors.nbt");
                java.nio.file.Files.createDirectories(path.getParent());
                NbtIo.writeCompressed(template.save(new CompoundTag()), path);
                var stack = com.simibubi.create.content.schematics.SchematicItem.create(outside, "cannon-floors.nbt", "audit");
                stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR, target);
                stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, true);
                var inventory = new ItemStackHandler(2);
                inventory.setStackInSlot(0, ModBlocks.RECURSIVE_FACTORY_ITEM.toStack());
                inventory.setStackInSlot(1, new ItemStack(Items.GOLD_BLOCK));
                var cannon = new TestCannon(target.south(4));
                cannon.setLevel(outside);
                cannon.inventory.setStackInSlot(0, stack);
                cannon.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
                cannon.remainingFuel = 100;
                // Initializing loads the blueprint and looks for the inventories beside the cannon again,
                // which drops one attached before it, so the materials go on afterwards.
                cannon.step();
                cannon.attachedInventories.add(inventory);
                for (int i = 0; i < 30 && !cannon.statusMsg.equals("finished"); i++) {
                    cannon.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
                    cannon.step();
                    land(cannon, outside);
                }
                int copied = ((RecursiveFactoryBlockEntity) outside.getBlockEntity(target)).getFactoryId();
                BlockPos to = FactoryBlueprint.origin(record(outside, copied).anchorCell());
                check(inside.getBlockState(to.offset(2, -1, 2)).is(Blocks.GOLD_BLOCK),
                        "cannon swaps a floor tile the source room replaced");
                check(inside.getBlockState(to.offset(3, -1, 3)).isAir()
                                && inside.getBlockState(to.offset(2, -1, 3))
                                        .equals(FactoryDimension.floorState(to.getX() + 2, to.getZ() + 3)),
                        "cannon clears a floor hole and leaves the rest of the checkerboard alone");
                check(inventory.getStackInSlot(0).isEmpty() && inventory.getStackInSlot(1).isEmpty()
                                && cannon.statusMsg.equals("finished"),
                        "a floor-only room is charged for the tile it holds and nothing else");
            } catch (Exception error) { throw new RuntimeException(error); }
        }
        private static void land(TestCannon cannon, ServerLevel level) {
            for (var shot : cannon.flyingBlocks) { shot.ticksRemaining = 0; shot.update(level); }
            cannon.flyingBlocks.clear();
        }
        private static final class TestCannon extends com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity {
            TestCannon(BlockPos pos) { super(com.simibubi.create.AllBlockEntityTypes.SCHEMATICANNON.get(), pos,
                    com.simibubi.create.AllBlocks.SCHEMATICANNON.getDefaultState()); }
            void step() { ((com.zinzinc.recursivefactory.compat.FactoryCannonAccess) (Object) this).recursivefactory$cooldown(0); tickPrinter(); }
        }
    }
}
