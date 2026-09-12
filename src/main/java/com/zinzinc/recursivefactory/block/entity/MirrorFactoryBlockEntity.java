package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.world.FactoryData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MirrorFactoryBlockEntity extends EndpointBlockEntity {
    private static final String PREVIEW_BLOCKS_TAG = "ExternalPreview";
    private static final int PREVIEW_RADIUS = 4;
    private static final int PREVIEW_HEIGHT = 5;
    private static final int REFRESH_INTERVAL = 100;

    private List<PreviewBlock> previewBlocks = List.of();
    private long lastRefreshTick = Long.MIN_VALUE;

    public MirrorFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MIRROR_FACTORY.get(), pos, state);
    }

    @Override
    public List<PreviewBlock> getPreviewBlocks() {
        return previewBlocks;
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (level instanceof ServerLevel serverLevel && hasFactoryId()) {
            long gameTime = serverLevel.getGameTime();
            if (lastRefreshTick == Long.MIN_VALUE || gameTime - lastRefreshTick >= REFRESH_INTERVAL) {
                lastRefreshTick = gameTime;
                refreshExternalPreview(serverLevel);
            }
        }
    }

    private void refreshExternalPreview(ServerLevel factoryLevel) {
        if (factoryLevel.getServer() == null) {
            return;
        }

        FactoryData data = FactoryData.get(factoryLevel.getServer());
        FactoryData.FactoryRecord record = data.factory(getFactoryId());
        if (record == null || record.entranceDimension() == null) {
            updatePreview(List.of());
            return;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, record.entranceDimension());
        ServerLevel externalLevel = factoryLevel.getServer().getLevel(dimensionKey);
        if (externalLevel == null) {
            updatePreview(List.of());
            return;
        }

        List<PreviewBlock> sampled = new ArrayList<>();
        BlockPos center = record.entrancePos();
        for (int y = -1; y < PREVIEW_HEIGHT - 1; y++) {
            for (int x = -PREVIEW_RADIUS; x <= PREVIEW_RADIUS; x++) {
                for (int z = -PREVIEW_RADIUS; z <= PREVIEW_RADIUS; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    BlockPos targetPos = center.offset(x, y, z);
                    BlockState state = externalLevel.getBlockState(targetPos);
                    if (state.isAir() || state.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    sampled.add(new PreviewBlock(x, y, z, state));
                }
            }
        }
        updatePreview(sampled);
    }

    private void updatePreview(List<PreviewBlock> updatedPreview) {
        if (previewBlocks.equals(updatedPreview)) {
            return;
        }
        previewBlocks = List.copyOf(updatedPreview);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(PREVIEW_BLOCKS_TAG, writePreview(previewBlocks));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        previewBlocks = readPreview(tag, registries);
    }

    private static CompoundTag writePreview(List<PreviewBlock> blocks) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (PreviewBlock previewBlock : blocks) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", previewBlock.x());
            entry.putInt("Y", previewBlock.y());
            entry.putInt("Z", previewBlock.z());
            entry.put("State", NbtUtils.writeBlockState(previewBlock.state()));
            list.add(entry);
        }
        root.put("Blocks", list);
        return root;
    }

    private static List<PreviewBlock> readPreview(CompoundTag tag, HolderLookup.Provider registries) {
        List<PreviewBlock> blocks = new ArrayList<>();
        ListTag list = tag.getList(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND);
        for (Tag entry : list) {
            CompoundTag blockTag = (CompoundTag) entry;
            BlockState state = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK),
                    blockTag.getCompound("State")
            );
            blocks.add(new PreviewBlock(
                    blockTag.getInt("X"),
                    blockTag.getInt("Y"),
                    blockTag.getInt("Z"),
                    state
            ));
        }
        return List.copyOf(blocks);
    }
}
