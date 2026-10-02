package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.compat.FactorySchematicMaterials;
import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.item.MirrorFactoryItem;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryBlueprintCapture;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;

/**
 * The factory printer: it builds a copy of a factory room out of a blueprint, one block at a time,
 * out of the materials in the containers around it.
 *
 * <p>What it prints is a room of its own. The blueprint holds what stood inside a factory - its
 * machines, not its floor or its walls, which every room gets for free (see
 * {@link FactoryBlueprint}) - so the printer asks its neighbours for one block's worth of items, puts the
 * block down in the new room, and waits {@code printer.delay} ticks before it asks for the next one. The
 * room therefore fills up from the floor upwards at a pace a player can feed, which is the point of
 * printing it a block at a time rather than all at once: a chest of iron ingots here, a stack of shafts
 * there, and the print goes on.
 *
 * <p>A factory standing inside the room the blueprint was taken from is printed too: the printer builds
 * the room that entrance block leads into as a room of its own, wires the block into it, and then fills
 * that room from the same blueprint, in the same order and out of the same containers (see
 * {@link FactoryBlueprint#rooms()}). The rooms are printed one after another, each one only once the room
 * holding the entrance block that leads to it is standing.
 *
 * <p>Sugar is what it burns to place a block, at the same rate a Create schematicannon burns gunpowder:
 * one sugar is good for {@code printer.shotsPerSugar} blocks, and how far the sugar in it reaches is
 * written on the machine so picking it up in the middle of a print does not put the fire out. A creative
 * crate put next to it feeds it both - sugar and materials - without ever running out.
 *
 * <p>Nothing here needs a screen: right clicking the printer says where the print has got to and what it
 * is waiting for, a blueprint loaded into it starts a print, sugar tops the fuel up, and an empty hand
 * takes the finished copy out.
 */
public class FactoryPrinterBlockEntity extends BlockEntity {
    /** How often the containers around the printer are looked at again, in ticks. */
    public static final int NEIGHBOUR_CHECKING = 100;
    /**
     * How many blocks of a blueprint one tick may walk past without placing one. A print skips the blocks
     * that are already standing there - which is what picking a half printed room up and carrying on
     * looks like - and this is what keeps that walking from never coming back to the server.
     */
    private static final int SKIP_BUDGET = 1000;

    private static final String BLUEPRINT_TAG = "Blueprint";
    private static final String ROOM_TAG = "Room";
    private static final String ROOM_INDEX_TAG = "RoomIndex";
    private static final String NESTED_ROOMS_TAG = "NestedRooms";
    private static final String PROGRESS_TAG = "Progress";
    private static final String COOLDOWN_TAG = "Cooldown";
    private static final String FUEL_TAG = "Fuel";
    private static final String MISSING_TAG = "Missing";
    private static final String OWNER_TAG = "Owner";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The blueprint file being printed, or null while the printer is idle or only holding a copy. */
    private String blueprintName;
    /** The room the room that was read out is going into: the one on top of a blueprint's room list. */
    private int roomId = -1;
    /** Which of the blueprint's rooms the print is on, counted in {@link FactoryBlueprint#rooms()}. */
    private int roomIndex;
    /**
     * The room built for each of the factories nested in the blueprint, one per room of
     * {@link FactoryBlueprint#rooms()} after the first, or -1 for one that has not been built yet - which
     * is every room the print has not reached.
     */
    private int[] nestedRoomIds = new int[0];
    /** How far through the room the print is on: the index of the block it places next. */
    private int progress;
    /** Ticks left before the next block may be placed. */
    private int cooldown;
    /** Blocks this much sugar still pays for. */
    private int fuel;
    /** What the print is waiting for, so a player looking at the printer can be told. */
    private ItemStack missing = ItemStack.EMPTY;
    /** Whoever put the printer down, who then owns the room it prints. */
    @Nullable
    private UUID owner;

    /** The blueprint itself, read from its file the first time it is needed. */
    @Nullable
    private FactoryBlueprint blueprint;
    /** True when the blueprint's file could not be read, so the print does not keep trying. */
    private boolean blueprintUnreadable;
    private List<IItemHandler> inventories = List.of();
    private int neighbourCheck;
    private boolean creativeCrate;

