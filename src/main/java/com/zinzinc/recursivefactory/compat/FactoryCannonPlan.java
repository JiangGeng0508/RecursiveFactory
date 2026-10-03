package com.zinzinc.recursivefactory.compat;

import com.simibubi.create.content.schematics.SchematicPrinter;
import com.simibubi.create.content.schematics.cannon.LaunchedItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.mixin.SchematicPrinterAccessor;
import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cannon owns the immutable room snapshots and all their cursors, including nested rooms. */
public final class FactoryCannonPlan {
    private final List<FactoryPrint> factories = new ArrayList<>();
    private ItemStack schematic = ItemStack.EMPTY;
    private CompoundTag saved;
    private RoomPrint placingRoom;
    private FactoryPrint placingFactory;

    private static final class FactoryPrint {
        final FactoryBlueprint blueprint;
        final List<RoomPrint> rooms = new ArrayList<>();
        final Map<BlockPos, BlockPos> entrances = new LinkedHashMap<>();
        int cursor;

        FactoryPrint(FactoryBlueprint blueprint) {
            this.blueprint = blueprint;
            for (FactoryBlueprint.Room room : blueprint.rooms()) rooms.add(new RoomPrint(room));
        }
    }

    private static final class RoomPrint {
        final FactoryBlueprint.Room room;
        final List<FactoryBlueprint.Entry> entries;
        final List<CompoundTag> wires;
        int id;
        int cursor;
        int wireCursor;
        SchematicPrinter printer;
        BlockPos origin;

        RoomPrint(FactoryBlueprint.Room room) {
            this.room = room;
            entries = room.blocks().stream().sorted(Comparator
                    .comparing((FactoryBlueprint.Entry e) -> SchematicPrinter.shouldDeferBlock(e.state()))
                    .thenComparingInt(e -> e.pos().getY()).thenComparingInt(e -> e.pos().getZ())
                    .thenComparingInt(e -> e.pos().getX())).toList();
            wires = RecursiveFactory.powerAvailable() ? FactoryWires.printSteps(room.wires()) : List.of();
        }

        void allocate(ServerLevel level) {
            if (id != 0) return;
            FactoryData data = FactoryData.get(level.getServer());
            id = FactoryDimension.newRoom(level, data, null, room.colorIndex());
            FactoryDimension.sizeRoom(level, data, id, room.cells());
        }

        SchematicPrinter reader(ServerLevel level) {
            if (printer != null) return printer;
            var record = FactoryData.get(level.getServer()).factory(id);
            origin = id == 0 || record == null ? BlockPos.ZERO
                    : new BlockPos(record.baseChunk().getMinBlockX(), FactoryData.FLOOR_Y + 1, record.baseChunk().getMinBlockZ());
            SchematicLevel view = new SchematicLevel(origin, level);
            for (FactoryBlueprint.Entry entry : entries) {
                BlockPos pos = origin.offset(entry.pos());
                view.setBlock(pos, entry.state(), Block.UPDATE_CLIENTS);
                BlockEntity be = view.getBlockEntity(pos);
                if (be != null && entry.nbt() != null) {
                    CompoundTag nbt = entry.nbt().copy();
                    nbt.putInt("x", pos.getX());
                    nbt.putInt("y", pos.getY());
                    nbt.putInt("z", pos.getZ());
                    be.loadWithComponents(nbt, level.registryAccess());
                }
            }
            printer = new SchematicPrinter();
            var access = (SchematicPrinterAccessor) printer;
            access.recursivefactory$reader(view);
            access.recursivefactory$anchor(origin);
            access.recursivefactory$loaded(true);
            return printer;
        }
    }

    public void read(CompoundTag tag) {
        saved = tag.copy();
        factories.clear();
    }

