package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public abstract class EndpointBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FACTORY_ID_TAG = "FactoryId";
    private static final String PENDING_STACK_TAG = "PendingStack";
    private static final String PENDING_INPUT_TAG = "PendingInput";
    private static final String LOCAL_POWERED_TAG = "LocalPowered";
    private static final String REMOTE_POWERED_TAG = "RemotePowered";
    private static final String OUTPUT_DIRECTION_TAG = "OutputDirection";
    private static final String PREVIEW_BLOCKS_TAG = "PreviewBlocks";

    private int factoryId = -1;
    public static final int PENDING_SLOTS = 9;
    private final NonNullList<ItemStack> pendingStacks = NonNullList.withSize(PENDING_SLOTS, ItemStack.EMPTY);
    private final Direction[] pendingInputs = new Direction[PENDING_SLOTS];
    private boolean localPowered;
    private boolean remotePowered;
    private @Nullable Direction outputDirection;
    private List<PreviewBlock> previewBlocks = List.of();

    protected EndpointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    public boolean hasFactoryId() {
        return factoryId > 0;
    }

    public int getFactoryId() {
        return factoryId;
    }

    public void setFactoryId(int factoryId) {
        this.factoryId = factoryId;
        setChanged();
    }

    public ItemStack offer(ItemStack stack, @Nullable Direction inputSide) {
        return offer(stack, inputSide, false);
    }

    /** Merge matching stacks first; each buffered stack retains the face it entered through. */
    public ItemStack offer(ItemStack stack, @Nullable Direction inputSide, boolean simulate) {
        ItemStack remainder = stack;
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < PENDING_SLOTS && !remainder.isEmpty(); slot++) {
                if (pendingStacks.get(slot).isEmpty() == (pass == 1)) {
                    remainder = offer(slot, remainder, inputSide, simulate);
                }
            }
        }
        return remainder;
    }

    /** Simulation and execution use exactly the same capacity, item and face checks. */
    public ItemStack offer(int slot, ItemStack stack, @Nullable Direction inputSide, boolean simulate) {
        if (stack.isEmpty() || !hasFactoryId() || inputSide == null || slot < 0 || slot >= PENDING_SLOTS) {
            return stack;
        }
        ItemStack pending = pendingStacks.get(slot);
        if (!pending.isEmpty() && (pendingInputs[slot] != inputSide
                || !ItemStack.isSameItemSameComponents(pending, stack))) {
            return stack;
        }
        int accepted = Math.min(stack.getCount(), Math.min(64, stack.getMaxStackSize()) - pending.getCount());
        if (accepted <= 0) return stack;
        if (!simulate) {
            pendingStacks.set(slot, stack.copyWithCount(pending.getCount() + accepted));
            pendingInputs[slot] = inputSide;
            setChanged();
        }
        return stack.copyWithCount(stack.getCount() - accepted);
    }

    public ItemStack getPendingStack() {
        return getPendingStack(0);
    }

    public @Nullable Direction getPendingInput() {
        return getPendingInput(0);
    }

    public void setTransportResult(ItemStack remainder) {
        setTransportResult(0, remainder);
    }

    public ItemStack getPendingStack(int slot) {
        return slot < 0 || slot >= PENDING_SLOTS ? ItemStack.EMPTY : pendingStacks.get(slot);
    }

    public @Nullable Direction getPendingInput(int slot) {
        return slot < 0 || slot >= PENDING_SLOTS ? null : pendingInputs[slot];
    }

    public void setTransportResult(int slot, ItemStack remainder) {
        if (slot < 0 || slot >= PENDING_SLOTS || ItemStack.matches(pendingStacks.get(slot), remainder)) return;
        pendingStacks.set(slot, remainder.copy());
        if (remainder.isEmpty()) pendingInputs[slot] = null;
        setChanged();
    }

    public boolean isLocalPowered() {
        return localPowered;
    }

    public boolean isRemotePowered() {
        return remotePowered;
    }

    public boolean isOutputPowered() {
        return localPowered || remotePowered;
    }

    public void setLocalPowered(boolean powered) {
        if (localPowered != powered) {
            localPowered = powered;
            setChanged();
        }
    }

    public void setRemotePowered(boolean powered) {
        if (remotePowered != powered) {
            remotePowered = powered;
            setChanged();
        }
    }

    public @Nullable Direction getOutputDirection() {
        return outputDirection;
    }

    public void setOutputDirection(@Nullable Direction direction) {
        if (outputDirection != direction) {
            outputDirection = direction;
            setChanged();
        }
    }

    public List<PreviewBlock> getPreviewBlocks() {
        return previewBlocks;
    }

    public void acceptPreview(List<PreviewBlock> updatedPreview) {
        previewBlocks = List.copyOf(updatedPreview);
    }

    public void refreshPreviewSnapshot() {
    }

    public void updatePreview(List<PreviewBlock> updatedPreview) {
        if (previewBlocks.equals(updatedPreview)) {
            return;
        }
        previewBlocks = List.copyOf(updatedPreview);
        broadcastPreview();
    }

    /**
     * Pushes the current preview to every client that has this block's chunk loaded. Vanilla's
     * sendBlockUpdated only reacts to block state changes, so block entity data has to be sent here.
     */
    protected void broadcastPreview() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        LOGGER.info("Broadcasting endpoint preview at {} with {} blocks", worldPosition, previewBlocks.size());
        PacketDistributor.sendToPlayersTrackingChunk(
                serverLevel,
                new ChunkPos(worldPosition),
                new EndpointPreviewPackets.Sync(worldPosition, writePreviewBlocks(previewBlocks))
        );
    }

    public static List<PreviewBlock> samplePreview(ServerLevel level, BlockPos center, int radius, int height) {
        List<PreviewBlock> sampled = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    BlockPos targetPos = center.offset(x, y - 1, z);
                    if (!level.isLoaded(targetPos)) continue;
                    BlockState state = level.getBlockState(targetPos);
                    if (state.isAir() || state.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    sampled.add(new PreviewBlock(x, y, z, state));
                }
            }
        }
        return List.copyOf(sampled);
    }

    public void serverTick() {
        if (level != null && !level.isClientSide) {
            FactoryRelay.transport(this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (hasFactoryId()) {
            tag.putInt(FACTORY_ID_TAG, factoryId);
        }
        ListTag pending = new ListTag();
        for (int slot = 0; slot < PENDING_SLOTS; slot++) {
            if (!pendingStacks.get(slot).isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", slot);
                entry.put("Item", pendingStacks.get(slot).save(registries));
                if (pendingInputs[slot] != null) entry.putString("Face", pendingInputs[slot].getSerializedName());
                pending.add(entry);
            }
        }
        if (!pending.isEmpty()) tag.put("PendingItems", pending);
        tag.putBoolean(LOCAL_POWERED_TAG, localPowered);
        tag.putBoolean(REMOTE_POWERED_TAG, remotePowered);
        if (outputDirection != null) {
            tag.putString(OUTPUT_DIRECTION_TAG, outputDirection.getSerializedName());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        factoryId = tag.contains(FACTORY_ID_TAG) ? tag.getInt(FACTORY_ID_TAG) : -1;
        pendingStacks.replaceAll(ignored -> ItemStack.EMPTY);
        Arrays.fill(pendingInputs, null);
        if (tag.contains("PendingItems", Tag.TAG_LIST)) {
            for (Tag value : tag.getList("PendingItems", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) value;
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= PENDING_SLOTS || !pendingStacks.get(slot).isEmpty()) continue;
                pendingStacks.set(slot, ItemStack.parse(registries, entry.getCompound("Item")).orElse(ItemStack.EMPTY));
                pendingInputs[slot] = Direction.byName(entry.getString("Face"));
            }
        } else if (tag.contains(PENDING_STACK_TAG, Tag.TAG_COMPOUND)) {
            // The old single-stack buffer becomes slot zero without losing its routing face.
            pendingStacks.set(0, ItemStack.parse(registries, tag.getCompound(PENDING_STACK_TAG)).orElse(ItemStack.EMPTY));
            pendingInputs[0] = Direction.byName(tag.getString(PENDING_INPUT_TAG));
        }
        localPowered = tag.getBoolean(LOCAL_POWERED_TAG);
        remotePowered = tag.getBoolean(REMOTE_POWERED_TAG);
        outputDirection = tag.contains(OUTPUT_DIRECTION_TAG)
                ? Direction.byName(tag.getString(OUTPUT_DIRECTION_TAG))
                : null;
        // Snapshots embedded in old saves are stale; only network updates may populate the cache.
        previewBlocks = List.of();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.put(PREVIEW_BLOCKS_TAG, writePreviewBlocks(previewBlocks));
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        List<PreviewBlock> previous = previewBlocks;
        loadAdditional(tag, registries);
        previewBlocks = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? readPreviewBlocks(tag, registries) : previous;
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        handleUpdateTag(tag == null ? new CompoundTag() : tag, registries);
    }

    public static CompoundTag writePreviewBlocks(List<PreviewBlock> blocks) {
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

    public static List<PreviewBlock> readPreviewBlocks(CompoundTag tag, HolderLookup.Provider registries) {
        List<PreviewBlock> blocks = new ArrayList<>();
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG)
                : tag;
        ListTag list = root.getList("Blocks", Tag.TAG_COMPOUND);
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

    public record PreviewBlock(int x, int y, int z, BlockState state) {
    }
}
