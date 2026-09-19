package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class FactoryData extends SavedData {
    public static final String DATA_NAME = RecursiveFactory.MODID + "_factories";
    /** Side of one room cell, and with it the footprint of the whole room shell. */
    public static final int CELL_SIZE = 16;
    /**
     * Height of the room shell, from its bottom barrier layer to its top one. A room is a closed box:
     * one solid layer at the bottom, the floor, the free space, and one solid layer on top.
     */
    public static final int ROOM_HEIGHT = 32;
    /** Bottom barrier layer. The checkerboard floor sits on top of it. */
    public static final int BASE_Y = 64;
    /** The checkerboard floor layer. */
    public static final int FLOOR_Y = BASE_Y + 1;
    /** Top barrier layer. */
    public static final int CEILING_Y = BASE_Y + ROOM_HEIGHT - 1;
    /** Free space inside one cell: a cell is {@link #CELL_SIZE} across, less a barrier on each side. */
    public static final int INNER_SIZE = CELL_SIZE - 2;
    /** Free height inside a room: the base, floor and ceiling layers are all solid. */
    public static final int INNER_HEIGHT = CEILING_Y - FLOOR_Y - 1;
    /**
     * How far apart neighbouring factory rooms sit, in blocks. Thirty two chunks keeps one room's
     * forced chunks well clear of the next one's.
     *
     * <p>Room positions are derived from this at run time rather than saved per factory, so changing
     * it moves every existing factory to a fresh platform and leaves whatever was built in the old
     * rooms behind at the old coordinates.
     */
    public static final int SLOT_SPACING_BLOCKS = 32 * 16;

    private final Map<Integer, FactoryRecord> factories = new LinkedHashMap<>();
    private int nextFactoryId = 1;
    private int nextSlotIndex;

    public static FactoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactoryData::new, FactoryData::load),
                DATA_NAME
        );
    }

    private static FactoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryData data = new FactoryData();
        data.nextFactoryId = Math.max(1, tag.getInt("NextFactoryId"));
        data.nextSlotIndex = tag.getInt("NextSlotIndex");

        ListTag factoriesTag = tag.getList("Factories", Tag.TAG_COMPOUND);
        for (Tag entry : factoriesTag) {
            FactoryRecord record = FactoryRecord.load((CompoundTag) entry);
            data.factories.put(record.id(), record);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextFactoryId", nextFactoryId);
        tag.putInt("NextSlotIndex", nextSlotIndex);

        ListTag factoriesTag = new ListTag();
        for (FactoryRecord record : factories.values()) {
            factoriesTag.add(record.save());
        }
        tag.put("Factories", factoriesTag);
        return tag;
    }

    public Collection<FactoryRecord> factories() {
        return List.copyOf(factories.values());
    }

    @Nullable
    public FactoryRecord factory(int factoryId) {
        return factories.get(factoryId);
    }

    public FactoryRecord create(@Nullable UUID owner) {
        int factoryId = nextFactoryId++;
        int slotX = nextSlotIndex % 64;
        int slotZ = nextSlotIndex / 64;
        nextSlotIndex++;

        FactoryRecord record = new FactoryRecord(
                factoryId,
                owner,
                slotX,
                slotZ,
                null,
                BlockPos.ZERO,
                List.of()
        );
        factories.put(factoryId, record);
        setDirty();
        return record;
    }

    private void update(FactoryRecord record) {
        factories.put(record.id(), record);
        setDirty();
    }

    /** Binds a factory's first entrance cell: its room cell is the one the slot's base chunk covers. */
    public void bindEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null) {
            ChunkPos chunk = record.baseChunk();
            FactoryRecord.Cell cell = new FactoryRecord.Cell(
                    pos,
                    chunk.getMinBlockX(),
                    chunk.getMinBlockZ()
            );
            update(record.withEntrance(dimension, pos).withCells(List.of(cell)));
        }
    }

    /**
     * Grows a factory by one entrance cell. The room cell is given rather than derived so that removing
     * cells later never shifts the room layout: each cell keeps the room position it was placed with.
     */
    public void addEntrance(int factoryId, ResourceLocation dimension, BlockPos pos, int roomX, int roomZ) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null && dimension.equals(record.entranceDimension())) {
            List<FactoryRecord.Cell> cells = new ArrayList<>(record.cells());
            cells.add(new FactoryRecord.Cell(pos, roomX, roomZ));
            update(record.withCells(cells));
        }
    }

    /**
     * Removes one entrance cell. A factory that still has cells keeps going with the rest - the anchor
     * moves to another cell - and only a factory with no cells left loses its entrance.
     */
    public void clearEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record == null || !dimension.equals(record.entranceDimension())) {
            return;
        }
        List<FactoryRecord.Cell> remaining = new ArrayList<>(record.cells());
        remaining.removeIf(cell -> cell.entrance().equals(pos));
        if (remaining.isEmpty()) {
            update(record.withEntrance(null, BlockPos.ZERO).withCells(List.of()));
        } else {
            BlockPos anchor = record.entrancePos().equals(pos)
                    ? remaining.get(0).entrance()
                    : record.entrancePos();
            update(record.withEntrance(dimension, anchor).withCells(remaining));
        }
    }

    /** The factory whose entrance takes up this cell, if any. */
    @Nullable
    public FactoryRecord factoryWithEntranceCell(ResourceLocation dimension, BlockPos pos) {
        for (FactoryRecord record : factories.values()) {
            if (dimension.equals(record.entranceDimension()) && record.cellAt(pos) != null) {
                return record;
            }
        }
        return null;
    }

    /** The factory whose room covers this position inside the factory dimension, if any. */
    @Nullable
    public FactoryRecord factoryAt(BlockPos pos) {
        if (pos.getY() < BASE_Y || pos.getY() > CEILING_Y) {
            return null;
        }
        for (FactoryRecord record : factories.values()) {
            if (record.roomContains(pos)) {
                return record;
            }
        }
        return null;
    }

    public record FactoryRecord(
            int id,
            @Nullable UUID owner,
            int slotX,
            int slotZ,
            @Nullable ResourceLocation entranceDimension,
            BlockPos entrancePos,
            List<Cell> cells
    ) {
        /**
         * One entrance block and the room cell it stands for. The room origin is saved per cell so the
         * room layout never shifts when cells are removed; the layout as a whole is the entrance layout,
         * translated, with each cell sixteen blocks across.
         */
        public record Cell(BlockPos entrance, int roomX, int roomZ) {
            public Cell {
                entrance = entrance.immutable();
            }

            /** Middle of the cell, at standing height: where a player arriving through this cell lands. */
            public BlockPos center() {
                return new BlockPos(roomX + CELL_SIZE / 2, FLOOR_Y + 1, roomZ + CELL_SIZE / 2);
            }
        }

        public FactoryRecord {
            entrancePos = entrancePos.immutable();
            cells = List.copyOf(cells);
        }

        @Nullable
        public Cell cellAt(BlockPos pos) {
            for (Cell cell : cells) {
                if (cell.entrance().equals(pos)) {
                    return cell;
                }
            }
            return null;
        }

        public boolean roomContains(BlockPos pos) {
            return roomContains(pos.getX(), pos.getZ());
        }

        /** The horizontal half of {@link #roomContains(BlockPos)}, for callers that walk columns. */
        public boolean roomContains(int x, int z) {
            for (Cell cell : cells) {
                if (x >= cell.roomX() && x < cell.roomX() + CELL_SIZE
                        && z >= cell.roomZ() && z < cell.roomZ() + CELL_SIZE) {
                    return true;
                }
            }
            return false;
        }

        /** The cell the anchor entrance stands for, falling back to any cell for a record mid-edit. */
        @Nullable
        public Cell anchorCell() {
            Cell cell = cellAt(entrancePos);
            return cell != null ? cell : (cells.isEmpty() ? null : cells.get(0));
        }

        public net.minecraft.world.level.ChunkPos baseChunk() {
            return new net.minecraft.world.level.ChunkPos(
                    (16 + slotX * SLOT_SPACING_BLOCKS) >> 4,
                    (16 + slotZ * SLOT_SPACING_BLOCKS) >> 4
            );
        }

        public FactoryRecord withEntrance(@Nullable ResourceLocation dimension, BlockPos pos) {
            return new FactoryRecord(id, owner, slotX, slotZ, dimension, pos, cells);
        }

        public FactoryRecord withCells(List<Cell> updatedCells) {
            return new FactoryRecord(id, owner, slotX, slotZ, entranceDimension, entrancePos, updatedCells);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Id", id);
            if (owner != null) {
                tag.putUUID("Owner", owner);
            }
            tag.putInt("SlotX", slotX);
            tag.putInt("SlotZ", slotZ);
            putEndpoint(tag, "Entrance", entranceDimension, entrancePos);

            ListTag cellsTag = new ListTag();
            for (Cell cell : cells) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putInt("X", cell.entrance().getX());
                cellTag.putInt("Y", cell.entrance().getY());
                cellTag.putInt("Z", cell.entrance().getZ());
                cellTag.putInt("RoomX", cell.roomX());
                cellTag.putInt("RoomZ", cell.roomZ());
                cellsTag.add(cellTag);
            }
            tag.put("Cells", cellsTag);
            return tag;
        }

        private static FactoryRecord load(CompoundTag tag) {
            UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
            Endpoint entrance = readEndpoint(tag, "Entrance");

            List<Cell> cells = new ArrayList<>();
            ListTag cellsTag = tag.getList("Cells", Tag.TAG_COMPOUND);
            for (Tag entry : cellsTag) {
                CompoundTag cellTag = (CompoundTag) entry;
                cells.add(new Cell(
                        new BlockPos(cellTag.getInt("X"), cellTag.getInt("Y"), cellTag.getInt("Z")),
                        cellTag.getInt("RoomX"),
                        cellTag.getInt("RoomZ")
                ));
            }

            FactoryRecord record = new FactoryRecord(
                    tag.getInt("Id"),
                    owner,
                    tag.getInt("SlotX"),
                    tag.getInt("SlotZ"),
                    entrance.dimension(),
                    entrance.pos(),
                    cells
            );
            // Records saved before a factory could hold several cells: the one entrance is the one cell.
            if (record.cells().isEmpty() && record.entranceDimension() != null) {
                record = record.withCells(List.of(new Cell(
                        record.entrancePos(),
                        record.baseChunk().getMinBlockX(),
                        record.baseChunk().getMinBlockZ()
                )));
            }
            return record;
        }

        private static void putEndpoint(CompoundTag tag, String prefix, @Nullable ResourceLocation dimension, BlockPos pos) {
            if (dimension != null) {
                tag.putString(prefix + "Dimension", dimension.toString());
            }
            tag.putInt(prefix + "X", pos.getX());
            tag.putInt(prefix + "Y", pos.getY());
            tag.putInt(prefix + "Z", pos.getZ());
        }

        private static Endpoint readEndpoint(CompoundTag tag, String prefix) {
            ResourceLocation dimension = tag.contains(prefix + "Dimension", Tag.TAG_STRING)
                    ? ResourceLocation.parse(tag.getString(prefix + "Dimension"))
                    : null;
            BlockPos pos = new BlockPos(
                    tag.getInt(prefix + "X"),
                    tag.getInt(prefix + "Y"),
                    tag.getInt(prefix + "Z")
            );
            return new Endpoint(dimension, pos);
        }

        private record Endpoint(@Nullable ResourceLocation dimension, BlockPos pos) {
        }
    }
}