    public void initialize(SchematicannonBlockEntity cannon, ItemStack stack) {
        ServerLevel level = (ServerLevel) cannon.getLevel();
        if (saved != null) {
            schematic = ItemStack.parseOptional(level.registryAccess(), saved.getCompound("Schematic"));
            for (Tag value : saved.getList("Factories", Tag.TAG_COMPOUND)) {
                CompoundTag tag = (CompoundTag) value;
                FactoryBlueprint blueprint = FactoryBlueprint.read(level.getServer(), "cannon", tag.getCompound("Snapshot"));
                if (blueprint == null) continue;
                FactoryPrint factory = new FactoryPrint(blueprint);
                factory.cursor = tag.getInt("Cursor");
                for (Tag link : tag.getList("Entrances", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) link;
                    factory.entrances.put(BlockPos.of(entry.getLong("Pos")), BlockPos.of(entry.getLong("Cell")));
                }
                ListTag rooms = tag.getList("Rooms", Tag.TAG_COMPOUND);
                for (int i = 0; i < rooms.size() && i < factory.rooms.size(); i++) {
                    CompoundTag progress = rooms.getCompound(i);
                    RoomPrint room = factory.rooms.get(i);
                    room.id = progress.getInt("Id");
                    room.cursor = progress.getInt("Cursor");
                    room.wireCursor = progress.getInt("WireCursor");
                }
                factories.add(factory);
            }
            saved = null;
        }
        if (ItemStack.matches(schematic, stack)) return;
        factories.clear();
        schematic = stack.copy();
        SchematicLevel view = ((SchematicPrinterAccessor) cannon.printer).recursivefactory$reader();
        List<RecursiveFactoryBlockEntity> doors = new ArrayList<>();
        for (BlockEntity be : view.getBlockEntities()) if (be instanceof RecursiveFactoryBlockEntity door) doors.add(door);
        doors.sort(Comparator.comparingLong(door -> door.getBlockPos().asLong()));
        while (!doors.isEmpty()) {
            RecursiveFactoryBlockEntity first = doors.removeFirst();
            FactoryBlueprint snapshot = RecursiveFactoryBlock.blueprintBehind(level.getServer(), first);
            if (snapshot == null) continue;
            FactoryPrint factory = new FactoryPrint(snapshot);
            BlockPos sourceCell = first.roomCell() == null ? BlockPos.ZERO : first.roomCell();
            factory.entrances.put(first.getBlockPos(), BlockPos.ZERO);
            // Quill captures of one expanded factory share one snapshot, even if only some cells were selected.
            if (first.blueprintFile() == null && first.hasFactoryId()) {
                doors.removeIf(door -> {
                    if (door.blueprintFile() != null || door.getFactoryId() != first.getFactoryId()
                            || !java.util.Objects.equals(door.blueprintOrigin(), first.blueprintOrigin())) return false;
                    BlockPos cell = door.roomCell() == null ? BlockPos.ZERO : door.roomCell();
                    factory.entrances.put(door.getBlockPos(), cell.subtract(sourceCell));
                    return true;
                });
            }
            factories.add(factory);
        }
        cannon.setChanged();
    }

    public CompoundTag write(SchematicannonBlockEntity cannon) {
        if (saved != null) return saved.copy();
        CompoundTag result = new CompoundTag();
        result.put("Schematic", schematic.saveOptional(cannon.getLevel().registryAccess()));
        ListTag list = new ListTag();
        for (FactoryPrint factory : factories) {
            CompoundTag tag = new CompoundTag();
            tag.put("Snapshot", factory.blueprint.serialize(cannon.getLevel().registryAccess()));
            tag.putInt("Cursor", factory.cursor);
            ListTag entrances = new ListTag();
            factory.entrances.forEach((pos, cell) -> {
                CompoundTag link = new CompoundTag();
                link.putLong("Pos", pos.asLong());
                link.putLong("Cell", cell.asLong());
                entrances.add(link);
            });
            tag.put("Entrances", entrances);
            ListTag rooms = new ListTag();
            for (RoomPrint room : factory.rooms) {
                CompoundTag progress = new CompoundTag();
                progress.putInt("Id", room.id);
                progress.putInt("Cursor", room.cursor);
                progress.putInt("WireCursor", room.wireCursor);
                rooms.add(progress);
            }
            tag.put("Rooms", rooms);
            list.add(tag);
        }
        result.put("Factories", list);
        return result;
    }

    /** Added to the paid entrance projectile; vanilla projectile NBT persists this through a restart. */
    public CompoundTag entranceData(SchematicannonBlockEntity cannon, BlockPos target, CompoundTag original) {
        ServerLevel level = cannon.getLevel().getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (level == null) return original;
        RoomPrint room = null;
        BlockPos cell = BlockPos.ZERO;
        if (placingRoom != null) {
            FactoryBlueprint.Room child = placingRoom.room.nestedAt(target.subtract(placingRoom.origin));
            if (child == null) return original;
            room = placingFactory.rooms.get(child.index());
            cell = child.entranceCell(target.subtract(placingRoom.origin));
        } else {
            for (FactoryPrint factory : factories) {
                if (!factory.entrances.containsKey(target)) continue;
                room = factory.rooms.getFirst();
                cell = factory.entrances.get(target);
                break;
            }
        }
        if (room == null) {
            if (original == null) return null;
            CompoundTag plain = original.copy();
            plain.remove(RecursiveFactoryBlockEntity.BLUEPRINT_TAG);
            plain.remove(RecursiveFactoryBlockEntity.ORIGIN_TAG);
            plain.remove(RecursiveFactoryBlockEntity.CELL_TAG);
            plain.remove("FactoryId");
            return plain;
        }
        room.allocate(level);
        // A checklist reader made before allocation has a different origin.
        room.printer = null;
        CompoundTag nbt = original == null ? new CompoundTag() : original.copy();
        CompoundTag binding = new CompoundTag();
        binding.putInt("Id", room.id);
        binding.putLong("Cell", cell.asLong());
        nbt.put("FactoryCannonRoom", binding);
        cannon.setChanged();
        return nbt;
    }

