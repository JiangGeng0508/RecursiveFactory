package com.zinzinc.recursivefactory.world;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.data.FactoryColors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>The name of the file is what a blueprint item carries. The file itself lives in the world's own
 * folder, {@code recursivefactory/blueprints}, so a room full of machinery never has to fit inside an
 * item's data.
 */
public final class FactoryBlueprint {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Where the files live, relative to the world folder. */
    public static final String FOLDER = "recursivefactory/blueprints";
    /** The keys of the file, in the shape a vanilla structure uses. */
    private static final String SIZE_TAG = "size";
    private static final String PALETTE_TAG = "palette";
    private static final String BLOCKS_TAG = "blocks";
    private static final String ENTITIES_TAG = "entities";
    private static final String META_TAG = "RecursiveFactory";
    private static final String NAME_TAG = "Name";
    private static final String COLOR_TAG = "Color";
    private static final String SOURCE_TAG = "Source";
    private static final String SUFFIX = ".nbt";

    /** One block of the room, at its offset from the room's own floor layer. */
    public record Entry(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
        public Entry {
            pos = pos.immutable();
            nbt = nbt == null ? null : nbt.copy();
        }
    }

    private final List<Entry> blocks;
    private final int colorIndex;
    private final int sourceFactory;
    private final String name;

    private FactoryBlueprint(String name, List<Entry> blocks, int colorIndex, int sourceFactory) {
        this.name = name;
        this.blocks = List.copyOf(blocks);
        this.colorIndex = colorIndex;
        this.sourceFactory = sourceFactory;
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

    public List<Entry> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    /**
     * Where a blueprint's offsets start: the room's own floor layer, one above the checkerboard floor, on
     * the corner of the cell. {@link #capture} writes its offsets against exactly this.
     */
    public static BlockPos origin(FactoryData.FactoryRecord.Cell cell) {
        return new BlockPos(cell.roomX(), FactoryData.FLOOR_Y + 1, cell.roomZ());
    }

    /**
     * Reads the contents of one room out into a blueprint. Everything in the room's free space is taken,
     * floor by floor; the barrier shell and air are left out, so what comes back is what the player built
     * inside rather than the room itself. Entities are not taken.
     */
    public static FactoryBlueprint capture(ServerLevel roomLevel, FactoryData.FactoryRecord record,
                                           FactoryData.FactoryRecord.Cell cell, String name) {
        List<Entry> captured = readCell(roomLevel, cell);
        LOGGER.info("Factory #{}: took a blueprint of {} blocks from the room cell at {}",
                record.id(), captured.size(), cell.entrance().toShortString());
        // The colour is written down as the kind the room is really painted rather than as the setting it
        // was given, so a factory from an older save - which has no colour of its own and is painted with a
        // hash of its id - still comes out of a printer in the colour it was standing in.
        return new FactoryBlueprint(name, captured,
                FactoryColors.kindOfFactory(record.colorIndex(), record.id()), record.id());
    }

    /**
     * Everything standing inside one room cell, in the order it is printed back: floor first, then up one
     * row at a time. Air and the barrier shell are left out - they are what a room is built with, not what
     * a player put in it - so what comes back is the contents of the room rather than the room itself.
     */
    public static List<Entry> readCell(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        BlockPos origin = origin(cell);
        int width = FactoryData.CELL_SIZE;
        int height = FactoryData.INNER_HEIGHT;
        List<Entry> captured = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.is(ModBlocks.FACTORY_BARRIER.get())) {
                        continue;
                    }
                    captured.add(new Entry(new BlockPos(x, y, z), state, blockEntityTag(level, pos, state)));
                }
            }
        }
        return captured;
    }

    /**
     * Copies everything standing in one room cell over into another one, block for block. This is what a
     * second copy of a printed factory is made of: the blocks are taken from the room that was printed
     * rather than from the file it was printed from, so a copy carries what the player built there.
     *
     * @return how many blocks went over
     */
    public static int copyInto(ServerLevel level, FactoryData.FactoryRecord.Cell source,
                               FactoryData.FactoryRecord.Cell target) {
        List<Entry> entries = readCell(level, source);
        for (Entry entry : entries) {
            placeEntry(level, target, entry);
        }
        return entries.size();
    }

    /** Puts one block of a blueprint where it belongs inside a room cell. */
    public static void placeEntry(ServerLevel level, FactoryData.FactoryRecord.Cell cell, Entry entry) {
        placeBlock(level, origin(cell).offset(entry.pos()), entry.state(), entry.nbt());
    }

    /** Puts the whole blueprint into a room cell, at once. */
    public int placeAll(ServerLevel level, FactoryData.FactoryRecord.Cell cell) {
        for (Entry entry : blocks) {
            placeEntry(level, cell, entry);
        }
        return blocks.size();
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
     */
    private static void placeBlock(ServerLevel level, BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
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
        try {
            state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
        } catch (RuntimeException exception) {
            LOGGER.debug("A block placed from a blueprint did not take being placed at {}", pos, exception);
        }
    }

    private static @Nullable CompoundTag blockEntityTag(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
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

    /** The file's contents as NBT: the structure itself, plus a note of where it came from. */
    private CompoundTag serialize(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        root.put(SIZE_TAG, newIntList(FactoryData.CELL_SIZE, FactoryData.INNER_HEIGHT, FactoryData.CELL_SIZE));

        ListTag palette = new ListTag();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        ListTag blocks = new ListTag();
        for (Entry entry : this.blocks) {
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
        root.put(PALETTE_TAG, palette);
        root.put(BLOCKS_TAG, blocks);
        root.put(ENTITIES_TAG, new ListTag());

        CompoundTag meta = new CompoundTag();
        meta.putString(NAME_TAG, name);
        meta.putInt(COLOR_TAG, colorIndex);
        meta.putInt(SOURCE_TAG, sourceFactory);
        root.put(META_TAG, meta);
        NbtUtils.addCurrentDataVersion(root);
        return root;
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
        ListTag palette = root.getList(PALETTE_TAG, Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            LOGGER.warn("The blueprint {} has no palette in it", name);
            return null;
        }
        HolderLookup<net.minecraft.world.level.block.Block> lookup = server.registryAccess()
                .lookupOrThrow(Registries.BLOCK);
        List<BlockState> states = new ArrayList<>(palette.size());
        for (Tag entry : palette) {
            states.add(NbtUtils.readBlockState(lookup, (CompoundTag) entry));
        }

        List<Entry> blocks = new ArrayList<>();
        for (Tag entry : root.getList(BLOCKS_TAG, Tag.TAG_COMPOUND)) {
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

        CompoundTag meta = root.getCompound(META_TAG);
        int colorIndex = meta.contains(COLOR_TAG) ? meta.getInt(COLOR_TAG) : FactoryColors.NO_COLOR;
        int sourceFactory = meta.contains(SOURCE_TAG) ? meta.getInt(SOURCE_TAG) : -1;
        String storedName = meta.contains(NAME_TAG) ? meta.getString(NAME_TAG) : name;
        return new FactoryBlueprint(storedName, blocks, colorIndex, sourceFactory);
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