    public FactoryPrinterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY_PRINTER.get(), pos, state);
    }

    /** Everything a print is made of, written on the machine so it can be picked up and put back down. */
    public void readFromItem(ItemStack stack) {
        // Fuel also belongs to an idle printer, before loading a blueprint or after finishing one.
        // A schematic may have loaded the block entity already and pass an empty placement stack.
        fuel = Math.max(0, stack.getOrDefault(ModDataComponents.FUEL.get(), fuel));
        String name = stack.get(ModDataComponents.BLUEPRINT.get());
        if (name == null) {
            setChanged();
            return;
        }
        blueprintName = name;
        roomId = stack.getOrDefault(ModDataComponents.ROOM.get(), -1);
        progress = Math.max(0, stack.getOrDefault(ModDataComponents.PROGRESS.get(), 0));
        CompoundTag nested = stack.get(ModDataComponents.NESTED_ROOMS.get());
        if (nested != null) {
            roomIndex = nested.getInt(ROOM_INDEX_TAG);
            nestedRoomIds = nested.getIntArray(NESTED_ROOMS_TAG);
        } else {
            roomIndex = 0;
            nestedRoomIds = new int[0];
        }
        blueprint = null;
        blueprintUnreadable = false;
        setChanged();
    }

    /** Writes the print's state onto the item this machine drops, so a print survives being moved. */
    public void writeToItem(ItemStack stack) {
        if (fuel > 0) {
            stack.set(ModDataComponents.FUEL.get(), fuel);
        } else {
            stack.remove(ModDataComponents.FUEL.get());
        }
        if (blueprintName == null) {
            return;
        }
        stack.set(ModDataComponents.BLUEPRINT.get(), blueprintName);
        stack.set(ModDataComponents.ROOM.get(), roomId);
        stack.set(ModDataComponents.PROGRESS.get(), progress);
        CompoundTag nested = new CompoundTag();
        nested.putInt(ROOM_INDEX_TAG, roomIndex);
        nested.putIntArray(NESTED_ROOMS_TAG, nestedRoomIds);
        stack.set(ModDataComponents.NESTED_ROOMS.get(), nested);
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public void tick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (blueprintName == null) {
            return;
        }
        // A room being printed has no entrance block yet, so nothing else would keep its chunk open: the
        // print asks for it itself, and stops asking the moment it is done with it (see the room id below).
        FactoryDimension.keepLoaded(roomId);
        if (neighbourCheck-- <= 0) {
            neighbourCheck = NEIGHBOUR_CHECKING;
            findInventories();
        }
        if (blueprintUnreadable) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            setChanged();
            return;
        }

        MinecraftServer server = level.getServer();
        ServerLevel roomLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        if (server == null || roomLevel == null) {
            return;
        }
        if (blueprint == null && !loadBlueprint(server)) {
            return;
        }
        FactoryData data = FactoryData.get(server);
        if (ensureRoom(server, roomLevel, data) == null) {
            return;
        }
        int budget = SKIP_BUDGET;
        while (budget-- > 0) {
            FactoryBlueprint.Room room = currentRoom();
            if (room == null) {
                finish(server, data);
                return;
            }
            FactoryData.FactoryRecord.Cell cell = roomCell(data, room);
            if (cell == null) {
                // Nothing to print this room into: a room whose factory has gone. The rest of the print
                // carries on with the next one rather than stopping on it.
                nextRoom();
                continue;
            }
            if (progress >= room.size()) {
                // Connections belong to the room rather than a block entity. Finish them only after
                // their support blocks exist, and pay for the whole remaining wiring before placing it.
                if (RecursiveFactory.powerAvailable() && !room.wires().isEmpty()) {
                    var wires = room.wires();
                    var origin = FactoryBlueprint.origin(cell);
                    var required = FactoryWires.requirements(wires, roomLevel, origin);
                    ItemStack missingWire = creativeCrate ? ItemStack.EMPTY
                            : FactorySchematicMaterials.pay(required, inventories, level, worldPosition);
                    if (!missingWire.isEmpty()) {
                        pause(missingWire);
                        return;
                    }
                    FactoryWires.place(roomLevel, origin, wires);
                    missing = ItemStack.EMPTY;
                }
                nextRoom();
                continue;
            }
            FactoryBlueprint.Entry entry = room.blocks().get(progress);
            // A block of a nested factory's room only goes in once the room is standing, and the room is
            // built when the entrance block leading into it comes up; a print waits for it here rather
            // than printing a room's blocks before there is anywhere for them to go.
            if (room.nestedAt(entry.pos()) != null && !ensureNestedRoom(roomLevel, data, room, entry)) {
                return;
            }
            BlockPos target = FactoryBlueprint.roomPos(cell, entry.pos());
            if (roomLevel.getBlockState(target).equals(entry.state())) {
                // Already the block the blueprint asks for - a print that is being carried on rather than
                // started - so there is nothing to pay and nothing to wait for.
                progress++;
                continue;
            }
            ItemRequirement requirement = requirementOf(roomLevel, entry);
            if (requirement.isInvalid()) {
                LOGGER.debug("Factory printer at {}: skipping {} at {}, it has no item form",
                        worldPosition, entry.state(), entry.pos());
                progress++;
                continue;
            }
            // What the block is made of is asked for before the sugar is burnt, so a printer that is
            // waiting for materials never burns fuel for a block it does not place.
            ItemStack missingItem = firstMissing(requirement);
            if (missingItem != null) {
                pause(missingItem);
                return;
            }
            if (!creativeCrate && fuel <= 0 && !refillFuel()) {
                pause(new ItemStack(Items.SUGAR));
                return;
            }
            take(requirement);
            placeEntry(roomLevel, data, cell, room, entry);
            missing = ItemStack.EMPTY;
            progress++;
            if (!creativeCrate) {
                fuel--;
            }
            cooldown = FactoryConfig.printerDelay();
            setChanged();
            return;
        }
        setChanged();
    }

    /** The room of the blueprint the print is on, or null when every one of them has been printed. */
    @Nullable
    private FactoryBlueprint.Room currentRoom() {
        if (blueprint == null || roomIndex < 0 || roomIndex >= blueprint.rooms().size()) {
            return null;
        }
        return blueprint.rooms().get(roomIndex);
    }

    /** Moves on to the next room of the blueprint, which every room's printing starts at the floor of. */
    private void nextRoom() {
        roomIndex++;
        progress = 0;
        setChanged();
    }

    /** The room cell the current room is printed into, or null while there is no room standing for it. */
    @Nullable
    private FactoryData.FactoryRecord.Cell roomCell(FactoryData data, FactoryBlueprint.Room room) {
        int id = roomIdFor(room);
        FactoryData.FactoryRecord record = id > 0 ? data.factory(id) : null;
        return record == null ? null : record.anchorCell();
    }

    /**
     * The factory one room of the blueprint is printed into: the room the printer built for the room that
     * was read out, or the room built for one of the factories nested in it - which is built when the
     * entrance block leading into it is put down, so a room the print has not reached yet has none.
     */
    private int roomIdFor(FactoryBlueprint.Room room) {
        int index = room.index();
        if (index <= 0) {
            return roomId;
        }
        return index - 1 < nestedRoomIds.length ? nestedRoomIds[index - 1] : -1;
    }

    /**
     * Puts one block of the blueprint down. A block that is the entrance block of a factory nested in the
     * blueprint is wired into the room that was built for it, which is what the next room of the blueprint
     * is printed into.
     */
    private void placeEntry(ServerLevel roomLevel, FactoryData data, FactoryData.FactoryRecord.Cell cell,
                            FactoryBlueprint.Room room, FactoryBlueprint.Entry entry) {
        FactoryBlueprint.Room nested = room.nestedAt(entry.pos());
        if (nested == null) {
            FactoryBlueprint.placeEntry(roomLevel, cell, entry);
            return;
        }
        int nestedRoomId = nestedRoomIds[nested.index() - 1];
        FactoryBlueprint.placeEntry(roomLevel, cell, entry, nestedRoomId, nested.colorIndex());
        LOGGER.info("Factory printer at {}: the entrance block at {} leads into factory #{}",
                worldPosition, entry.pos(), nestedRoomId);
    }

    /**
     * The room the entrance block at this block of the blueprint leads into, standing by the time the
     * block is put down: the room the print built for it, or - where the block is already standing, put
     * there by a print that was picked up and carried on, or by the player - the room that block already
     * leads into, or a room built for it here and now and wired in.
     *
     * <p>Answers whether the block may be printed now: a print waits for the room its entrance block
     * leads into, because the room has to be standing - floor, shell and all - before there is anywhere
     * for that room's own blocks to go.
     */
    private boolean ensureNestedRoom(ServerLevel roomLevel, FactoryData data, FactoryBlueprint.Room room,
                                     FactoryBlueprint.Entry entry) {
        int slot = room.nestedAt(entry.pos()).index() - 1;
        if (nestedRoomIds[slot] > 0) {
            return true;
        }
        FactoryData.FactoryRecord.Cell cell = roomCell(data, room);
        if (cell == null) {
            return false;
        }
        BlockPos target = FactoryBlueprint.roomPos(cell, entry.pos());
        int existing = FactoryDimension.entranceRoomOf(roomLevel, data, target);
        if (existing > 0) {
            // The block is already standing and already leads into a room - a print that is being carried
            // on - so that room is the one this print fills, rather than a fresh room beside it.
            nestedRoomIds[slot] = existing;
            setChanged();
            return true;
        }
        if (!roomLevel.getBlockState(target).equals(entry.state())) {
            // The block is not there yet: the room is built for it now, and it is wired into it when it
            // goes down.
            nestedRoomIds[slot] = FactoryDimension.newRoom(roomLevel, data, owner,
                    room.nestedAt(entry.pos()).colorIndex());
            FactoryDimension.sizeRoom(roomLevel, data, nestedRoomIds[slot],
                    room.nestedAt(entry.pos()).cells());
            setChanged();
            return true;
        }
        // A block left standing with no room behind it: the print cannot build the room on top of it -
        // the room is somewhere else entirely - so it is built and the block is wired into it.
        nestedRoomIds[slot] = FactoryDimension.newRoom(roomLevel, data, owner,
                room.nestedAt(entry.pos()).colorIndex());
        FactoryDimension.sizeRoom(roomLevel, data, nestedRoomIds[slot],
                room.nestedAt(entry.pos()).cells());
        FactoryDimension.linkEntrance(roomLevel, target, nestedRoomIds[slot],
                room.nestedAt(entry.pos()).colorIndex());
        LOGGER.info("Factory printer at {}: the entrance block at {} led nowhere; it leads into factory #{}",
                worldPosition, target, nestedRoomIds[slot]);
        setChanged();
        return true;
    }

    /** How many blocks of the blueprint are standing already, every room counted, for the player to read. */
    private int printed() {
        if (blueprint == null) {
            return 0;
        }
        int done = 0;
        for (FactoryBlueprint.Room room : blueprint.rooms()) {
            int index = room.index();
            if (index < roomIndex) {
                done += room.size();
            } else if (index == roomIndex) {
                done += Math.min(progress, room.size());
            }
        }
        return done;
    }

    /** What one block of a blueprint asks the containers around the printer for. */
    private static ItemRequirement requirementOf(ServerLevel level, FactoryBlueprint.Entry entry) {
        return ItemRequirement.of(entry.state(),
                FactoryBlueprint.newBlockEntity(level.registryAccess(), entry));
    }

    private boolean loadBlueprint(MinecraftServer server) {
        FactoryBlueprint read = FactoryBlueprint.read(server, blueprintName);
        if (read == null || read.isEmpty()) {
            // The name is kept rather than dropped: the print never starts, but a player looking at the
            // machine is told which blueprint it is holding and cannot read, rather than finding it idle.
            LOGGER.warn("Factory printer at {}: the blueprint {} could not be read", worldPosition,
                    blueprintName);
            blueprintUnreadable = true;
            return false;
        }
        adopt(read);
        return true;
    }

    /**
     * Takes on a blueprint that has been read and checked: there is a slot per factory nested in it, which
     * are the rooms after the one that was read out, and a print that is being carried on keeps the rooms
     * it has already built.
     */
    private void adopt(FactoryBlueprint read) {
        blueprint = read;
        if (nestedRoomIds.length < read.rooms().size() - 1) {
            int[] grown = new int[read.rooms().size() - 1];
            Arrays.fill(grown, -1);
            System.arraycopy(nestedRoomIds, 0, grown, 0, nestedRoomIds.length);
            nestedRoomIds = grown;
        }
        LOGGER.info("Factory printer at {}: took on the blueprint {} with {} rooms in it",
                worldPosition, blueprintName, read.rooms().size());
    }

    /**
     * The room the room that was read out goes into: the one it was already printing into, or a fresh one
     * of its own. A fresh room is built by the first look at it, which lays its floor and walls and loads
     * the chunk it stands in, so the print has somewhere to put its blocks. The rooms of the factories
     * nested in the blueprint are built as the entrance blocks leading to them are put down.
     */
    @Nullable
    private FactoryData.FactoryRecord ensureRoom(MinecraftServer server, ServerLevel roomLevel, FactoryData data) {
        FactoryData.FactoryRecord record = roomId > 0 ? data.factory(roomId) : null;
        if (record == null) {
            roomId = FactoryDimension.newRoom(roomLevel, data, owner,
                    blueprint == null ? 0 : blueprint.colorIndex());
            LOGGER.info("Factory printer at {}: printing factory #{}", worldPosition, roomId);
            record = data.factory(roomId);
        } else if (record.cells().isEmpty()) {
            data.bindRoom(record.id());
            record = data.factory(record.id());
            if (record != null) {
                FactoryDimension.prepare(roomLevel, record);
            }
            LOGGER.info("Factory printer at {}: printing factory #{}", worldPosition,
                    record == null ? -1 : record.id());
        }
        if (record == null || blueprint == null) {
            return record;
        }
        // A blueprint of a factory that had grown past one cell asks for a room as wide as the room it
        // was read from: the further cells are built beside the first, and the blocks of each land in it.
        FactoryDimension.sizeRoom(roomLevel, data, record.id(), blueprint.root().cells());
        FactoryData.FactoryRecord sized = data.factory(record.id());
        return sized == null ? record : sized;
    }

    /** The print is waiting for something: remember what, so the next look at the printer can say. */
    private void pause(ItemStack what) {
        if (!ItemStack.isSameItemSameComponents(missing, what)) {
            missing = what;
            LOGGER.info("Factory printer at {}: waiting for {}", worldPosition, what.getHoverName().getString());
        }
        setChanged();
    }

    /**
     * The print is done: the room stands, so the machine turns itself into the copy of the factory it has
     * been building - an item that hands the room out to a player who puts its entrance down.
     */
    private void finish(MinecraftServer server, FactoryData data) {
        FactoryData.FactoryRecord room = roomId > 0 ? data.factory(roomId) : null;
        String name = blueprintName;
        int printedRoom = roomId;
        ItemStack product = MirrorFactoryItem.create(roomId, name,
                room == null ? 0 : room.colorIndex());
        blueprintName = null;
        blueprint = null;
        // Where this print was building is let go with the rest of it: a room is printed once, and the
        // machine is left idle rather than still holding the room it has just finished - the next
        // blueprint loaded into it must build a room of its own rather than printing on top of this one.
        roomId = -1;
        roomIndex = 0;
        nestedRoomIds = new int[0];
        progress = 0;
        cooldown = 0;
        missing = ItemStack.EMPTY;
        if (!eject(product)) {
            // Nothing beside the machine would take it, so it goes on the ground where the machine stands
            // rather than sitting in there for ever waiting for somebody to look.
            if (level != null) {
                Block.popResource(level, worldPosition, product);
            }
        }
        setChanged();
        LOGGER.info("Factory printer at {}: finished printing factory #{}", worldPosition, printedRoom);
        if (level != null) {
            level.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6F, 1.4F);
        }
    }

    /** Tries to hand a finished copy to a container beside the printer. */
    private boolean eject(ItemStack product) {
        for (IItemHandler handler : inventories) {
            ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItem(handler, product, false);
            if (left.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void findInventories() {
        creativeCrate = false;
        List<IItemHandler> found = new ArrayList<>(Direction.values().length);
        if (!(level instanceof ServerLevel serverLevel)) {
            inventories = List.of();
            return;
        }
        for (Direction side : Direction.values()) {
            BlockPos neighbour = worldPosition.relative(side);
            if (!serverLevel.isLoaded(neighbour)) {
                continue;
            }
            if (AllBlocks.CREATIVE_CRATE.has(serverLevel.getBlockState(neighbour))) {
                creativeCrate = true;
            }
            IItemHandler handler = serverLevel.getCapability(
                    Capabilities.ItemHandler.BLOCK, neighbour, side.getOpposite());
            if (handler != null) {
                found.add(handler);
            }
        }
        inventories = found;
    }

    /** The first thing a block asks for that is not in the containers around the printer, or null. */
    @Nullable
    private ItemStack firstMissing(ItemRequirement requirement) {
        if (creativeCrate) {
            return null;
        }
        for (ItemRequirement.StackRequirement required : requirement.getRequiredItems()) {
            if (countMatching(required) < required.stack.getCount()) {
                return required.stack;
            }
        }
        return null;
    }

    private int countMatching(ItemRequirement.StackRequirement required) {
        int found = 0;
        for (IItemHandler handler : inventories) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                // Visible contents may be locked or exposed on an insert-only side. Only count
                // what the same extraction used by takeMatching can actually take.
                ItemStack stack = handler.extractItem(slot, required.stack.getCount() - found, true);
                if (required.matches(stack)) {
                    found += stack.getCount();
                    if (found >= required.stack.getCount()) {
                        return found;
                    }
                }
            }
        }
        return found;
    }

    private void take(ItemRequirement requirement) {
        if (creativeCrate) {
            return;
        }
        for (ItemRequirement.StackRequirement required : requirement.getRequiredItems()) {
            takeMatching(required, required.stack.getCount());
        }
    }

    private void takeMatching(ItemRequirement.StackRequirement required, int amount) {
        int left = amount;
        for (IItemHandler handler : inventories) {
            for (int slot = 0; slot < handler.getSlots() && left > 0; slot++) {
                if (!required.matches(handler.getStackInSlot(slot))) {
                    continue;
                }
                left -= handler.extractItem(slot, left, false).getCount();
            }
            if (left <= 0) {
                return;
            }
        }
    }

    /** Burns one sugar from the containers around the printer, if there is one to burn. */
    private boolean refillFuel() {
        if (creativeCrate) {
            return true;
        }
        for (IItemHandler handler : inventories) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).is(Items.SUGAR)) {
                    continue;
                }
                if (handler.extractItem(slot, 1, false).isEmpty()) {
                    continue;
                }
                fuel = Math.min(fuel + FactoryConfig.printerShotsPerSugar(), FactoryConfig.printerShotsPerSugar());
                setChanged();
                return true;
            }
        }
        return false;
    }

    /** Puts sugar from a player's hand into the printer. Answers whether any went in. */
    public boolean addFuel(ItemStack held, boolean consume) {
        if (!held.is(Items.SUGAR)) {
            return false;
        }
        fuel += FactoryConfig.printerShotsPerSugar();
        if (consume) {
            held.shrink(1);
        }
        setChanged();
        return true;
    }

    /**
     * Whether the printer has anything to do with what a player is holding. A blueprint and sugar are the
     * two things it answers to; anything else is none of its business, so a click with it goes on to
     * whoever else would have taken it.
     */
    public boolean accepts(ItemStack held) {
        return held.is(Items.SUGAR) || held.is(AllItems.SCHEMATIC.get());
    }

    /**
     * A player right clicking the printer with something in hand. A blueprint starts a print and sugar is
     * burnt for it; anything else never reaches here (see {@link #accepts}).
     *
     * @return true when the click was used up
     */
    public boolean useItem(Player player, ItemStack held) {
        if (held.is(Items.SUGAR)) {
            addFuel(held, !player.isCreative());
            player.displayClientMessage(Component.translatable(
                    "message.recursivefactory.printer.fuel", fuel), true);
            return true;
        }
        if (!held.is(AllItems.SCHEMATIC.get())) {
            return false;
        }
        if (blueprintName != null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.busy"), true);
            return true;
        }
        String name = FactoryBlueprintCapture.nameOf(held);
        if (name == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.not_a_blueprint"), true);
            return true;
        }
        if (!take(name)) {
            // A blueprint with no factory read into it - a plain Create schematic of something else - has
            // no room for the printer to build, so it is handed back rather than burnt, along with a word
            // on where a blueprint of a factory comes from.
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.no_factory"), true);
            player.displayClientMessage(Component.translatable("message.recursivefactory.blueprint.blank"), false);
            return true;
        }
        if (!player.isCreative()) {
            held.shrink(1);
        }
        player.displayClientMessage(Component.translatable("message.recursivefactory.printer.loaded"), true);
        return true;
    }

    /**
     * Takes on a blueprint by the name of its file: the print of it starts here, and it is what a player
     * right clicking the machine with a blueprint does (see {@link #useItem}).
     *
     * <p>A blueprint always starts a print of its own, so the room the machine was last building in is let
     * go here as well as at the end of a print: a printer that carries a stale room id - one written by a
     * version that left it behind, or by a print that was picked up mid-way - must not print its next
     * blueprint on top of the rooms the last one left standing.
     *
     * @return false when the file cannot be read as a blueprint of a factory, which leaves the machine as
     *     it was
     */
    public boolean take(String name) {
        if (level == null) {
            return false;
        }
        FactoryBlueprint read = FactoryBlueprint.read(level.getServer(), name);
        if (read == null || read.isEmpty()) {
            LOGGER.warn("Factory printer at {}: the blueprint {} holds no factory to print", worldPosition, name);
            return false;
        }
        blueprintName = name;
        blueprintUnreadable = false;
        roomId = -1;
        roomIndex = 0;
        nestedRoomIds = new int[0];
        progress = 0;
        cooldown = 0;
        missing = ItemStack.EMPTY;
        adopt(read);
        setChanged();
        return true;
    }

    /** A player right clicking the printer with an empty hand: hear how the print is getting on. */
    public boolean useEmptyHand(Player player) {
        if (blueprintName == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.idle"), true);
            return true;
        }
        if (blueprintUnreadable) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.unreadable",
                    blueprintName), false);
            return true;
        }
        int total = blueprint == null ? 0 : blueprint.size();
        player.displayClientMessage(Component.translatable("message.recursivefactory.printer.working",
                printed(), total), false);
        if (blueprint != null && blueprint.rooms().size() > 1) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.rooms",
                    Math.min(roomIndex + 1, blueprint.rooms().size()), blueprint.rooms().size()), false);
        }
        player.displayClientMessage(Component.translatable("message.recursivefactory.printer.fuel", fuel), false);
        if (!missing.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.waiting",
                    missing.getHoverName()), false);
        }
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (blueprintName != null) {
            tag.putString(BLUEPRINT_TAG, blueprintName);
        }
        tag.putInt(ROOM_TAG, roomId);
        tag.putInt(ROOM_INDEX_TAG, roomIndex);
        tag.putIntArray(NESTED_ROOMS_TAG, nestedRoomIds);
        tag.putInt(PROGRESS_TAG, progress);
        tag.putInt(COOLDOWN_TAG, cooldown);
        tag.putInt(FUEL_TAG, fuel);
        if (!missing.isEmpty()) {
            tag.put(MISSING_TAG, missing.saveOptional(registries));
        }
        if (owner != null) {
            tag.putUUID(OWNER_TAG, owner);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        blueprintName = tag.contains(BLUEPRINT_TAG) ? tag.getString(BLUEPRINT_TAG) : null;
        // A room id only ever belongs to a print, so one left behind by a finished print - which is what
        // versions before this one wrote - is dropped rather than carried into the next blueprint, where it
        // would have the machine print into the room it had already finished.
        roomId = blueprintName == null ? -1 : tag.getInt(ROOM_TAG);
        roomIndex = tag.getInt(ROOM_INDEX_TAG);
        nestedRoomIds = tag.getIntArray(NESTED_ROOMS_TAG);
        progress = tag.getInt(PROGRESS_TAG);
        cooldown = tag.getInt(COOLDOWN_TAG);
        fuel = tag.getInt(FUEL_TAG);
        missing = tag.contains(MISSING_TAG) ? ItemStack.parseOptional(registries, tag.getCompound(MISSING_TAG))
                : ItemStack.EMPTY;
        owner = tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
    }
}