    /** Returns true while room work owns the cannon's next shot. */
    public boolean tick(SchematicannonBlockEntity cannon) {
        if (!cannon.printer.isLoaded() || cannon.state == SchematicannonBlockEntity.State.STOPPED
                || !ItemStack.matches(schematic, cannon.inventory.getStackInSlot(0))) return false;
        FactoryPrint factory = factories.stream().filter(f -> f.rooms.getFirst().id > 0 && f.cursor < f.rooms.size())
                .findFirst().orElse(null);
        if (factory == null) return false;
        FactoryCannonAccess access = (FactoryCannonAccess) cannon;
        if (cannon.state == SchematicannonBlockEntity.State.PAUSED && cannon.missingItem == null
                && !cannon.positionNotLoaded && cannon.remainingFuel > 0) return true;
        if (access.recursivefactory$cooldown() > 0) {
            access.recursivefactory$cooldown(access.recursivefactory$cooldown() - 1);
            return true;
        }
        if (!cannon.hasCreativeCrate && cannon.remainingFuel <= 0) {
            pause(cannon, "noGunpowder");
            return true;
        }
        ServerLevel level = cannon.getLevel().getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (level == null) return true;
        int rootId = factory.rooms.getFirst().id;
        FactoryDimension.keepLoaded(rootId);
        boolean landed = factory.entrances.keySet().stream().anyMatch(pos -> cannon.getLevel().isLoaded(pos)
                && cannon.getLevel().getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity door
                && door.getFactoryId() == rootId);
        if (!landed) {
            cannon.positionNotLoaded = true;
            pause(cannon, "targetNotLoaded");
            return true;
        }
        cannon.positionNotLoaded = false;
        // Bound the scan so a mostly empty or already built room cannot stall a server tick.
        for (int skipped = 0; skipped < 256 && factory.cursor < factory.rooms.size(); skipped++) {
            RoomPrint room = factory.rooms.get(factory.cursor);
            if (room.id == 0) { factory.cursor++; continue; } // its entrance was skipped
            FactoryDimension.keepLoaded(room.id);
            SchematicPrinter printer = room.reader(level);
            if (room.cursor < room.entries.size()) {
                FactoryBlueprint.Entry entry = room.entries.get(room.cursor);
                BlockPos target = room.origin.offset(entry.pos());
                if (!level.isLoaded(target)) {
                    cannon.positionNotLoaded = true;
                    pause(cannon, "targetNotLoaded");
                    return true;
                }
                ((SchematicPrinterAccessor) printer).recursivefactory$position(entry.pos());
                ItemRequirement requirement = printer.getCurrentRequirement();
                if (requirement.isInvalid() || !printer.shouldPlaceCurrent(level,
                        (p, s, be, old, other, solid) -> shouldPlace(cannon, level, p, s, be, old, other, solid))) {
                    room.cursor++;
                    continue;
                }
                if (!pay(cannon, requirement)) {
                    if (cannon.skipMissing) { room.cursor++; continue; }
                    return true;
                }
                placingRoom = room;
                placingFactory = factory;
                try {
                    // Use Create's exact belt/multipart/safe-NBT placement, landing in the room this tick.
                    int firstShot = cannon.flyingBlocks.size();
                    ItemStack icon = requirement.isEmpty() ? ItemStack.EMPTY : requirement.getRequiredItems().getFirst().stack;
                    printer.handleCurrentTarget((p, s, be) -> access.recursivefactory$launch(p, icon, s, be), (p, e) -> {});
                    while (cannon.flyingBlocks.size() > firstShot) {
                        LaunchedItem shot = cannon.flyingBlocks.remove(firstShot);
                        shot.ticksRemaining = 0;
                        shot.update(level);
                    }
                } finally {
                    placingRoom = null;
                    placingFactory = null;
                }
                room.cursor++;
                fired(cannon);
                return true;
            }
            if (room.wireCursor < room.wires.size()) {
                CompoundTag step = room.wires.get(room.wireCursor);
                if (!FactoryWires.supportsPresent(level, room.origin, step)) {
                    room.wireCursor++;
                    continue;
                }
                ItemRequirement requirement = FactoryWires.requirements(step, level, room.origin);
                if (requirement.isEmpty()) { room.wireCursor++; continue; }
                if (!pay(cannon, requirement)) {
                    if (cannon.skipMissing) { room.wireCursor++; continue; }
                    return true;
                }
                FactoryWires.place(level, room.origin, step);
                room.wireCursor++;
                cannon.blocksPlaced++;
                cannon.playFiringSound();
                fired(cannon);
                return true;
            }
            factory.cursor++;
        }
        cannon.missingItem = null;
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        cannon.setChanged();
        cannon.sendUpdate = true;
        return true;
    }

