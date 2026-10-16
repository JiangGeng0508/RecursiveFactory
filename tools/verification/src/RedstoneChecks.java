package com.zinzinc.recursivefactory.probe;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.world.*;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Exercises connection toggles through the actual interaction event and real room walls. */
final class RedstoneChecks {
    static void run(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        var door = door(outside, new BlockPos(980, 200, 100));
        var record = FactoryData.get(outside.getServer()).factory(door.getFactoryId());
        var player = new FakePlayer(outside, new GameProfile(UUID.randomUUID(), "RedstoneAudit"));
        player.setGameMode(GameType.SURVIVAL);
        var builder = new FakePlayer(inside, new GameProfile(UUID.randomUUID(), "RedstoneInside"));
        builder.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        builder.setShiftKeyDown(true);
        check.accept(door.redstonePortCount() == 0, "new entrances have no enabled redstone connections");

        for (Direction face : Direction.values()) {
            BlockPos wall = FactoryDimension.wallLine(record, record.anchorCell(), face).getFirst();
            var barrier = (EndpointBlockEntity) inside.getBlockEntity(wall);
            var other = (EndpointBlockEntity) inside.getBlockEntity(
                    FactoryDimension.wallLine(record, record.anchorCell(), face).getLast());
            int[] input = new int[6];
            input[face.ordinal()] = 9;
            FactoryRelay.syncPower(door, input);
            check.accept(door.getInputPower(face) == 0 && barrier.getOutputPower(face.getOpposite()) == 0,
                    "disabled ports do not read or relay: " + face);
            int count = player.getMainHandItem().getCount();
            var click = click(player, door.getBlockPos(), face, InteractionHand.MAIN_HAND);
            FactoryRelay.syncPower(door, input);
            check.accept(click.isCanceled() && click.getCancellationResult().consumesAction()
                            && door.isRedstoneEnabled(face) && player.getMainHandItem().getCount() == count
                            && player.getMainHandItem().getDamageValue() == 0
                            && barrier.getOutputPower(face.getOpposite()) == 0,
                    "a wrench enables one entrance face without materials or damage, still requiring its inner port: " + face);
            click(player, door.getBlockPos(), face, InteractionHand.MAIN_HAND);
            check.accept(!door.isRedstoneEnabled(face) && player.getMainHandItem().getCount() == count,
                    "a second wrench click disables that face: " + face);
            click(player, door.getBlockPos(), face, InteractionHand.MAIN_HAND);
            FactoryRelay.syncPower(door, input);
            count = builder.getMainHandItem().getCount();
            var innerClick = click(builder, wall, face.getOpposite(), InteractionHand.MAIN_HAND);
            check.accept(innerClick.isCanceled() && innerClick.getCancellationResult().consumesAction()
                            && builder.getMainHandItem().getCount() == count
                            && barrier.getOutputPower(face.getOpposite()) == 9 && !other.isRemotePowered()
                            && barrier.redstonePortCount() == 1,
                    "sneaking empty-handed enables one barrier and immediately receives existing input: " + face);
            check.accept(FactoryRelay.emittedSignal(barrier.getBlockState(), barrier, face) == 9
                            && FactoryRelay.emittedSignal(barrier.getBlockState(), barrier, face.getOpposite()) == 0,
                    "active output emits only into its own room: " + face);
            FactoryRelay.syncPower(door, new int[6]);
            check.accept(!barrier.isRemotePowered(), "input removal turns off the active barrier: " + face);
            int[] reverse = new int[6];
            reverse[face.getOpposite().ordinal()] = 12;
            FactoryRelay.syncPower(barrier, reverse);
            check.accept(door.getOutputPower(face) == 12 && door.getOutputPower(face.getOpposite()) == 0,
                    "activated link carries reverse input without crossing faces: " + face);
            FactoryRelay.syncPower(barrier, new int[6]);
        }

        var north = (EndpointBlockEntity) inside.getBlockEntity(
                FactoryDimension.wallLine(record, record.anchorCell(), Direction.NORTH).getFirst());
        var another = (EndpointBlockEntity) inside.getBlockEntity(
                FactoryDimension.wallLine(record, record.anchorCell(), Direction.NORTH).getLast());
        another.activateRedstone(Direction.SOUTH);
        int[] a = new int[6], b = new int[6];
        a[Direction.SOUTH.ordinal()] = 5;
        b[Direction.SOUTH.ordinal()] = 11;
        FactoryRelay.syncPower(north, a);
        FactoryRelay.syncPower(another, b);
        check.accept(door.getOutputPower(Direction.NORTH) == 11, "multiple enabled barriers combine by maximum strength");
        click(builder, another.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        check.accept(!another.isRedstoneEnabled(Direction.SOUTH) && door.getOutputPower(Direction.NORTH) == 5,
                "disabling the strongest barrier preserves another active barrier's input");
        FactoryRelay.syncPower(north, new int[6]);

        CompoundTag saved = north.saveWithoutMetadata(inside.registryAccess());
        var loaded = new FactoryBarrierBlockEntity(north.getBlockPos(), north.getBlockState());
        loaded.loadWithComponents(saved, inside.registryAccess());
        check.accept(loaded.isRedstoneEnabled(Direction.SOUTH) && loaded.redstonePortCount() == 1,
                "barrier toggling survives a save and load");
        click(player, door.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        saved = door.saveWithoutMetadata(outside.registryAccess());
        var loadedDoor = new RecursiveFactoryBlockEntity(door.getBlockPos(), door.getBlockState());
        loadedDoor.loadWithComponents(saved, outside.registryAccess());
        check.accept(loadedDoor.redstonePortCount() == 5 && !loadedDoor.isRedstoneEnabled(Direction.SOUTH),
                "both enabled and disabled entrance faces survive a save and load");
        saved.remove(EndpointBlockEntity.REDSTONE_PORTS_TAG);
        saved.putInt("OutputPower", 0xffffff);
        saved.putInt("InputPower", 0xffffff);
        loadedDoor.loadWithComponents(saved, outside.registryAccess());
        check.accept(loadedDoor.redstonePortCount() == 0
                        && FactoryRelay.emittedSignal(door.getBlockState(), loadedDoor, Direction.NORTH) == 0,
                "legacy saves default to closed and cannot emit stale persisted power");

        var fresh = door(outside, new BlockPos(1000, 200, 100));
        player.setGameMode(GameType.CREATIVE);
        int count = player.getMainHandItem().getCount();
        click(player, fresh.getBlockPos(), Direction.NORTH, InteractionHand.MAIN_HAND);
        check.accept(fresh.isRedstoneEnabled(Direction.NORTH) && player.getMainHandItem().getCount() == count,
                "creative wrench toggling also preserves the held item");
        player.setShiftKeyDown(true);
        var sneakWrench = click(player, fresh.getBlockPos(), Direction.NORTH, InteractionHand.MAIN_HAND);
        check.accept(!sneakWrench.isCanceled() && fresh.isRedstoneEnabled(Direction.NORTH),
                "sneaking with a wrench does not toggle or intercept the wrench's normal action");
        player.setShiftKeyDown(false);
        player.setGameMode(GameType.ADVENTURE);
        click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        check.accept(!fresh.isRedstoneEnabled(Direction.SOUTH), "players without build permission cannot activate ports");
        player.setGameMode(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, AllItems.WRENCH.asStack());
        click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        check.accept(fresh.isRedstoneEnabled(Direction.SOUTH) && AllItems.WRENCH.isIn(player.getOffhandItem())
                        && player.getOffhandItem().getCount() == 1,
                "an offhand wrench is handled before an empty main hand can teleport");
        player.setShiftKeyDown(true);
        check.accept(!click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND).isCanceled()
                        && fresh.isRedstoneEnabled(Direction.SOUTH), "sneaking with an offhand wrench is not an empty-hand toggle");
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        var bareClick = click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        check.accept(bareClick.isCanceled() && bareClick.getCancellationResult().consumesAction()
                        && !fresh.isRedstoneEnabled(Direction.SOUTH) && fresh.isRedstoneEnabled(Direction.NORTH),
                "sneaking empty-handed disables only the clicked face and consumes block use");
        click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND);
        check.accept(fresh.isRedstoneEnabled(Direction.SOUTH), "sneaking empty-handed can enable the same face again");
        player.setShiftKeyDown(false);
        check.accept(!click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND).isCanceled()
                        && fresh.isRedstoneEnabled(Direction.SOUTH), "normal empty-hand use remains available for teleporting");
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
        check.accept(!click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.MAIN_HAND).isCanceled()
                        && !click(player, fresh.getBlockPos(), Direction.SOUTH, InteractionHand.OFF_HAND).isCanceled()
                        && fresh.isRedstoneEnabled(Direction.SOUTH), "an empty offhand cannot toggle while the main hand holds another item");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.REDSTONE, 2));
        check.accept(!click(player, fresh.getBlockPos(), Direction.EAST, InteractionHand.MAIN_HAND).isCanceled()
                        && !fresh.isRedstoneEnabled(Direction.EAST) && player.getMainHandItem().getCount() == 2,
                "redstone dust no longer activates connections or gets consumed by the connection handler");
        count = builder.getMainHandItem().getCount();
        click(builder, north.getBlockPos(), Direction.NORTH, InteractionHand.MAIN_HAND);
        check.accept(!north.isRedstoneEnabled(Direction.NORTH) && builder.getMainHandItem().getCount() == count,
                "the back of a barrier cannot toggle a connection");

        // Actual neighbour sampling: a pre-existing source begins transmitting when the final port opens.
        var freshRecord = FactoryData.get(outside.getServer()).factory(fresh.getFactoryId());
        var wall = FactoryDimension.wallLine(freshRecord, freshRecord.anchorCell(), Direction.WEST).getFirst();
        var receiver = (EndpointBlockEntity) inside.getBlockEntity(wall);
        click(builder, wall, Direction.EAST, InteractionHand.MAIN_HAND);
        outside.setBlockAndUpdate(fresh.getBlockPos().west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        check.accept(!receiver.isRemotePowered(), "physical source is blocked until the entrance is activated");
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(receiver.getOutputPower(Direction.EAST) == 15, "activating the final port samples existing neighbour power immediately");
        click(builder, wall, Direction.EAST, InteractionHand.MAIN_HAND);
        check.accept(receiver.getOutputPower(Direction.EAST) == 0 && !receiver.isRedstoneEnabled(Direction.EAST),
                "disabling the receiving barrier immediately clears an existing physical signal");
        click(builder, wall, Direction.EAST, InteractionHand.MAIN_HAND);
        check.accept(receiver.getOutputPower(Direction.EAST) == 15, "reopening the barrier restores the existing source");
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(fresh.getInputPower(Direction.WEST) == 0 && receiver.getOutputPower(Direction.EAST) == 0,
                "disabling the sending entrance immediately clears both local and remote power");
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(receiver.getOutputPower(Direction.EAST) == 15, "reopening the entrance samples the source again");
        outside.removeBlock(fresh.getBlockPos().west(), false);
        check.accept(!receiver.isRemotePowered(), "physical source removal clears the enabled link");
        inside.setBlockAndUpdate(wall.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        check.accept(fresh.getOutputPower(Direction.WEST) == 15, "a physical inner source drives the entrance in reverse");
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(fresh.getOutputPower(Direction.WEST) == 0, "disabling the receiving entrance clears reverse output");
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(fresh.getOutputPower(Direction.WEST) == 15, "reopening the entrance restores reverse output");
        click(builder, wall, Direction.EAST, InteractionHand.MAIN_HAND);
        check.accept(receiver.getInputPower(Direction.EAST) == 0 && fresh.getOutputPower(Direction.WEST) == 0,
                "disabling the sending barrier clears reverse input and remote output");
        click(builder, wall, Direction.EAST, InteractionHand.MAIN_HAND);
        check.accept(fresh.getOutputPower(Direction.WEST) == 15, "reopening the sending barrier restores reverse transmission");
        inside.removeBlock(wall.east(), false);
        outside.setBlockAndUpdate(fresh.getBlockPos().west().below(), Blocks.STONE.defaultBlockState());
        outside.setBlockAndUpdate(fresh.getBlockPos().west(), Blocks.REDSTONE_WIRE.defaultBlockState());
        outside.setBlockAndUpdate(fresh.getBlockPos().west(2), Blocks.REDSTONE_BLOCK.defaultBlockState());
        check.accept(outside.getBlockState(fresh.getBlockPos().west())
                        .getValue(net.minecraft.world.level.block.RedStoneWireBlock.EAST).isConnected()
                        && receiver.getOutputPower(Direction.EAST) == 14,
                "real dust connects to the enabled face and relays with one strength lost");
        // A straight vanilla wire extends its unused end visually. A perpendicular branch lets this
        // fixture distinguish the entrance connection from that automatic straight-line stub.
        outside.setBlockAndUpdate(fresh.getBlockPos().west().north().below(), Blocks.STONE.defaultBlockState());
        outside.setBlockAndUpdate(fresh.getBlockPos().west().north(), Blocks.REDSTONE_WIRE.defaultBlockState());
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(!outside.getBlockState(fresh.getBlockPos().west())
                        .getValue(net.minecraft.world.level.block.RedStoneWireBlock.EAST).isConnected()
                        && receiver.getOutputPower(Direction.EAST) == 0,
                "closing a live entrance also disconnects the physical dust shape and signal");
        click(player, fresh.getBlockPos(), Direction.WEST, InteractionHand.MAIN_HAND);
        check.accept(outside.getBlockState(fresh.getBlockPos().west())
                        .getValue(net.minecraft.world.level.block.RedStoneWireBlock.EAST).isConnected()
                        && receiver.getOutputPower(Direction.EAST) == 14,
                "reopening a live entrance restores physical dust shape and strength");
        outside.removeBlock(fresh.getBlockPos().west(2), false);
        check.accept(!receiver.isRemotePowered(), "a powered dust line does not latch after its source is removed");
        check.accept(!fresh.getBlockState().canRedstoneConnectTo(outside, fresh.getBlockPos(), Direction.WEST)
                        && fresh.getBlockState().canRedstoneConnectTo(outside, fresh.getBlockPos(), Direction.EAST),
                "dust connections distinguish enabled and disabled entrance faces");
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }

    static void copies(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        var source = door(outside, new BlockPos(1100, 200, 100));
        var extra = door(outside, source.getBlockPos().east());
        var data = FactoryData.get(outside.getServer());
        var record = data.factory(source.getFactoryId());
        var cell = record.cellAt(source.getBlockPos());
        source.activateRedstone(Direction.NORTH);
        extra.activateRedstone(Direction.UP);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.UP, Direction.DOWN}) {
            var barrier = (EndpointBlockEntity) inside.getBlockEntity(FactoryDimension.wallLine(record, cell, side).getFirst());
            barrier.activateRedstone(side.getOpposite());
        }
        var extraCell = record.cellAt(extra.getBlockPos());
        var extraBarrier = (EndpointBlockEntity) inside.getBlockEntity(
                FactoryDimension.wallLine(record, extraCell, Direction.UP).getFirst());
        extraBarrier.activateRedstone(Direction.DOWN);
        var builder = new FakePlayer(outside, new GameProfile(UUID.randomUUID(), "RedstoneExpansion"));
        builder.setGameMode(GameType.SURVIVAL);
        builder.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        click(builder, source.getBlockPos(), Direction.EAST, InteractionHand.MAIN_HAND);
        check.accept(!source.isRedstoneEnabled(Direction.EAST) && builder.getMainHandItem().getCount() == 1,
                "a merged internal entrance face rejects toggling without changing the held wrench");
        var blueprint = FactoryBlueprint.capture(inside, data, record, cell, "redstone-copy");
        var restored = FactoryBlueprint.read(outside.getServer(), "redstone-copy", blueprint.serialize(outside.registryAccess()));
        check.accept(restored != null && restored.blocks().size() == 4
                        && restored.blocks().stream().allMatch(entry -> entry.nbt() != null
                                && entry.nbt().getInt(EndpointBlockEntity.REDSTONE_PORTS_TAG) != 0
                                && !entry.nbt().contains("FactoryId") && !entry.nbt().contains("OutputPower")),
                "blueprints retain enabled wall, ceiling and bottom ports without runtime identity or power");
        var prepared = FactoryCopyLink.resolve(outside.getServer(), GlobalPos.of(outside.dimension(), source.getBlockPos())).capture();
        BlockPos target = source.getBlockPos().east(20);
        outside.setBlockAndUpdate(target, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState());
        var copy = (RecursiveFactoryBlockEntity) outside.getBlockEntity(target);
        copy.prepareCopy(prepared);
        copy.getBlockState().getBlock().setPlacedBy(outside, target, copy.getBlockState(), null, ModBlocks.RECURSIVE_FACTORY_ITEM.toStack());
        var copyRecord = data.factory(copy.getFactoryId());
        var copyExtra = (RecursiveFactoryBlockEntity) outside.getBlockEntity(target.east());
        check.accept(copy.getFactoryId() != source.getFactoryId() && copy.isRedstoneEnabled(Direction.NORTH)
                        && copyExtra.getFactoryId() == copy.getFactoryId() && copyExtra.isRedstoneEnabled(Direction.UP)
                        && copy.redstonePortCount() == 1 && copyExtra.redstonePortCount() == 1,
                "expanded creative copies preserve each entrance's own connections");
        boolean portsMatch = true;
        for (var entry : restored.blocks()) {
            var barrier = (EndpointBlockEntity) inside.getBlockEntity(FactoryBlueprint.roomPos(copyRecord.anchorCell(), entry.pos()));
            portsMatch &= barrier.getFactoryId() == copy.getFactoryId()
                    && barrier.redstonePorts() == entry.nbt().getInt(EndpointBlockEntity.REDSTONE_PORTS_TAG);
        }
        check.accept(portsMatch, "copied barriers keep new factory bindings and all enabled ports");
        CompoundTag safe = new CompoundTag();
        source.writeSafe(safe, outside.registryAccess());
        check.accept(safe.getInt(EndpointBlockEntity.REDSTONE_PORTS_TAG) == source.redstonePorts(),
                "Create safe NBT includes enabled entrance connections");
        int[] signal = new int[6];
        signal[Direction.NORTH.ordinal()] = 8;
        FactoryRelay.syncPower(copy, signal);
        var receiver = (EndpointBlockEntity) inside.getBlockEntity(
                FactoryDimension.wallLine(copyRecord, copyRecord.anchorCell(), Direction.NORTH).getFirst());
        check.accept(receiver.getOutputPower(Direction.SOUTH) == 8 && !source.isRemotePowered(),
                "copied redstone connections work without powering the original factory");
        FactoryRelay.syncPower(copy, new int[6]);
        door(outside, source.getBlockPos().above());
        click(builder, source.getBlockPos(), Direction.UP, InteractionHand.MAIN_HAND);
        check.accept(!source.isRedstoneEnabled(Direction.UP) && builder.getMainHandItem().getCount() == 1,
                "a vertically merged entrance face rejects toggling without changing the held wrench");
    }

    static void cannon(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        try {
            var source = door(outside, new BlockPos(1200, 200, 100));
            var data = FactoryData.get(outside.getServer());
            var record = data.factory(source.getFactoryId());
            source.activateRedstone(Direction.NORTH);
            for (Direction side : new Direction[]{Direction.NORTH, Direction.UP, Direction.DOWN}) {
                var barrier = (EndpointBlockEntity) inside.getBlockEntity(
                        FactoryDimension.wallLine(record, record.anchorCell(), side).getFirst());
                barrier.activateRedstone(side.getOpposite());
            }
            inside.setBlockAndUpdate(FactoryBlueprint.origin(record.anchorCell()).offset(4, 0, 4), Blocks.STONE.defaultBlockState());
            BlockPos target = source.getBlockPos().east(20);
            outside.getChunk(target);
            outside.getChunk(target.south(4));
            var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
            template.fillFromWorld(outside, source.getBlockPos(), new Vec3i(1, 1, 1), false, null);
            var path = java.nio.file.Path.of("schematics/uploaded/audit/redstone.nbt");
            java.nio.file.Files.createDirectories(path.getParent());
            net.minecraft.nbt.NbtIo.writeCompressed(template.save(new CompoundTag()), path);
            var stack = com.simibubi.create.content.schematics.SchematicItem.create(outside, "redstone.nbt", "audit");
            stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR, target);
            stack.set(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, true);
            var inventory = new net.neoforged.neoforge.items.ItemStackHandler(2);
            inventory.setStackInSlot(0, ModBlocks.RECURSIVE_FACTORY_ITEM.toStack());
            var cannon = new TestCannon(target.south(4));
            cannon.setLevel(outside);
            cannon.inventory.setStackInSlot(0, stack);
            cannon.state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING;
            cannon.remainingFuel = 100;
            cannon.step();
            check.accept(cannon.checklist.required.getInt(Items.REDSTONE) == 0,
                    "the cannon checklist charges no dust for entrance or barrier settings");
            cannon.attachedInventories.add(inventory);
            for (int i = 0; i < 6; i++) { cannon.resume(); cannon.step(); cannon.land(outside); }
            var entrance = (RecursiveFactoryBlockEntity) outside.getBlockEntity(target);
            var printed = data.factory(entrance.getFactoryId());
            check.accept(entrance.isRedstoneEnabled(Direction.NORTH) && inventory.getStackInSlot(0).isEmpty()
                            && inventory.getStackInSlot(1).isEmpty() && cannon.missingItem != null && cannon.missingItem.is(Items.STONE),
                    "the cannon restores the entrance toggle for free and pauses only for real building materials");
            inventory.setStackInSlot(1, new ItemStack(Items.STONE));
            cannon.resume();
            cannon.step();
            var progress = cannon.saveWithFullMetadata(outside.registryAccess());
            var resumed = new TestCannon(target.south(4));
            resumed.setLevel(outside);
            resumed.loadWithComponents(progress, outside.registryAccess());
            resumed.step();
            resumed.attachedInventories.add(inventory);
            for (int i = 0; i < 20 && !resumed.statusMsg.equals("finished"); i++) {
                resumed.resume(); resumed.step(); resumed.land(outside);
            }
            check.accept(resumed.statusMsg.equals("finished") && inventory.getStackInSlot(1).isEmpty(),
                    "the resumed cannon finishes all connection settings without any redstone supply");
            check.accept(inside.getBlockState(FactoryBlueprint.origin(printed.anchorCell()).offset(4, 0, 4)).is(Blocks.STONE),
                    "real room construction still consumes and places its requested material");
            check.accept(java.util.Arrays.stream(new Direction[]{Direction.NORTH, Direction.UP, Direction.DOWN}).allMatch(side -> {
                var barrier = (EndpointBlockEntity) inside.getBlockEntity(
                        FactoryDimension.wallLine(printed, printed.anchorCell(), side).getFirst());
                return barrier.getFactoryId() == printed.id() && barrier.isRedstoneEnabled(side.getOpposite());
            }), "cannon restores wall, ceiling and bottom connections without replacing shell bindings");
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private static final class TestCannon extends com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity {
        TestCannon(BlockPos pos) { super(com.simibubi.create.AllBlockEntityTypes.SCHEMATICANNON.get(), pos,
                com.simibubi.create.AllBlocks.SCHEMATICANNON.getDefaultState()); }
        void step() { ((com.zinzinc.recursivefactory.compat.FactoryCannonAccess) (Object) this).recursivefactory$cooldown(0); tickPrinter(); }
        void resume() { state = com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State.RUNNING; }
        void land(ServerLevel level) {
            for (var shot : flyingBlocks) { shot.ticksRemaining = 0; shot.update(level); }
            flyingBlocks.clear();
        }
    }

    private static PlayerInteractEvent.RightClickBlock click(FakePlayer player, BlockPos pos, Direction face, InteractionHand hand) {
        var event = new PlayerInteractEvent.RightClickBlock(player, hand, pos,
                new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, pos, false));
        NeoForge.EVENT_BUS.post(event);
        return event;
    }
}
