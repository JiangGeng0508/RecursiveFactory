package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public abstract class EndpointBlockEntity extends GeneratingKineticBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FACTORY_ID_TAG = "FactoryId";
    private static final String COLOR_TAG = "Color";
    private static final String PENDING_STACK_TAG = "PendingStack";
    private static final String PENDING_INPUT_TAG = "PendingInput";
    private static final String PENDING_FLUID_TAG = "PendingFluid";
    private static final String PENDING_FLUID_INPUT_TAG = "PendingFluidInput";
    private static final int FACE_COUNT = Direction.values().length;
    private static final int MAX_POWER = 15;
    /**
     * How much fluid one end holds between two ticks, in mB. It is drained into the far end on every
     * tick, so the figure is a throughput rather than a store - Create's pipes move a bucket or two a
     * tick - and it is what stops one push from being thrown away when the far end is momentarily full.
     */
    public static final int FLUID_CAPACITY = 8000;
    private static final int POWER_BITS = 4;
    private static final String INPUT_POWER_TAG = "InputPower";
    private static final String OUTPUT_POWER_TAG = "OutputPower";
    /** Saves from when a link stored which faces were live instead of how strong each of them is. */
    private static final String LEGACY_INPUT_FACES_TAG = "InputFaces";
    private static final String LEGACY_OUTPUT_FACES_TAG = "OutputFaces";
    /** Saves from when a link could only ever drive one face, and only ever at full strength. */
    private static final String LEGACY_OUTPUT_DIRECTION_TAG = "OutputDirection";
    private static final String LEGACY_REMOTE_POWERED_TAG = "RemotePowered";
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
    /**
     * Which of the sixteen colour kinds this block is painted, or
     * {@link FactoryColors#NO_COLOR} for one that has no colour of its own and so falls back to the
     * colour its factory id hashes to. Set from the factory, so a room and the entrance blocks that
     * lead into it always come out the same colour.
     */
    private int colorIndex = FactoryColors.NO_COLOR;
    private ItemStack pendingStack = ItemStack.EMPTY;
    private @Nullable Direction pendingInput;
    private FluidStack pendingFluid = FluidStack.EMPTY;
    private @Nullable Direction pendingFluidInput;
    /**
     * Strength fed into each face, 0-15, indexed by {@link Direction#ordinal()}. Several faces can be fed
     * at once - an entrance block wired from two sides drives both of the room's walls - and every face
     * keeps its own strength, the way each dust block in a line keeps the level it was fed with.
     */
    private final int[] inputPower = new int[FACE_COUNT];
    /** Strength emitted from each face, 0-15: what the far end of the link drives this end with. */
    private final int[] outputPower = new int[FACE_COUNT];
    /** The face {@link #noteBlockedTransport} last reported; see there. Not saved on purpose. */
    private @Nullable Direction lastBlockedFace;
    /** The face {@link #noteBlockedFluidTransport} last reported; see {@link #noteBlockedTransport}. */
    private @Nullable Direction lastBlockedFluidFace;
    private List<PreviewBlock> previewBlocks = List.of();
    private List<CompoundTag> previewEntities = List.of();
    private List<CompoundTag> previewBlockEntities = List.of();
    /** The speed the far end of the link turns this end at, 0 while the link is not turning it. */
    private float bridgeSpeed;
    /** The stress capacity this end hands to its own network while the link is turning it. */
    private float bridgeCapacity;
    /** The stress the far end's network is asking for, put on this end's network while it drives the link. */
    private float bridgeLoad;
    /** The tick the link was last looked at, so an end whose entrance block went away can let go of it. */
    private long bridgeTick = Long.MIN_VALUE;
    /** False until the link has been worked out once, so a save is followed by a fresh look at it. */
    private boolean bridgeFresh;

    protected EndpointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    /**
     * What the link between this end and the far end is carrying right now, handed over by
     * {@link KineticRelay}: the speed to turn this end at, the stress capacity to hand to its own
     * network, and - when it is this end that drives the link - the stress the far end is asking for,
     * which is what makes both ends stall together once the shared capacity runs out.
     */
    public void setBridge(float speed, float capacity, float load) {
        if (level == null || level.isClientSide) {
            return;
        }
        bridgeTick = level.getGameTime();
        if (bridgeFresh && speed == bridgeSpeed && capacity == bridgeCapacity && load == bridgeLoad) {
            return;
        }
        bridgeFresh = true;
        bridgeSpeed = speed;
        bridgeCapacity = capacity;
        bridgeLoad = load;
        LOGGER.debug("Endpoint link at {} carries speed {}, capacity {}, load {}", worldPosition, speed,
                capacity, load);
        // Turns this end into a generator of the speed the far end runs at, joining its own local network
        // or leaving it, and telling that network what it may ask for.
        updateGeneratedRotation();
        KineticNetwork network = getOrCreateNetwork();
        if (network != null) {
            network.updateStressFor(this, calculateStressApplied());
        }
    }

    /** The tick the link was last looked at, see KineticRelay. */
    public long getBridgeTick() {
        return bridgeTick;
    }

    @Override
    public float getGeneratedSpeed() {
        return bridgeSpeed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = bridgeCapacity;
        return bridgeCapacity;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = bridgeLoad;
        return bridgeLoad;
    }

    /**
     * Nothing is sent to the clients about this end's rotation: a room is walled with thousands of these
     * and none of them draw their own turning, so the speed and stress Create would broadcast with every
     * overstress change would be a packet per wall block. The block's own state and the preview are sent
     * by the code that changes them.
     */
    @Override
    public void sendData() {
    }

    /** Kinetic sound is left to the machines rather than to the wall they stand in. */
    @Override
    protected boolean isNoisy() {
        return false;
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
        // The client tints a barrier with its factory's colour, so it has to be told the id even when the
        // barrier is placed after the chunk it sits in was sent.
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public boolean hasColorIndex() {
        return colorIndex != FactoryColors.NO_COLOR;
    }

    public int getColorIndex() {
        return colorIndex;
    }

    /** Paints this block; the client tints it with the colour kind, see FactoryColors. */
    public void setColorIndex(int colorIndex) {
        if (this.colorIndex == colorIndex) {
            return;
        }
        this.colorIndex = colorIndex;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
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
     * Takes fluid into this end's buffer and answers how much of it was accepted, which is the bargain
     * {@link #offer} makes for items: a pipe pushing into a wall block is held up exactly as far as the far
     * end of the link is full, rather than the fluid being lost. Fluid already waiting keeps its face, so a
     * second pipe feeding from another side cannot send it out of the wrong side of the room.
     */
    public int offerFluid(FluidStack stack, @Nullable Direction inputSide) {
        int accepted = roomForFluid(stack, inputSide);
        if (accepted <= 0) {
            return 0;
        }
        if (pendingFluid.isEmpty()) {
            pendingFluid = stack.copyWithAmount(accepted);
            pendingFluidInput = inputSide;
        } else {
            pendingFluid.grow(accepted);
        }
        setChanged();
        return accepted;
    }

    /**
     * How much of {@code stack} a push from {@code inputSide} would be accepted right now: none while the
     * buffer holds another fluid, or fluid that came in through another side - that fluid has to leave by
     * the far end of its own link first, or it would come out of the wrong side of the room.
     */
    public int roomForFluid(FluidStack stack, @Nullable Direction inputSide) {
        if (stack.isEmpty() || !hasFactoryId()) {
            return 0;
        }
        if (!pendingFluid.isEmpty()
                && (pendingFluidInput != inputSide || !FluidStack.isSameFluidSameComponents(pendingFluid, stack))) {
            return 0;
        }
        return Math.min(FLUID_CAPACITY - pendingFluid.getAmount(), stack.getAmount());
    }

    public FluidStack getPendingFluid() {
        return pendingFluid;
    }

    public @Nullable Direction getPendingFluidInput() {
        return pendingFluidInput;
    }

    /** Bookkeeping after a push into the far end took {@code accepted} mB out of the buffer. */
    public void setFluidTransportResult(int accepted) {
        if (accepted <= 0 || pendingFluid.isEmpty()) {
            return;
        }
        pendingFluid.shrink(accepted);
        if (pendingFluid.getAmount() <= 0) {
            pendingFluid = FluidStack.EMPTY;
            pendingFluidInput = null;
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

    /** The fluid that could not be pushed on; see {@link #noteBlockedTransport}. */
    public boolean noteBlockedFluidTransport(@Nullable Direction face) {
        if (face == lastBlockedFluidFace) {
            return false;
        }
        lastBlockedFluidFace = face;
        return face != null;
    }

    public boolean isLocalPowered() {
        return anyPower(inputPower);
    }

    public boolean isRemotePowered() {
        return anyPower(outputPower);
    }

    public boolean isOutputPowered() {
        return isLocalPowered() || isRemotePowered();
    }

    private static boolean anyPower(int[] powers) {
        for (int power : powers) {
            if (power > 0) {
                return true;
            }
        }
        return false;
    }

    /** The strength fed into {@code face} right now, 0-15. */
    public int getInputPower(Direction face) {
        return inputPower[face.ordinal()];
    }

    public int[] getInputPowers() {
        return inputPower.clone();
    }

    public void setInputPowers(int[] powers) {
        if (Arrays.equals(inputPower, powers)) {
            return;
        }
        System.arraycopy(powers, 0, inputPower, 0, FACE_COUNT);
        setChanged();
    }

    /** The strength this end emits on {@code face} right now, 0-15. */
    public int getOutputPower(Direction face) {
        return outputPower[face.ordinal()];
    }

    public int[] getOutputPowers() {
        return outputPower.clone();
    }

    /** What the far end of a link drives this face with. Zero turns that face's link off again. */
    public void setOutputPower(Direction face, int power) {
        int clamped = Mth.clamp(power, 0, MAX_POWER);
        if (outputPower[face.ordinal()] != clamped) {
            outputPower[face.ordinal()] = clamped;
            setChanged();
        }
    }

    /**
     * The face the block model should mark. Only one face fits in the block state, so an end that emits
     * on several at once marks the strongest of them (ties go to direction order). A face this end really
     * emits on wins over the face of an input it is only passing on, which is what would otherwise be
     * marked, so the marker always points at a link that is live on this end.
     */
    public @Nullable Direction markerFace() {
        Direction marked = null;
        int strongest = 0;
        for (Direction direction : Direction.values()) {
            int power = outputPower[direction.ordinal()];
            if (power > strongest) {
                marked = direction;
                strongest = power;
            }
        }
        if (marked != null) {
            return marked;
        }
        for (Direction direction : Direction.values()) {
            int power = inputPower[direction.ordinal()];
            if (power > strongest) {
                marked = direction.getOpposite();
                strongest = power;
            }
        }
        return marked;
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

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide) {
            KineticRelay.tick(this);
            FactoryRelay.transport(this);
            FactoryRelay.transportFluid(this);
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (hasFactoryId()) {
            tag.putInt(FACTORY_ID_TAG, factoryId);
        }
        if (hasColorIndex()) {
            tag.putInt(COLOR_TAG, colorIndex);
        }
        if (!pendingStack.isEmpty()) {
            tag.put(PENDING_STACK_TAG, pendingStack.save(registries));
            if (pendingInput != null) {
                tag.putString(PENDING_INPUT_TAG, pendingInput.getSerializedName());
            }
        }
        if (!pendingFluid.isEmpty()) {
            tag.put(PENDING_FLUID_TAG, pendingFluid.saveOptional(registries));
            if (pendingFluidInput != null) {
                tag.putString(PENDING_FLUID_INPUT_TAG, pendingFluidInput.getSerializedName());
            }
        }
        tag.putInt(INPUT_POWER_TAG, powerMask(inputPower));
        tag.putInt(OUTPUT_POWER_TAG, powerMask(outputPower));
        tag.put(PREVIEW_BLOCKS_TAG, writePreview(previewBlocks, previewEntities, previewBlockEntities));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        // The link is worked out again on the next tick rather than carried over from the save, so a
        // saved speed is not mistaken for a generator of this end's own until the entrance block has
        // looked at it again.
        bridgeFresh = false;
        bridgeSpeed = 0;
        bridgeCapacity = 0;
        bridgeLoad = 0;
        factoryId = tag.contains(FACTORY_ID_TAG) ? tag.getInt(FACTORY_ID_TAG) : -1;
        colorIndex = tag.contains(COLOR_TAG) ? tag.getInt(COLOR_TAG) : FactoryColors.NO_COLOR;
        pendingStack = tag.contains(PENDING_STACK_TAG)
                ? ItemStack.parse(registries, tag.getCompound(PENDING_STACK_TAG)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        pendingInput = tag.contains(PENDING_INPUT_TAG)
                ? Direction.byName(tag.getString(PENDING_INPUT_TAG))
                : null;
        pendingFluid = FluidStack.parseOptional(registries, tag.getCompound(PENDING_FLUID_TAG));
        pendingFluidInput = tag.contains(PENDING_FLUID_INPUT_TAG)
                ? Direction.byName(tag.getString(PENDING_FLUID_INPUT_TAG))
                : null;
        Arrays.fill(inputPower, 0);
        Arrays.fill(outputPower, 0);
        if (tag.contains(INPUT_POWER_TAG) || tag.contains(OUTPUT_POWER_TAG)) {
            readPowerMask(tag.getInt(INPUT_POWER_TAG), inputPower);
            readPowerMask(tag.getInt(OUTPUT_POWER_TAG), outputPower);
        } else if (tag.contains(LEGACY_INPUT_FACES_TAG) || tag.contains(LEGACY_OUTPUT_FACES_TAG)) {
            readFaceMaskAsPower(tag.getInt(LEGACY_INPUT_FACES_TAG), inputPower);
            readFaceMaskAsPower(tag.getInt(LEGACY_OUTPUT_FACES_TAG), outputPower);
        } else if (tag.contains(LEGACY_OUTPUT_DIRECTION_TAG, Tag.TAG_STRING)) {
            Direction legacy = Direction.byName(tag.getString(LEGACY_OUTPUT_DIRECTION_TAG));
            if (legacy != null) {
                if (tag.getBoolean(LEGACY_REMOTE_POWERED_TAG)) {
                    outputPower[legacy.ordinal()] = MAX_POWER;
                } else {
                    inputPower[legacy.getOpposite().ordinal()] = MAX_POWER;
                }
            }
        }
        previewBlocks = readPreviewBlocks(tag, registries);
        previewEntities = readPreviewEntities(tag);
        previewBlockEntities = readPreviewBlockEntities(tag);
    }

    /** Four bits per face is all a strength needs, so both tables fit in one int each. */
    private static int powerMask(int[] powers) {
        int mask = 0;
        for (Direction face : Direction.values()) {
            mask |= (powers[face.ordinal()] & MAX_POWER) << (face.get3DDataValue() * POWER_BITS);
        }
        return mask;
    }

    private static void readPowerMask(int mask, int[] powers) {
        for (Direction face : Direction.values()) {
            powers[face.ordinal()] = (mask >>> (face.get3DDataValue() * POWER_BITS)) & MAX_POWER;
        }
    }

    /** A live face from an older save carries no strength: it was full power back then. */
    private static void readFaceMaskAsPower(int mask, int[] powers) {
        for (Direction face : Direction.values()) {
            if ((mask & (1 << face.get3DDataValue())) != 0) {
                powers[face.ordinal()] = MAX_POWER;
            }
        }
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