    private static boolean shouldPlace(SchematicannonBlockEntity cannon, ServerLevel level, BlockPos pos,
            BlockState state, BlockEntity be, BlockState old, BlockState other, boolean solid) {
        if (((FactoryCannonAccess) cannon).recursivefactory$ignore(state, be)) return false;
        if (!cannon.replaceBlockEntities && (old.hasBlockEntity() || other != null && other.hasBlockEntity())) return false;
        boolean replaceable = !old.isRedstoneConductor(level, pos)
                && (other == null || !other.isRedstoneConductor(level, pos));
        return cannon.replaceMode == 3 || !state.isAir() && (cannon.replaceMode == 2
                || cannon.replaceMode == 1 && (solid || replaceable) || cannon.replaceMode == 0 && replaceable);
    }

    private static boolean pay(SchematicannonBlockEntity cannon, ItemRequirement requirement) {
        ItemStack missing = cannon.hasCreativeCrate ? ItemStack.EMPTY
                : FactorySchematicMaterials.pay(requirement, cannon.attachedInventories, cannon.getLevel(), cannon.getBlockPos());
        if (missing.isEmpty()) return true;
        if (cannon.skipMissing) {
            cannon.missingItem = null;
            cannon.statusMsg = "skipping";
            cannon.state = SchematicannonBlockEntity.State.RUNNING;
        } else {
            cannon.missingItem = missing;
            pause(cannon, "missingBlock");
        }
        return false;
    }

    private static void pause(SchematicannonBlockEntity cannon, String message) {
        cannon.state = SchematicannonBlockEntity.State.PAUSED;
        cannon.statusMsg = message;
        cannon.sendUpdate = true;
        cannon.setChanged();
    }

    private static void fired(SchematicannonBlockEntity cannon) {
        cannon.remainingFuel = cannon.hasCreativeCrate ? 0 : cannon.remainingFuel - 1;
        ((FactoryCannonAccess) cannon).recursivefactory$cooldown(cannon.config().schematicannonDelay.get());
        cannon.missingItem = null;
        cannon.state = SchematicannonBlockEntity.State.RUNNING;
        cannon.statusMsg = "placing";
        cannon.sendUpdate = true;
        cannon.setChanged();
    }

    public void checklist(SchematicannonBlockEntity cannon) {
        if (saved != null || cannon.getLevel() == null || cannon.getLevel().isClientSide) return;
        ServerLevel level = cannon.getLevel().getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (level == null) return;
        for (FactoryPrint factory : factories) for (int i = factory.cursor; i < factory.rooms.size(); i++) {
            RoomPrint room = factory.rooms.get(i);
            SchematicPrinter printer = room.reader(level);
            for (int b = room.cursor; b < room.entries.size(); b++) {
                FactoryBlueprint.Entry entry = room.entries.get(b);
                ((SchematicPrinterAccessor) printer).recursivefactory$position(entry.pos());
                ItemRequirement requirement = printer.getCurrentRequirement();
                BlockEntity be = ((SchematicPrinterAccessor) printer).recursivefactory$reader()
                        .getBlockEntity(room.origin.offset(entry.pos()));
                if (requirement.isInvalid() || entry.state().isAir()
                        || ((FactoryCannonAccess) cannon).recursivefactory$ignore(entry.state(), be)) continue;
                if (room.id != 0 && level.isLoaded(room.origin.offset(entry.pos())) && !printer.shouldPlaceCurrent(level,
                        (p, s, entity, old, other, solid) -> shouldPlace(cannon, level, p, s, entity, old, other, solid))) continue;
                if (!requirement.isEmpty()) cannon.checklist.require(requirement);
                cannon.blocksToPlace++;
            }
            for (int w = room.wireCursor; w < room.wires.size(); w++) {
                ItemRequirement requirement = room.id == 0 ? FactoryWires.printRequirement(room.wires.get(w))
                        : FactoryWires.requirements(room.wires.get(w), level, room.origin);
                if (requirement.isEmpty()) continue;
                cannon.checklist.require(requirement);
                cannon.blocksToPlace++;
            }
        }
    }
}
