package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.item.MirrorFactoryItem;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.ArrayList;
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
    private static final String PROGRESS_TAG = "Progress";
    private static final String COOLDOWN_TAG = "Cooldown";
    private static final String FUEL_TAG = "Fuel";
    private static final String MISSING_TAG = "Missing";
    private static final String OWNER_TAG = "Owner";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The blueprint file being printed, or null while the printer is idle or only holding a copy. */
    private String blueprintName;
    /** The room the print is going into. */
    private int roomId = -1;
    /** How far through the blueprint the print is: the index of the block it places next. */
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
        String name = stack.get(ModDataComponents.BLUEPRINT.get());
        if (name == null) {
            return;
        }
        blueprintName = name;
        roomId = stack.getOrDefault(ModDataComponents.ROOM.get(), -1);
        progress = Math.max(0, stack.getOrDefault(ModDataComponents.PROGRESS.get(), 0));
        fuel = Math.max(0, stack.getOrDefault(ModDataComponents.FUEL.get(), 0));
        blueprint = null;
        blueprintUnreadable = false;
        setChanged();
    }

    /** Writes the print's state onto the item this machine drops, so a print survives being moved. */
    public void writeToItem(ItemStack stack) {
        if (blueprintName == null) {
            return;
        }
        stack.set(ModDataComponents.BLUEPRINT.get(), blueprintName);
        stack.set(ModDataComponents.ROOM.get(), roomId);
        stack.set(ModDataComponents.PROGRESS.get(), progress);
        stack.set(ModDataComponents.FUEL.get(), fuel);
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
        FactoryData.FactoryRecord record = ensureRoom(server, roomLevel, data);
        if (record == null || blueprint == null) {
            return;
        }
        FactoryData.FactoryRecord.Cell cell = record.anchorCell();
        if (cell == null) {
            return;
        }
        BlockPos origin = FactoryBlueprint.origin(cell);
        int budget = SKIP_BUDGET;
        while (budget-- > 0) {
            if (progress >= blueprint.size()) {
                finish(server, data);
                return;
            }
            FactoryBlueprint.Entry entry = blueprint.blocks().get(progress);
            BlockPos target = origin.offset(entry.pos());
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
            FactoryBlueprint.placeEntry(roomLevel, cell, entry);
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

    /** What one block of a blueprint asks the containers around the printer for. */
    private static ItemRequirement requirementOf(ServerLevel level, FactoryBlueprint.Entry entry) {
        return ItemRequirement.of(entry.state(), FactoryBlueprint.newBlockEntity(level, entry));
    }

    private boolean loadBlueprint(MinecraftServer server) {
        FactoryBlueprint read = FactoryBlueprint.read(server, blueprintName);
        if (read == null || read.size() == 0) {
            // The name is kept rather than dropped: the print never starts, but a player looking at the
            // machine is told which blueprint it is holding and cannot read, rather than finding it idle.
            LOGGER.warn("Factory printer at {}: the blueprint {} could not be read", worldPosition,
                    blueprintName);
            blueprintUnreadable = true;
            return false;
        }
        blueprint = read;
        return true;
    }

    /**
     * The room the print goes into: the one it was already printing into, or a fresh one of its own. A
     * fresh room is built by the first look at it, which lays its floor and walls and loads the chunk it
     * stands in, so the print has somewhere to put its blocks.
     */
    @Nullable
    private FactoryData.FactoryRecord ensureRoom(MinecraftServer server, ServerLevel roomLevel, FactoryData data) {
        FactoryData.FactoryRecord record = roomId > 0 ? data.factory(roomId) : null;
        if (record != null && !record.cells().isEmpty()) {
            return record;
        }
        if (record == null) {
            record = data.create(owner, blueprint == null ? 0 : blueprint.colorIndex());
            roomId = record.id();
        }
        data.bindRoom(record.id());
        FactoryData.FactoryRecord room = data.factory(record.id());
        if (room == null) {
            return null;
        }
        FactoryDimension.prepare(roomLevel, room);
        LOGGER.info("Factory printer at {}: printing factory #{}", worldPosition, room.id());
        return room;
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
        ItemStack product = MirrorFactoryItem.create(roomId, name,
                room == null ? 0 : room.colorIndex());
        blueprintName = null;
        blueprint = null;
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
        LOGGER.info("Factory printer at {}: finished printing factory #{}", worldPosition, roomId);
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
                ItemStack stack = handler.getStackInSlot(slot);
                if (required.matches(stack)) {
                    found += stack.getCount();
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
        return held.is(Items.SUGAR) || held.is(ModBlocks.FACTORY_BLUEPRINT_ITEM.get());
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
        if (!held.is(ModBlocks.FACTORY_BLUEPRINT_ITEM.get())) {
            return false;
        }
        if (blueprintName != null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.busy"), true);
            return true;
        }
        String name = held.get(ModDataComponents.BLUEPRINT.get());
        if (name == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.printer.not_a_blueprint"), true);
            return true;
        }
        blueprintName = name;
        blueprint = null;
        blueprintUnreadable = false;
        progress = 0;
        cooldown = 0;
        missing = ItemStack.EMPTY;
        if (!player.isCreative()) {
            held.shrink(1);
        }
        setChanged();
        player.displayClientMessage(Component.translatable("message.recursivefactory.printer.loaded"), true);
        LOGGER.info("Factory printer at {}: took on the blueprint {}", worldPosition, name);
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
                progress, total), false);
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
        roomId = tag.getInt(ROOM_TAG);
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
