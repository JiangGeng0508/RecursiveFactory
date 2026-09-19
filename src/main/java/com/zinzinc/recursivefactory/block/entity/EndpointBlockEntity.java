package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
    private static final String PREVIEW_BLOCKS_LIST_TAG = "Blocks";
    private static final String PREVIEW_ENTITIES_LIST_TAG = "Entities";
    private static final String PREVIEW_BLOCK_ENTITIES_LIST_TAG = "BlockEntities";
    /** Entities travel as full NBT, so a busy room would otherwise send an unbounded preview. */
    private static final int MAX_PREVIEW_ENTITIES = 24;
    /**
     * Block entities travel as NBT too, and some of them are big (a machine's inventory, a contraption),
     * so both how many are taken and how large a single one may be are capped.
     */
    private static final int MAX_PREVIEW_BLOCK_ENTITIES = 32;
    private static final int MAX_PREVIEW_BLOCK_ENTITY_BYTES = 16384;
    /**
     * Tags that exist for physics only. Dropping them keeps the payload small and, more importantly, keeps
     * an entity that is standing still from looking changed: every one of these is rewritten every tick.
     */
    private static final List<String> VOLATILE_ENTITY_TAGS = List.of(
            "Motion", "FallDistance", "OnGround", "PortalCooldown", "Air", "Fire", "UUID"
    );

    private int factoryId = -1;
    private ItemStack pendingStack = ItemStack.EMPTY;
    private @Nullable Direction pendingInput;
    private boolean localPowered;
    private boolean remotePowered;
    private @Nullable Direction outputDirection;
    /** The face {@link #noteBlockedTransport} last reported; see there. Not saved on purpose. */
    private @Nullable Direction lastBlockedFace;
    private List<PreviewBlock> previewBlocks = List.of();
    private List<CompoundTag> previewEntities = List.of();
    private List<CompoundTag> previewBlockEntities = List.of();

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
        if (stack.isEmpty() || !hasFactoryId()) {
            return stack;
        }

        if (!pendingStack.isEmpty()) {
            if (pendingInput != inputSide || !ItemStack.isSameItemSameComponents(pendingStack, stack)) {
                return stack;
            }

            int space = stack.getMaxStackSize() - pendingStack.getCount();
            int accepted = Math.min(space, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }

            pendingStack.grow(accepted);
            setChanged();
            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }

        pendingInput = inputSide;
        pendingStack = stack.copy();
        setChanged();
        return ItemStack.EMPTY;
    }

    public ItemStack getPendingStack() {
        return pendingStack;
    }

    public @Nullable Direction getPendingInput() {
        return pendingInput;
    }

    public void setTransportResult(ItemStack remainder) {
        pendingStack = remainder.copy();
        if (pendingStack.isEmpty()) {
            pendingInput = null;
        }
        setChanged();
    }

    /**
     * Remembers the face the last transport attempt found nothing to push into, so that a stack waiting to
     * get into the room (or back out of it) is reported once per face instead of on every one of its ticks.
     * Passing {@code null} clears the mark; the return value is true when the face is new, which is when the
     * wait is worth a line. Not saved: it only exists to keep the log readable.
     */
    public boolean noteBlockedTransport(@Nullable Direction face) {
        if (face == lastBlockedFace) {
            return false;
        }
        lastBlockedFace = face;
        return face != null;
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

    public List<CompoundTag> getPreviewEntities() {
        return previewEntities;
    }

    public List<CompoundTag> getPreviewBlockEntities() {
        return previewBlockEntities;
    }

    public void acceptPreview(List<PreviewBlock> updatedPreview, List<CompoundTag> updatedEntities,
                              List<CompoundTag> updatedBlockEntities) {
        previewBlocks = List.copyOf(updatedPreview);
        previewEntities = List.copyOf(updatedEntities);
        previewBlockEntities = List.copyOf(updatedBlockEntities);
    }

    public void refreshPreviewSnapshot() {
    }

    public void updatePreview(List<PreviewBlock> updatedPreview, List<CompoundTag> updatedEntities,
                              List<CompoundTag> updatedBlockEntities) {
        if (previewBlocks.equals(updatedPreview)
                && previewEntities.equals(updatedEntities)
                && previewBlockEntities.equals(updatedBlockEntities)) {
            return;
        }
        boolean blocksChanged = !previewBlocks.equals(updatedPreview);
        previewBlocks = List.copyOf(updatedPreview);
        previewEntities = List.copyOf(updatedEntities);
        previewBlockEntities = List.copyOf(updatedBlockEntities);
        setChanged();
        broadcastPreview(blocksChanged);
    }

    /**
     * Pushes the current preview to every client that has this block's chunk loaded. Vanilla's
     * sendBlockUpdated only reacts to block state changes, so block entity data has to be sent here.
     */
    protected void broadcastPreview(boolean blocksChanged) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // An entity that walks around changes the preview every tick; only a change to the blocks is worth a
        // line at info, the rest would flood the log. Both still go out on the wire.
        if (blocksChanged) {
            LOGGER.info(
                    "Broadcasting endpoint preview at {} with {} blocks, {} entities and {} block entities",
                    worldPosition,
                    previewBlocks.size(),
                    previewEntities.size(),
                    previewBlockEntities.size()
            );
        } else {
            LOGGER.debug(
                    "Broadcasting endpoint preview at {} with {} blocks, {} entities and {} block entities",
                    worldPosition,
                    previewBlocks.size(),
                    previewEntities.size(),
                    previewBlockEntities.size()
            );
        }
        PacketDistributor.sendToPlayersTrackingChunk(
                serverLevel,
                new ChunkPos(worldPosition),
                new EndpointPreviewPackets.Sync(
                        worldPosition,
                        writePreview(previewBlocks, previewEntities, previewBlockEntities)
                )
        );
    }

    /**
     * Samples a box of {@code size} columns square around {@code center}, {@code height} blocks tall and
     * starting with the block below the centre. Row {@code y} of the sample maps to
     * {@code center.offset(x, y - 1, z)}, so {@code y == 0} is the block under the centre and the box
     * grows upwards from there. The columns run from {@code -size / 2} to {@code size / 2 - 1}: for a box
     * one room cell across, centred on the block whose minimum corner sits on the middle of that cell,
     * that is exactly the cell, edge to edge. Air and bedrock are dropped; anything else that should stay
     * hidden (a room's barrier shell, for example) is the caller's to filter.
     */
    public static List<PreviewBlock> samplePreview(ServerLevel level, BlockPos center, int size, int height) {
        List<PreviewBlock> sampled = new ArrayList<>();
        int half = size / 2;
        for (int y = 0; y < height; y++) {
            for (int x = -half; x < size - half; x++) {
                for (int z = -half; z < size - half; z++) {
                    BlockPos targetPos = center.offset(x, y - 1, z);
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

    /**
     * Collects the entities inside the same box {@link #samplePreview} covers, as full NBT so that items,
     * named mobs and the like come back on the client the way they are. Positions are rebased on the centre
     * of the sample, the local frame the blocks use. Players are left out: their entity type refuses to be
     * rebuilt from NBT on the client, and the person looking at the preview is not its subject anyway.
     */
    public static List<CompoundTag> samplePreviewEntities(ServerLevel level, BlockPos center, int size, int height) {
        int half = size / 2;
        AABB box = new AABB(
                center.getX() - half,
                center.getY() - 1,
                center.getZ() - half,
                center.getX() - half + size,
                center.getY() - 1 + height,
                center.getZ() - half + size
        );
        List<CompoundTag> sampled = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, EndpointBlockEntity::isPreviewable)) {
            if (sampled.size() >= MAX_PREVIEW_ENTITIES) {
                break;
            }
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            // saveWithoutId deliberately leaves the type id out (its NBT is not meant to be recreated from),
            // and EntityType.create looks the type up by exactly this key, so it has to be put back in.
            tag.putString("id", EntityType.getKey(entity.getType()).toString());
            for (String volatileTag : VOLATILE_ENTITY_TAGS) {
                tag.remove(volatileTag);
            }
            tag.put("Pos", newDoubleList(
                    entity.getX() - center.getX(),
                    entity.getY() - center.getY() + 1,
                    entity.getZ() - center.getZ()
            ));
            sampled.add(tag);
        }
        return List.copyOf(sampled);
    }

    private static boolean isPreviewable(Entity entity) {
        return !(entity instanceof Player) && !entity.isSpectator() && !entity.isRemoved();
    }

    private static ListTag newDoubleList(double... values) {
        ListTag list = new ListTag();
        for (double value : values) {
            list.add(DoubleTag.valueOf(value));
        }
        return list;
    }

    /**
     * Collects the NBT of every block entity among the sampled blocks, so the client can build them again
     * and let their renderers draw: a chest holds items, a bell has a swinging part, a sign has text, and
     * none of that lives in the block state. The position in the tag is rebased on the sample centre like
     * everything else the preview carries. Blocks the caller already filtered out (the barrier shell) are
     * not looked at, so the shell's block entities never travel.
     */
    public static List<CompoundTag> samplePreviewBlockEntities(ServerLevel level, BlockPos center,
                                                              List<PreviewBlock> blocks) {
        List<CompoundTag> sampled = new ArrayList<>();
        for (PreviewBlock block : blocks) {
            if (sampled.size() >= MAX_PREVIEW_BLOCK_ENTITIES) {
                break;
            }
            if (!block.state().hasBlockEntity()) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(center.offset(block.x(), block.y() - 1, block.z()));
            if (blockEntity == null) {
                continue;
            }
            CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
            if (tag.sizeInBytes() > MAX_PREVIEW_BLOCK_ENTITY_BYTES) {
                LOGGER.debug("Skipping oversized block entity {} in the endpoint preview", blockEntity.getType());
                continue;
            }
            tag.putInt("x", block.x());
            tag.putInt("y", block.y());
            tag.putInt("z", block.z());
            sampled.add(tag);
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
        if (!pendingStack.isEmpty()) {
            tag.put(PENDING_STACK_TAG, pendingStack.save(registries));
            if (pendingInput != null) {
                tag.putString(PENDING_INPUT_TAG, pendingInput.getSerializedName());
            }
        }
        tag.putBoolean(LOCAL_POWERED_TAG, localPowered);
        tag.putBoolean(REMOTE_POWERED_TAG, remotePowered);
        if (outputDirection != null) {
            tag.putString(OUTPUT_DIRECTION_TAG, outputDirection.getSerializedName());
        }
        tag.put(PREVIEW_BLOCKS_TAG, writePreview(previewBlocks, previewEntities, previewBlockEntities));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        factoryId = tag.contains(FACTORY_ID_TAG) ? tag.getInt(FACTORY_ID_TAG) : -1;
        pendingStack = tag.contains(PENDING_STACK_TAG)
                ? ItemStack.parse(registries, tag.getCompound(PENDING_STACK_TAG)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        pendingInput = tag.contains(PENDING_INPUT_TAG)
                ? Direction.byName(tag.getString(PENDING_INPUT_TAG))
                : null;
        localPowered = tag.getBoolean(LOCAL_POWERED_TAG);
        remotePowered = tag.getBoolean(REMOTE_POWERED_TAG);
        outputDirection = tag.contains(OUTPUT_DIRECTION_TAG)
                ? Direction.byName(tag.getString(OUTPUT_DIRECTION_TAG))
                : null;
        previewBlocks = readPreviewBlocks(tag, registries);
        previewEntities = readPreviewEntities(tag);
        previewBlockEntities = readPreviewBlockEntities(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        loadAdditional(tag == null ? new CompoundTag() : tag, registries);
    }

    public static CompoundTag writePreview(List<PreviewBlock> blocks, List<CompoundTag> entities,
                                           List<CompoundTag> blockEntities) {
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
        root.put(PREVIEW_BLOCKS_LIST_TAG, list);
        ListTag entityList = new ListTag();
        for (CompoundTag entity : entities) {
            entityList.add(entity.copy());
        }
        root.put(PREVIEW_ENTITIES_LIST_TAG, entityList);
        ListTag blockEntityList = new ListTag();
        for (CompoundTag blockEntity : blockEntities) {
            blockEntityList.add(blockEntity.copy());
        }
        root.put(PREVIEW_BLOCK_ENTITIES_LIST_TAG, blockEntityList);
        return root;
    }

    public static List<PreviewBlock> readPreviewBlocks(CompoundTag tag, HolderLookup.Provider registries) {
        List<PreviewBlock> blocks = new ArrayList<>();
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG)
                : tag;
        ListTag list = root.getList(PREVIEW_BLOCKS_LIST_TAG, Tag.TAG_COMPOUND);
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

    public static List<CompoundTag> readPreviewEntities(CompoundTag tag) {
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG)
                : tag;
        ListTag list = root.getList(PREVIEW_ENTITIES_LIST_TAG, Tag.TAG_COMPOUND);
        List<CompoundTag> entities = new ArrayList<>();
        for (Tag entry : list) {
            entities.add(((CompoundTag) entry).copy());
        }
        return List.copyOf(entities);
    }

    public static List<CompoundTag> readPreviewBlockEntities(CompoundTag tag) {
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG)
                : tag;
        ListTag list = root.getList(PREVIEW_BLOCK_ENTITIES_LIST_TAG, Tag.TAG_COMPOUND);
        List<CompoundTag> blockEntities = new ArrayList<>();
        for (Tag entry : list) {
            blockEntities.add(((CompoundTag) entry).copy());
        }
        return List.copyOf(blockEntities);
    }

    public record PreviewBlock(int x, int y, int z, BlockState state) {
    }
}
