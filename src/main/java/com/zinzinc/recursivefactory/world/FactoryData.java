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
    public static final int CEILING_Y = 80;

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
                BlockPos.ZERO
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

    public void bindEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null) {
            update(record.withEntrance(dimension, pos));
        }
    }

    public void clearEntrance(int factoryId, ResourceLocation dimension, BlockPos pos) {
        FactoryRecord record = factories.get(factoryId);
        if (record != null && dimension.equals(record.entranceDimension()) && pos.equals(record.entrancePos())) {
            update(record.withEntrance(null, BlockPos.ZERO));
        }
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
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (FactoryRecord record : factories.values()) {
            if (record.baseChunk().x == chunkX && record.baseChunk().z == chunkZ
                    && pos.getY() >= FLOOR_Y - 1 && pos.getY() <= CEILING_Y + 1) {
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
            @Nullable ResourceLocation mirrorDimension,
            BlockPos mirrorPos
    ) {
        public FactoryRecord {
            entrancePos = entrancePos.immutable();
            mirrorPos = mirrorPos.immutable();
        }

        public net.minecraft.world.level.ChunkPos baseChunk() {
            return new net.minecraft.world.level.ChunkPos(
                    (16 + slotX * 64) >> 4,
                    (16 + slotZ * 64) >> 4
            );
        }

        public FactoryRecord withEntrance(@Nullable ResourceLocation dimension, BlockPos pos) {
            return new FactoryRecord(id, owner, slotX, slotZ, dimension, pos, mirrorDimension, mirrorPos);
        }

        public FactoryRecord withMirror(@Nullable ResourceLocation dimension, BlockPos pos) {
            return new FactoryRecord(id, owner, slotX, slotZ, entranceDimension, entrancePos, dimension, pos);
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
            return tag;
        }

        private static FactoryRecord load(CompoundTag tag) {
            UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
            Endpoint entrance = readEndpoint(tag, "Entrance");
            Endpoint mirror = readEndpoint(tag, "Mirror");
            return new FactoryRecord(
                    tag.getInt("Id"),
                    owner,
                    tag.getInt("SlotX"),
                    tag.getInt("SlotZ"),
                    entrance.dimension(),
                    entrance.pos(),
                    mirror.dimension(),
                    mirror.pos()
            );
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
