package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * A copy of what stands inside one factory room, written to disk as a blueprint file.
 *
 * <p>The file is a structure file in the vanilla shape - a {@code size}, a {@code palette} and a list of
 * {@code blocks} carrying a palette index and a block entity tag - which is the same thing a Create
 * schematic is, so the file can be read by anything that reads blueprints. The blocks are written in the
 * order they are printed back: floor first, then upwards, one row at a time, which is what lets a printer
 * hand a player the materials for the part it is about to build rather than for the whole room.
 *
 * <p>What a blueprint holds is the room's <em>contents</em>: the free space from the floor up to the
 * ceiling, without the checkerboard floor itself and without the barrier shell. Those are what
 * {@link FactoryDimension#prepare} builds for every room, so a copy does not have to carry them.
 *
 * <p>A factory standing inside the room is a room of its own, and the file carries it the same way: the
 * entrance block that leads there travels as the block it is, and the room behind it is written beside it
 * under {@link #ROOMS_TAG}, joined to the room that holds the block. A copy therefore builds a factory of
 * its own for every factory the original had inside it, rather than an entrance block pointing back at the
 * one that was copied. A file written before this carried one room and reads back as one room.
 *
 * <p>The name of the file is what a blueprint item carries. The file itself lives in the world's own
 * folder, {@code recursivefactory/blueprints}, so a room full of machinery never has to fit inside an
 * item's data.
 */
public final class FactoryBlueprint {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Where the files live, relative to the world folder. */
    public static final String FOLDER = "recursivefactory/blueprints";
    /**
     * How many factories may stand inside one another before a copy gives up on following them. A copy
     * builds a room of its own for every factory in the stack, so the figure is what keeps a factory
     * nested a hundred deep from turning one click into a hundred rooms.
     */
    public static final int MAX_NESTING_DEPTH = 8;
    /** The keys of the file, in the shape a vanilla structure uses. */
    private static final String SIZE_TAG = "size";
    private static final String PALETTE_TAG = "palette";
    private static final String BLOCKS_TAG = "blocks";
    private static final String ENTITIES_TAG = "entities";
    private static final String META_TAG = "RecursiveFactory";
    private static final String NAME_TAG = "Name";
    private static final String COLOR_TAG = "Color";
    private static final String SOURCE_TAG = "Source";
    /**
     * Our own addition to the structure shape: the rooms standing inside the room the file carries. Each
     * one is a structure of its own - a {@code size}, a {@code palette} and {@code blocks} - plus the room
     * it hangs off ({@link #PARENT_TAG}, counted in {@link #rooms()} order) and the entrance block inside
     * that room which leads to it ({@link #POS_TAG}).
     */
    private static final String ROOMS_TAG = "Rooms";
    private static final String PARENT_TAG = "Parent";
    private static final String POS_TAG = "Pos";
    private static final String SUFFIX = ".nbt";

    /** One block of the room, at its offset from the room's own floor layer. */
    public record Entry(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
        public Entry {
            pos = pos.immutable();
            nbt = nbt == null ? null : nbt.copy();
        }
    }

    /**
     * One room of a blueprint: what stands in it, and the rooms the entrance blocks standing in it lead to.
     *
     * <p>A factory inside a factory is a room of its own, standing elsewhere in the factory dimension; the
     * only thing joining the two is the entrance block that leads from one into the other. A blueprint
     * carries the two the same way: this room's blocks, and - by the offset of the entrance block that
     * leads there - the room on the other side of it. A copy builds a room of its own for every one of
     * them, so a factory copied out of a room comes with working factories inside it rather than with
     * entrance blocks pointing back at the ones that were copied.
     */
    public static final class Room {
        private final List<Entry> blocks;
        /** The rooms the entrance blocks standing in this room lead to, by the offset of the block. */
        private final Map<BlockPos, Room> nested = new LinkedHashMap<>();
        /** The colour kind a copy of this room is painted, see {@link FactoryColors#kindOfFactory}. */
        private final int colorIndex;
        /** The entrance block inside the parent room that leads here, or null for the room that was read. */
        private final @Nullable BlockPos anchor;
        /** Where this room sits in {@link FactoryBlueprint#rooms()}; worked out while the rooms are listed. */
        private int index;
        /** The room this one stands inside, or -1 for the room that was read, see {@link #index}. */
        private int parentIndex = -1;

        private Room(List<Entry> blocks, int colorIndex, @Nullable BlockPos anchor) {
            this.blocks = List.copyOf(blocks);
            this.colorIndex = colorIndex;
            this.anchor = anchor == null ? null : anchor.immutable();
        }

        /** Everything standing in the room, in the order it is printed back: floor first, then upwards. */
        public List<Entry> blocks() {
            return blocks;
        }

        /** The rooms the entrance blocks standing in this one lead to, by the offset of that block. */
        public Map<BlockPos, Room> nested() {
            return Collections.unmodifiableMap(nested);
        }

        /**
         * The room the entrance block at {@code offset} leads to, or null when there is no factory nested
         * at that block - which is every block but the ones an entrance block was put down at.
         */
        public @Nullable Room nestedAt(BlockPos offset) {
            return nested.get(offset);
        }

        /** The colour kind a copy of this room is painted. */
        public int colorIndex() {
            return colorIndex;
        }

        /** Where this room sits in {@link FactoryBlueprint#rooms()}. */
        public int index() {
            return index;
        }

        /**
         * The room the entrance block leading here stands in, as a number into
         * {@link FactoryBlueprint#rooms()}, or -1 for the room that was read out - the one room of a
         * blueprint that no block leads into.
         */
        public int parentIndex() {
            return parentIndex;
        }

        /** The entrance block inside the parent room that leads here, or null for the room that was read. */
        public @Nullable BlockPos anchor() {
            return anchor;
        }

        public int size() {
            return blocks.size();
        }
    }

    private final Room root;
    /** Every room the blueprint carries, the one that was read first and then each room nested in it. */
    private List<Room> rooms;
    private final int colorIndex;
    private final int sourceFactory;
    private final String name;

    private FactoryBlueprint(String name, Room root, int colorIndex, int sourceFactory) {
        this.name = name;
        this.root = root;
        this.colorIndex = colorIndex;
        this.sourceFactory = sourceFactory;
        List<Room> flat = new ArrayList<>();
        flatten(root, flat);
        this.rooms = List.copyOf(flat);
    }

    /**
     * Lists the rooms in the order a copy builds them: a room first, then the rooms nested inside it, each
     * of which can only be built once its own entrance block has been put down. Every room therefore comes
     * after the room that holds the entrance block leading to it, which is what lets a print walk the list
     * from one end to the other.
     */
    /**
     * Lists the rooms again after the rooms nested in the blueprint were attached to the tree, which is
     * what reading a file does: the rooms are built up from the file rather than read in one pass, so the
     * order they are built in is only known once they are all there.
     */
    private void relist() {
        List<Room> flat = new ArrayList<>();
        flatten(root, flat);
        this.rooms = List.copyOf(flat);
    }

    private static void flatten(Room room, List<Room> out) {
        room.index = out.size();
        out.add(room);
        for (Room child : room.nested.values()) {
            child.parentIndex = room.index;
            flatten(child, out);
        }
    }

    public String name() {
        return name;
    }

    /** The colour kind of the factory this was taken from, which is the colour a copy is painted. */
    public int colorIndex() {
        return colorIndex;
    }

    /** The factory this was taken from, or {@code -1} for a blueprint from another world. */
    public int sourceFactory() {
        return sourceFactory;
    }

    /** The room that was read out, which is the one a copy of this blueprint stands for. */
    public Room root() {
        return root;
    }

    /**
     * Every room the blueprint carries, in the order they are built: the one that was read first, then the
     * ones nested inside it, see {@link #flatten}.
     */
    public List<Room> rooms() {
        return rooms;
    }

    /** The blocks of the room that was read out. */
    public List<Entry> blocks() {
        return root.blocks();
    }

    /** How many blocks the blueprint carries, every room counted. */
    public int size() {
        int total = 0;
        for (Room room : rooms) {
            total += room.size();
        }
        return total;
    }

    /**
     * Where a blueprint's offsets start: the room's own floor layer, one above the checkerboard floor, on
     * the corner of the cell. {@link #capture} writes its offsets against exactly this.
     */
    public static BlockPos origin(FactoryData.FactoryRecord.Cell cell) {
        return new BlockPos(cell.roomX(), FactoryData.FLOOR_Y + 1, cell.roomZ());
    }

    /**
     * A factory that cannot be copied out as it stands, with the line the player is told about it. A
     * capture reads a room and everything nested inside it, and gives up rather than half doing it when a
     * room leads somewhere a copy could not follow.
     */
    public static final class Refusal extends RuntimeException {
        private final transient Component reason;

        private Refusal(Component reason) {
            super(reason.getString());
            this.reason = reason;
        }

        /** What the player is told when a capture is given up on. */
        public Component reason() {
            return reason;
        }
    }

    /**
     * Reads the contents of one room out into a blueprint, and every factory standing inside it with it.
     *
     * <p>Everything in the room's free space is taken, floor by floor; the barrier shell and air are left
     * out, so what comes back is what the player built inside rather than the room itself. An entrance
     * block that was put down inside the room is a factory of its own - its room stands elsewhere in the
     * factory dimension - and that room is read out too, and so on (see {@link Room}). Entities are not
     * taken.
     *
     * @throws Refusal when a factory nested in the room cannot be copied as it stands: one that has grown
     *     past a single room cell, one nested deeper than {@link #MAX_NESTING_DEPTH}, or one that leads
     *     back into a factory this same capture is already reading
     */
    public static FactoryBlueprint capture(ServerLevel roomLevel, FactoryData data,
                                           FactoryData.FactoryRecord record,
                                           FactoryData.FactoryRecord.Cell cell, String name) {
        Set<Integer> reading = new LinkedHashSet<>();
        reading.add(record.id());
        Room root = readRoom(roomLevel, data, record, cell, reading, 0, null);
        FactoryBlueprint blueprint = new FactoryBlueprint(name, root,
                FactoryColors.kindOfFactory(record.colorIndex(), record.id()), record.id());
        LOGGER.info("Factory #{}: read {} blocks in {} rooms out of the room cell at {}",
                record.id(), blueprint.size(), blueprint.rooms().size(), cell.entrance().toShortString());
        return blueprint;
    }

    /**
     * Everything standing inside one room cell, in the order it is printed back: floor first, then up one
     * row at a time. Air and the barrier shell are left out - they are what a room is built with, not what
     * a player put in it - so what comes back is the contents of the room rather than the room itself.
     *
     * <p>An entrance block standing in the room is followed through to the room it leads to, which is read
     * out the same way. {@code reading} holds the factories this capture is in the middle of reading, so a
     * room that leads back into one of them is refused rather than followed round and round. A factory that
     * two entrance blocks of the same room lead to is read once for each of them, which is what those two
     * blocks lead to in the first place.
     */
    private static Room readRoom(ServerLevel level, FactoryData data, FactoryData.FactoryRecord record,
                                 FactoryData.FactoryRecord.Cell cell, Set<Integer> reading, int depth,
                                 @Nullable BlockPos anchor) {
        BlockPos origin = origin(cell);
        int width = FactoryData.CELL_SIZE;
        int height = FactoryData.INNER_HEIGHT;
        List<Entry> captured = new ArrayList<>();
        Map<BlockPos, Room> nested = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.is(ModBlocks.FACTORY_BARRIER.get())) {
                        continue;
                    }
                    BlockPos offset = new BlockPos(x, y, z);
                    captured.add(new Entry(offset, state, blockEntityTag(level, pos, state)));
                    FactoryData.FactoryRecord inner = nestedFactory(level, data, pos, state);
                    if (inner == null) {
                        continue;
                    }
                    FactoryData.FactoryRecord.Cell innerCell = inner.cellAt(pos);
                    if (innerCell == null) {
                        // An entrance block left behind by a factory that has since lost this cell: there is
                        // nothing behind it to read, so it travels as the block it is and is put down as one.
                        LOGGER.warn("The entrance block at {} leads into factory #{} no longer standing"
                                + " there; it is copied as a block of its own", pos, inner.id());
                        continue;
                    }
                    if (inner.cells().size() != 1) {
                        throw new Refusal(Component.translatable("message.recursivefactory.blueprint.grown"));
                    }
                    if (depth + 1 > MAX_NESTING_DEPTH) {
                        throw new Refusal(Component.translatable("message.recursivefactory.blueprint.deep",
                                MAX_NESTING_DEPTH));
                    }
                    if (!reading.add(inner.id())) {
                        throw new Refusal(Component.translatable("message.recursivefactory.blueprint.loop"));
                    }
                    try {
                        nested.put(offset, readRoom(level, data, inner, innerCell, reading, depth + 1, offset));
                    } finally {
                        reading.remove(inner.id());
                    }
                }
            }
        }
        Room room = new Room(captured, FactoryColors.kindOfFactory(record.colorIndex(), record.id()), anchor);
        for (Map.Entry<BlockPos, Room> child : nested.entrySet()) {
            room.nested.put(child.getKey(), child.getValue());
        }
        return room;
    }

    /**
     * The factory an entrance block standing at {@code pos} leads into, or null when the block there is
     * not an entrance block of a factory. Every entrance block put down inside a room is wired into a
     * factory of its own, which is what makes it a nested factory rather than a block.
     */
    private static @Nullable FactoryData.FactoryRecord nestedFactory(ServerLevel level, FactoryData data,
                                                                    BlockPos pos, BlockState state) {
        if (!state.is(ModBlocks.RECURSIVE_FACTORY.get())
                || !(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)
                || !entrance.hasFactoryId()) {
            return null;
        }
        return data.factory(entrance.getFactoryId());
    }

    /** Where one block of a blueprint stands in the world, given the cell of the room it belongs to. */
    public static BlockPos roomPos(FactoryData.FactoryRecord.Cell cell, BlockPos offset) {
        return origin(cell).offset(offset);
    }

    /** Puts one block of a blueprint where it belongs inside a room cell. */
    public static void placeEntry(ServerLevel level, FactoryData.FactoryRecord.Cell cell, Entry entry) {
        placeBlock(level, roomPos(cell, entry.pos()), entry.state(), entry.nbt(), true);
    }

    /**
     * Puts an entrance block of a nested factory where it belongs, and wires it into the room that was
     * built for it: the block goes down with everything it carried - its six face modes, say - and is then
     * bound to the new room and made to join up with any entrance block standing beside it.
     *
     * <p>The block is put down without the "somebody placed this" step an entrance block a player puts
     * down goes through, which is what starts a factory of its own. This block already leads into the room
     * that was built for it; letting it start a factory as well would leave that factory standing empty
     * and claiming the cell this block stands in, which is the room the copy is printed into.
     */
    public static void placeEntry(ServerLevel level, FactoryData.FactoryRecord.Cell cell, Entry entry,
                                  int nestedRoomId, int nestedColorIndex) {
        BlockPos pos = roomPos(cell, entry.pos());
        placeBlock(level, pos, entry.state(), entry.nbt(), false);
        FactoryDimension.linkEntrance(level, pos, nestedRoomId, nestedColorIndex);
    }

    /**
     * Puts the whole blueprint into the room it was built for, at once, and builds a room of its own for
     * every factory nested in it. This is what a second copy of a printed factory is made of: the blocks
     * come from the room the item points at rather than from the file the room was printed from, so a copy
     * carries whatever the player has built there since.
     *
     * @return how many blocks went in, every room counted
     */
    public int placeAll(ServerLevel level, FactoryData data, int roomId, @Nullable UUID owner) {
        return placeRoom(level, data, roomId, root, owner);
    }

    private int placeRoom(ServerLevel level, FactoryData data, int roomId, Room room, @Nullable UUID owner) {
        FactoryData.FactoryRecord record = data.factory(roomId);
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.anchorCell();
        if (cell == null) {
            return 0;
        }
        int placed = 0;
        for (Entry entry : room.blocks()) {
            Room nested = room.nestedAt(entry.pos());
            if (nested == null) {
                placeEntry(level, cell, entry);
            } else {
                int nestedRoomId = FactoryDimension.newRoom(level, data, owner, nested.colorIndex());
                placeEntry(level, cell, entry, nestedRoomId, nested.colorIndex());
                placed += placeRoom(level, data, nestedRoomId, nested, owner);
            }
            placed++;
        }
        return placed;
    }

    /**
     * Puts one block down, the way a print puts it back: the block itself first, then the block entity it
     * was carrying, told where it now stands. A block entity is handed back its own data rather than being
     * made from scratch, which is what carries a machine's own settings - a funnel's filters, a shaft's
     * speed, a chest's contents - over into the copy.
     *
     * <p>The block is placed with a full update rather than quietly, so the blocks around it are told
     * something arrived: machinery that wants a neighbour to lean on - a funnel looking for a container,
     * a shaft looking for the next shaft - finds it as the room fills up from the floor upwards.
     *
     * @param placedBy whether the block is also told that somebody just placed it, see
     *     {@link #placeEntry(ServerLevel, FactoryData.FactoryRecord.Cell, Entry, int, int)}
     */
    private static void placeBlock(ServerLevel level, BlockPos pos, BlockState state, @Nullable CompoundTag nbt,
                                   boolean placedBy) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (nbt != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                CompoundTag tag = nbt.copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                blockEntity.loadWithComponents(tag, level.registryAccess());
                if (blockEntity instanceof KineticBlockEntity kinetic) {
                    // A machine that was put somewhere new has to work its own shape out again: a bearing
                    // rebuilds its contraption, a belt its segments.
                    kinetic.warnOfMovement();
                }
            }
        }
        if (!placedBy) {
            return;
        }
        try {
            state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
        } catch (RuntimeException exception) {
            LOGGER.debug("A block placed from a blueprint did not take being placed at {}", pos, exception);
        }
    }

    /**
     * What the block entity standing at {@code pos} is carrying, with the position it stands at written
     * onto it. A block of this mod's own - an entrance block, or a barrier standing in the room - has the
     * parts of its data that belong to this particular block dropped: a copy carries the block and how it
     * was set up, but not the factory it happens to be wired into (see
     * {@link EndpointBlockEntity#stripPlaceBoundTags}).
     */
    private static @Nullable CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
        if (blockEntity instanceof EndpointBlockEntity endpoint) {
            EndpointBlockEntity.stripPlaceBoundTags(tag);
            LOGGER.debug("Copied the endpoint at {} without the factory it stands for: {}", pos, endpoint);
        }
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    /**
     * A block entity of the kind an entry carries, loaded with the tag the entry was taken with. This is
     * what tells a printer what one block of a blueprint costs: Create asks the block entity itself for the
     * items it needs (see {@code ItemRequirement#of}).
     */
    public static @Nullable BlockEntity newBlockEntity(ServerLevel level, Entry entry) {
        if (entry.nbt() == null || !entry.state().hasBlockEntity()
                || !(entry.state().getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, entry.state());
        if (blockEntity != null) {
            blockEntity.loadWithComponents(entry.nbt(), level.registryAccess());
        }
        return blockEntity;
    }

    /** Writes the blueprint into the world's blueprint folder, answering whether it got there. */
    public boolean write(MinecraftServer server) {
        Path folder = folder(server).normalize();
        Path file = fileIn(folder, name);
        if (file == null) {
            return false;
        }
        try {
            Files.createDirectories(folder);
            NbtIo.writeCompressed(serialize(server.registryAccess()), file);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Could not write the blueprint {}", file, exception);
            return false;
        }
    }

    /**
     * The file's contents as NBT: the structure itself, plus a note of where it came from and the rooms
     * standing inside it.
     *
     * <p>The room that was read out stays where a structure file keeps one - {@code size}, {@code palette}
     * and {@code blocks} at the top - so the file still opens in anything that reads blueprints, and only
     * the rooms nested in it are written under a key of our own.
     */
    private CompoundTag serialize(HolderLookup.Provider registries) {
        CompoundTag root = writeRoom(this.root);
        root.put(META_TAG, writeMeta(colorIndex));

        ListTag rooms = new ListTag();
        for (Room room : this.rooms) {
            if (room == this.root) {
                continue;
            }
            CompoundTag roomTag = writeRoom(room);
            roomTag.putInt(PARENT_TAG, room.parentIndex);
            roomTag.put(POS_TAG, newIntList(room.anchor.getX(), room.anchor.getY(), room.anchor.getZ()));
            roomTag.put(META_TAG, writeMeta(room.colorIndex));
            rooms.add(roomTag);
        }
        if (!rooms.isEmpty()) {
            root.getCompound(META_TAG).put(ROOMS_TAG, rooms);
        }
        NbtUtils.addCurrentDataVersion(root);
        return root;
    }

    /** One room as a structure: its size, the palette of the blocks in it, and the blocks themselves. */
    private static CompoundTag writeRoom(Room room) {
        CompoundTag tag = new CompoundTag();
        tag.put(SIZE_TAG, newIntList(FactoryData.CELL_SIZE, FactoryData.INNER_HEIGHT, FactoryData.CELL_SIZE));

        ListTag palette = new ListTag();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        ListTag blocks = new ListTag();
        for (Entry entry : room.blocks) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.put("pos", newIntList(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()));
            blockTag.putInt("state", paletteIndex.computeIfAbsent(entry.state(), state -> {
                palette.add(NbtUtils.writeBlockState(state));
                return palette.size() - 1;
            }));
            if (entry.nbt() != null) {
                blockTag.put("nbt", entry.nbt().copy());
            }
            blocks.add(blockTag);
        }
        tag.put(PALETTE_TAG, palette);
        tag.put(BLOCKS_TAG, blocks);
        tag.put(ENTITIES_TAG, new ListTag());
        return tag;
    }

    /** Where a room or a whole blueprint came from, and the colour kind a copy of it is painted. */
    private CompoundTag writeMeta(int roomColor) {
        CompoundTag meta = new CompoundTag();
        meta.putString(NAME_TAG, name);
        meta.putInt(COLOR_TAG, roomColor);
        meta.putInt(SOURCE_TAG, sourceFactory);
        return meta;
    }

    private static ListTag newIntList(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(net.minecraft.nbt.IntTag.valueOf(value));
        }
        return list;
    }

    /** Reads a blueprint back, or {@code null} when the file is missing or unreadable. */
    public static @Nullable FactoryBlueprint read(MinecraftServer server, String name) {
        Path folder = folder(server).normalize();
        Path file = fileIn(folder, name);
        if (file == null || !Files.isRegularFile(file)) {
            LOGGER.warn("There is no blueprint file called {} in {}", name, folder);
            return null;
        }
        CompoundTag root;
        try {
            // Read gzipped, the way a Create schematic is written, so a blueprint is a structure file and
            // not a raw NBT dump.
            root = NbtIo.readCompressed(file, NbtAccounter.create(0x20000000L));
        } catch (IOException exception) {
            LOGGER.warn("Could not read the blueprint {}", file, exception);
            return null;
        }
        return read(server, name, root);
    }

    private static @Nullable FactoryBlueprint read(MinecraftServer server, String name, CompoundTag root) {
        HolderLookup<net.minecraft.world.level.block.Block> lookup = server.registryAccess()
                .lookupOrThrow(Registries.BLOCK);
        CompoundTag meta = root.getCompound(META_TAG);
        int colorIndex = meta.contains(COLOR_TAG) ? meta.getInt(COLOR_TAG) : FactoryColors.NO_COLOR;
        int sourceFactory = meta.contains(SOURCE_TAG) ? meta.getInt(SOURCE_TAG) : -1;
        String storedName = meta.contains(NAME_TAG) ? meta.getString(NAME_TAG) : name;

        Room top = readRoom(storedName, root, lookup, null, colorIndex);
        if (top == null) {
            return null;
        }

        // The rooms nested in the top one, each hanging off the room that holds the entrance block leading
        // to it. They are listed in the order a copy builds them - a room after the room it hangs off - so a
        // room whose parent is not in the list yet is one a hand-edited file got wrong, and is left out.
        FactoryBlueprint blueprint = new FactoryBlueprint(storedName, top, colorIndex, sourceFactory);
        List<Room> added = new ArrayList<>(blueprint.rooms());
        for (Tag tag : meta.getList(ROOMS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag roomTag = (CompoundTag) tag;
            int parentIndex = roomTag.getInt(PARENT_TAG);
            ListTag pos = roomTag.getList(POS_TAG, Tag.TAG_INT);
            if (parentIndex < 0 || parentIndex >= added.size() || pos.size() != 3) {
                LOGGER.warn("The blueprint {} lists a room hanging off room {} and is not read further;"
                        + " it is copied without that room", name, parentIndex);
                continue;
            }
            Room parent = added.get(parentIndex);
            BlockPos anchor = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            int roomColor = roomTag.getCompound(META_TAG).contains(COLOR_TAG)
                    ? roomTag.getCompound(META_TAG).getInt(COLOR_TAG)
                    : colorIndex;
            Room room = readRoom(name, roomTag, lookup, anchor, roomColor);
            if (room == null) {
                continue;
            }
            parent.nested.put(anchor, room);
            added.add(room);
        }
        blueprint.relist();
        return blueprint;
    }

    /**
     * One room out of a structure: the blocks the file lists, in the order it lists them. A structure with
     * no palette in it is not a room, and comes back as null rather than as an empty one.
     */
    private static @Nullable Room readRoom(String name, CompoundTag tag, HolderLookup<Block> lookup,
                                           @Nullable BlockPos anchor, @Nullable Integer roomColor) {
        ListTag palette = tag.getList(PALETTE_TAG, Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            LOGGER.warn("The blueprint {} has a room with no palette in it", name);
            return null;
        }
        List<BlockState> states = new ArrayList<>(palette.size());
        for (Tag entry : palette) {
            states.add(NbtUtils.readBlockState(lookup, (CompoundTag) entry));
        }

        List<Entry> blocks = new ArrayList<>();
        for (Tag entry : tag.getList(BLOCKS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag blockTag = (CompoundTag) entry;
            ListTag pos = blockTag.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) {
                continue;
            }
            int index = blockTag.getInt("state");
            if (index < 0 || index >= states.size()) {
                continue;
            }
            blocks.add(new Entry(
                    new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)),
                    states.get(index),
                    blockTag.contains("nbt", Tag.TAG_COMPOUND) ? blockTag.getCompound("nbt") : null
            ));
        }
        return new Room(blocks, roomColor == null ? FactoryColors.NO_COLOR : roomColor, anchor);
    }

    /** The folder the blueprint files live in, inside the world. */
    public static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    /**
     * The file a blueprint name stands for, inside the folder blueprints are kept in, or {@code null} for a
     * name that would lead out of it. A blueprint is named by the item that carries it, so the name is not
     * trusted to be a plain file name: a name with a separator or a {@code ..} in it is refused rather than
     * resolved, which keeps a blueprint from reaching anything that is not another blueprint.
     *
     * <p>The folder is normalised along with the file - a world path comes back as something like
     * {@code .\world\.}, so the two have to be compared in the same shape for the check to mean anything.
     */
    private static @Nullable Path fileIn(Path folder, String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            LOGGER.warn("Refusing a blueprint name that is not a plain file name: {}", name);
            return null;
        }
        Path file = folder.resolve(name).normalize();
        return file.startsWith(folder) && file.getParent() != null && file.getParent().equals(folder)
                ? file
                : null;
    }

    /** A file name no other blueprint is using, so two blueprints never overwrite each other. */
    public static String newName() {
        return "blueprint-" + Long.toHexString(System.nanoTime()) + SUFFIX;
    }
}
