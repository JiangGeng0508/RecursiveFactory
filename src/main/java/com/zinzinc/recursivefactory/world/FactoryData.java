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
import net.minecraft.world.level.saveddata.SavedData;

public final class FactoryData extends SavedData {
    public static final String DATA_NAME = RecursiveFactory.MODID + "_factories";
    public static final int FLOOR_Y = 64;
    public static final int CEILING_Y = 96;
    /**
     * Room heights for the two entrance block variants. Has to match what FactoryPortal derives for the
     * room side planes: the doorway height times the portal scale.
     */
    public static final int TALL_ROOM_HEIGHT = CEILING_Y - FLOOR_Y;
    public static final int SHORT_ROOM_HEIGHT = TALL_ROOM_HEIGHT / 2;
    /**
     * How far apart neighbouring factory rooms sit, in blocks. Thirty two chunks keeps one room's portals
     * and forced chunk well clear of the next one's.
     *
     * <p>Room positions are derived from this at run time rather than saved per factory, so changing it
     * moves every existing factory to a fresh platform and leaves whatever was built in the old rooms
     * behind at the old coordinates.
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
                null,
                BlockPos.ZERO,
                List.of()
        );
        factories.put(factoryId, record);
        setDirty();
        return record;
    }

    public FactoryRecord update(FactoryRecord record) {
        factories.put(record.id(), record);
        setDirty();
        return record;
    }

    /** Binds a factory's first entrance cell: the room cell it stands for is its slot's chunk. */
    public void bindEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null) {
            FactoryRecord.Cell cell = new FactoryRecord.Cell(
                    pos,
                    record.baseChunk().getMinBlockX(),
                    record.baseChunk().getMinBlockZ()
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

    public void bindMirror(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null) {
            update(record.withMirror(dimension, pos));
        }
    }

    public void clearMirror(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null && dimension.equals(record.mirrorDimension()) && pos.equals(record.mirrorPos())) {
            update(record.withMirror(null, BlockPos.ZERO));
        }
    }

    @Nullable
    public FactoryRecord factoryAt(BlockPos pos) {
        for (FactoryRecord record : factories.values()) {
            for (FactoryRecord.Cell cell : record.cells()) {
                if (pos.getX() >= cell.roomX() && pos.getX() < cell.roomX() + 16
                        && pos.getZ() >= cell.roomZ() && pos.getZ() < cell.roomZ() + 16
                        && pos.getY() >= FLOOR_Y - 1 && pos.getY() <= CEILING_Y + 1) {
                    return record;
                }
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
            @Nullable ResourceLocation mirrorDimension,
            BlockPos mirrorPos,
            List<Cell> cells
    ) {
        /**
         * One entrance block and the room cell it stands for. The room origin is saved per cell so the
         * room layout never shifts when cells are removed; the layout as a whole is the entrance layout,
         * translated, with each cell sixteen blocks across.
         */
        public record Cell(BlockPos entrance, int roomX, int roomZ) {
        }

        public FactoryRecord {
            entrancePos = entrancePos.immutable();
            mirrorPos = mirrorPos.immutable();
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

        public net.minecraft.world.level.ChunkPos baseChunk() {
            return new net.minecraft.world.level.ChunkPos(
                    (16 + slotX * SLOT_SPACING_BLOCKS) >> 4,
                    (16 + slotZ * SLOT_SPACING_BLOCKS) >> 4
            );
        }

        public FactoryRecord withEntrance(@Nullable ResourceLocation dimension, BlockPos pos) {
            return new FactoryRecord(id, owner, slotX, slotZ, dimension, pos, mirrorDimension, mirrorPos, cells);
        }

        public FactoryRecord withMirror(@Nullable ResourceLocation dimension, BlockPos pos) {
            return new FactoryRecord(id, owner, slotX, slotZ, entranceDimension, entrancePos, dimension, pos, cells);
        }

        public FactoryRecord withCells(List<Cell> updatedCells) {
            return new FactoryRecord(id, owner, slotX, slotZ, entranceDimension, entrancePos,
                    mirrorDimension, mirrorPos, updatedCells);
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
            putEndpoint(tag, "Mirror", mirrorDimension, mirrorPos);
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
            Endpoint mirror = readEndpoint(tag, "Mirror");
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
                    mirror.dimension(),
                    mirror.pos(),
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
