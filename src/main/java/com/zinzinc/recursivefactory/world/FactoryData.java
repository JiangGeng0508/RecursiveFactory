package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.data.FactoryColors;
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
    /**
     * The entrance a room cell carries while no entrance block is bound to it: a room that was printed, or
     * copied from another room, stands there with a cell of its own before anybody has put a block down to
     * walk in through. Binding an entrance later moves this stand-in to the block's own position, so the
     * room never moves and never has to be built a second time (see {@link #bindRoomEntrance}).
     */
    public static final BlockPos UNBOUND_ENTRANCE = BlockPos.ZERO;
    /** Side of one room cell, and with it the footprint of the whole room shell. */
    public static final int CELL_SIZE = 16;
    /**
     * Height of the room shell, from its bottom barrier layer to its top one. A room is a closed box:
     * one solid layer at the bottom, the floor, the free space, and one solid layer on top.
     *
     * <p>A room is as tall as it is wide, which is what makes the face preview of an entrance block work
     * out: sampled edge to edge and scaled by sixteen, the whole room - shell included - lands exactly on
     * the block's own cube, with the shell's layers and columns forming the block's frame.
     */
    public static final int ROOM_HEIGHT = 16;
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

    public FactoryRecord create(@Nullable UUID owner, int colorIndex) {
        int factoryId = nextFactoryId++;
        int slotX = nextSlotIndex % 64;
        int slotZ = nextSlotIndex / 64;
        nextSlotIndex++;

        FactoryRecord record = new FactoryRecord(
                factoryId,
                owner,
                colorIndex,
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
            // A brand new cell: its floor still has to be laid by the first look at the room.
            FactoryRecord.Cell cell = new FactoryRecord.Cell(
                    pos,
                    chunk.getMinBlockX(),
                    chunk.getMinBlockZ(),
                    false
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
            // The cell that just joined has no floor yet, including the seam it shares with its neighbour.
            cells.add(new FactoryRecord.Cell(pos, roomX, roomZ, false));
            update(record.withCells(cells));
        }
    }

    /**
     * Gives a factory the room cell of its own slot without handing it an entrance: a room that was
     * printed, or copied from another one, is standing there before anybody has walked into it. The floor
     * still has to be laid, which the next look at the room does (see FactoryDimension#prepare).
     *
     * <p>The cell's entrance is the {@link #UNBOUND_ENTRANCE} stand-in until {@link #bindRoomEntrance}
     * moves it to the block that is finally put down for it.
     */
    public void bindRoom(int factoryId) {
        FactoryRecord record = factories.get(factoryId);
        if (record == null || !record.cells().isEmpty()) {
            return;
        }
        ChunkPos chunk = record.baseChunk();
        update(record.withCells(List.of(new FactoryRecord.Cell(
                UNBOUND_ENTRANCE,
                chunk.getMinBlockX(),
                chunk.getMinBlockZ(),
                false
        ))));
    }

    /**
     * Binds an entrance to a room that is already built, without building anything again: the cell's
     * stand-in entrance becomes the block's position and everything else about the cell - where the room
     * is, and the floor that was laid for it - stays as it is. This is how a printed or copied room gets
     * the entrance block a player puts down for it.
     */
    public void bindRoomEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record == null) {
            return;
        }
        List<FactoryRecord.Cell> cells = new ArrayList<>(record.cells().size());
        for (FactoryRecord.Cell cell : record.cells()) {
            cells.add(cell.entrance().equals(UNBOUND_ENTRANCE)
                    ? new FactoryRecord.Cell(pos, cell.roomX(), cell.roomZ(), cell.floorLaid())
                    : cell);
        }
        update(record.withEntrance(dimension, pos).withCells(cells));
    }

    /**
     * Notes that one of a factory's cells has had its checkerboard floor laid, so a room that is looked
     * at again does not lay it a second time. Nothing happens when the cell already says so, which keeps
     * the repeated looks at a room from dirtying the save on every entry.
     */
    public void markFloorLaid(int factoryId, BlockPos entrancePos) {
        FactoryRecord record = factories.get(factoryId);
        if (record == null) {
            return;
        }
        FactoryRecord.Cell cell = record.cellAt(entrancePos);
        if (cell == null || cell.floorLaid()) {
            return;
        }
        List<FactoryRecord.Cell> cells = new ArrayList<>(record.cells());
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).entrance().equals(entrancePos)) {
                cells.set(i, cells.get(i).withFloorLaid(true));
            }
        }
        update(record.withCells(cells));
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
            /**
             * Which of the sixteen colour kinds this factory is painted, or
             * {@link FactoryColors#NO_COLOR} for a factory that was never given one: its colour is then
             * worked out from its id, so a factory from a save older than the choice keeps the colour it
             * has always had.
             */
            int colorIndex,
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
         *
         * <p>{@code floorLaid} remembers that the cell's checkerboard floor has been put down, so a
         * later look at the room knows the floor is one the player has been living with rather than one
         * that still has to be built. See {@code room.repairBrokenFloor} for what that changes.
         */
        public record Cell(BlockPos entrance, int roomX, int roomZ, boolean floorLaid) {
            public Cell {
                entrance = entrance.immutable();
            }

            /** Middle of the cell, at standing height: where a player arriving through this cell lands. */
            public BlockPos center() {
                return new BlockPos(roomX + CELL_SIZE / 2, FLOOR_Y + 1, roomZ + CELL_SIZE / 2);
            }

            /**
             * The middle of the cell's room shell, one block below {@link #center()}: the anchor a face
             * preview is sampled around. A sample of {@link #ROOM_HEIGHT} rows around it covers the room
             * from its base layer to its ceiling, so the whole room - shell and all - is what a sixteen
             * sixteenths wide preview shows.
             */
            public BlockPos previewCenter() {
                return new BlockPos(roomX + CELL_SIZE / 2, FLOOR_Y, roomZ + CELL_SIZE / 2);
            }

            public Cell withFloorLaid(boolean laid) {
                return new Cell(entrance, roomX, roomZ, laid);
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
            return new FactoryRecord(id, owner, colorIndex, slotX, slotZ, dimension, pos, cells);
        }

        public FactoryRecord withCells(List<Cell> updatedCells) {
            return new FactoryRecord(id, owner, colorIndex, slotX, slotZ, entranceDimension, entrancePos, updatedCells);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Id", id);
            if (owner != null) {
                tag.putUUID("Owner", owner);
            }
            tag.putInt("SlotX", slotX);
            tag.putInt("SlotZ", slotZ);
            if (colorIndex != FactoryColors.NO_COLOR) {
                tag.putInt("Color", colorIndex);
            }
            putEndpoint(tag, "Entrance", entranceDimension, entrancePos);

            ListTag cellsTag = new ListTag();
            for (Cell cell : cells) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putInt("X", cell.entrance().getX());
                cellTag.putInt("Y", cell.entrance().getY());
                cellTag.putInt("Z", cell.entrance().getZ());
                cellTag.putInt("RoomX", cell.roomX());
                cellTag.putInt("RoomZ", cell.roomZ());
                cellTag.putBoolean("FloorLaid", cell.floorLaid());
                cellsTag.add(cellTag);
            }
            tag.put("Cells", cellsTag);
            return tag;
        }

        private static FactoryRecord load(CompoundTag tag) {
            UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
            Endpoint entrance = readEndpoint(tag, "Entrance");
            int colorIndex = tag.contains("Color") ? tag.getInt("Color") : FactoryColors.NO_COLOR;

            List<Cell> cells = new ArrayList<>();
            ListTag cellsTag = tag.getList("Cells", Tag.TAG_COMPOUND);
            for (Tag entry : cellsTag) {
                CompoundTag cellTag = (CompoundTag) entry;
                cells.add(new Cell(
                        new BlockPos(cellTag.getInt("X"), cellTag.getInt("Y"), cellTag.getInt("Z")),
                        cellTag.getInt("RoomX"),
                        cellTag.getInt("RoomZ"),
                        // Cells saved before the floor was tracked count as laid: an older save is not
                        // repaved the first time a player walks back into it.
                        !cellTag.contains("FloorLaid") || cellTag.getBoolean("FloorLaid")
                ));
            }

            FactoryRecord record = new FactoryRecord(
                    tag.getInt("Id"),
                    owner,
                    colorIndex,
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
                        record.baseChunk().getMinBlockZ(),
                        true
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
