package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import com.zinzinc.recursivefactory.world.PreviewPlayerData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
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
    static final String FACTORY_ID_TAG = "FactoryId";
    private static final String COLOR_TAG = "Color";
    private static final String PENDING_STACK_TAG = "PendingStack";
    private static final String PENDING_INPUT_TAG = "PendingInput";
    private static final String PENDING_ITEMS_TAG = "PendingItems";
    private static final String PENDING_ITEM_TAG = "Item";
    private static final String PENDING_ITEM_FACE_TAG = "Face";
    private static final String PENDING_ITEM_SLOT_TAG = "Slot";
    /**
     * How many stacks the link holds on their way across. One slot per stack, each remembering the face
     * it came in through, so a mixed stream crosses instead of the second item having to wait for the
     * first (see {@link #pendingStacks}).
     */
    public static final int PENDING_SLOTS = 9;
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
    /** Neighbour data participates in model queries but must not be drawn a second time. */
    public static final String PREVIEW_CONTEXT_TAG = "RecursiveFactoryPreviewContext";
    /**
     * Tags that exist for physics only. Dropping them keeps the payload small and, more importantly, keeps
     * an entity that is standing still from looking changed: every one of these is rewritten every tick.
     */
    private static final List<String> VOLATILE_ENTITY_TAGS = List.of(
            "Motion", "FallDistance", "OnGround", "PortalCooldown", "Air", "Fire", "UUID"
    );
    /**
     * The name a sampled entity travels under. The client matches a sample to the entity it already draws
     * by this, so an entity that moves is moved onto the new sample instead of being built from scratch,
     * which is what lets its renderer interpolate between two snapshots (see FactoryProjectionCache).
     */
    public static final String PREVIEW_ID_TAG = "RecursiveFactoryPreviewId";

    private int factoryId = -1;
    /**
     * Which of the sixteen colour kinds this block is painted, or
     * {@link FactoryColors#NO_COLOR} for one that has no colour of its own and so falls back to the
     * colour its factory id hashes to. Set from the factory, so a room and the entrance blocks that
     * lead into it always come out the same colour.
     */
    private int colorIndex = FactoryColors.NO_COLOR;
    /**
     * Stacks on their way across the link, one slot each, together with the face each came in through.
     * Holding a single stack of a single item made a second kind of item - or the same item pushed in
     * through another face - wait for the first to leave, so a hopper feeding a mixed stream jammed; a
     * row of slots lets a mixed stream cross, and every slot still leaves by the side its own face picks.
     */
    private final NonNullList<ItemStack> pendingStacks = NonNullList.withSize(PENDING_SLOTS, ItemStack.EMPTY);
    private final Direction[] pendingInputs = new Direction[PENDING_SLOTS];
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
    private CompoundTag previewWires = new CompoundTag();
    private List<CompoundTag> previewBodies = List.of();
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
    /** What {@link KineticRelay} last put in the log about this end's link, so it only reports changes. */
    private String linkNote = "";
    /**
     * The faces of this block that answer to the outside machinery right now, one bit per
     * {@link Direction#get3DDataValue()}. An entrance block is one Create block and so has one speed, and
     * the faces that speed is handed out on are the ones the link is running through (see
     * {@link KineticRelay}): a face answers only while the wall of that same side of the room is the one
     * doing the driving, so machinery built against the other faces stays where it is. Not saved: the link
     * is worked out again on every tick.
     */
    private int outwardFaces;

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

    /** Whether {@code face} of this block answers to the outside machinery, see {@link KineticRelay}. */
    public boolean isOutwardFaceLive(Direction face) {
        return (outwardFaces & faceMask(face)) != 0;
    }

    /**
     * Sets which faces of this block answer to the outside machinery, and says whether that is a change.
     * Create remembers which neighbours a block is joined to, so a face that has just been opened or
     * closed has to be shown to it again - see {@link #reapplyLink}.
     */
    public boolean setOutwardFaces(int faces) {
        if (faces == outwardFaces) {
            return false;
        }
        outwardFaces = faces;
        return true;
    }

    /** The bit {@link #setOutwardFaces} uses for one face. */
    public static int faceMask(Direction face) {
        return 1 << face.get3DDataValue();
    }

    /**
     * Makes Create walk over this block's neighbours again. Opening or closing a face does not change this
     * block's own speed, so nothing else would make it look at those neighbours and notice the machinery
     * that has just been let in or let go of.
     */
    public void reapplyLink() {
        if (level == null || level.isClientSide) {
            return;
        }
        detachKinetics();
        attachKinetics();
    }

    /**
     * Forgets the link and lets this end go of the network it was turning with, without turning anything
     * by itself. Pulling a room's shell back apart into its six sides starts here (see
     * {@code FactoryDimension#separateShellSides}): the end comes out of the join stopped, and the next
     * look at the link puts it back on with the speed of the side it really belongs to.
     */
    public void forgetLink() {
        bridgeFresh = false;
        bridgeSpeed = 0;
        bridgeCapacity = 0;
        bridgeLoad = 0;
        removeSource();
    }

    /** The tick the link was last looked at, see KineticRelay. */
    public long getBridgeTick() {
        return bridgeTick;
    }

    /** The line the log has of this end's link, see {@link KineticRelay}. Not saved: only ever compared. */
    public String getLinkNote() {
        return linkNote;
    }

    public void setLinkNote(String linkNote) {
        this.linkNote = linkNote;
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

    /**
     * Drops the parts of an endpoint's saved data that belong to where the block stands rather than to
     * what the block is.
     *
     * <p>A copy of an endpoint block carries the block and how it was set up - an entrance block's six
     * face modes, say - but never the factory it happens to be wired into, the colour it was painted, the
     * items and redstone that were passing through it at the time, or the snapshot it was showing. A copy
     * is wired into a factory of its own and painted in that factory's colour (see {@code FactoryBlueprint}
     * and {@code FactoryDimension#linkEntrance}); carrying the original's id over instead would point the
     * copy at the factory it was copied from, and the two would be one factory rather than two.
     */
    public static void stripPlaceBoundTags(CompoundTag tag) {
        tag.remove(FACTORY_ID_TAG);
        tag.remove(COLOR_TAG);
        tag.remove(PENDING_STACK_TAG);
        tag.remove(PENDING_INPUT_TAG);
        tag.remove(PENDING_ITEMS_TAG);
        tag.remove(PENDING_FLUID_TAG);
        tag.remove(PENDING_FLUID_INPUT_TAG);
        tag.remove(INPUT_POWER_TAG);
        tag.remove(OUTPUT_POWER_TAG);
        tag.remove(LEGACY_INPUT_FACES_TAG);
        tag.remove(LEGACY_OUTPUT_FACES_TAG);
        tag.remove(LEGACY_OUTPUT_DIRECTION_TAG);
        tag.remove(LEGACY_REMOTE_POWERED_TAG);
        tag.remove(PREVIEW_BLOCKS_TAG);
        // A block that was put down out of a blueprint is a door for its factory for as long as it takes
        // that factory to be built, and never again: a copy of such a block is a copy of the block, not a
        // second factory built behind it (see RecursiveFactoryBlockEntity#blueprintFile).
        tag.remove(RecursiveFactoryBlockEntity.BLUEPRINT_TAG);
        // The save the block was taken in goes with it: a copy of an entrance block is a copy of the block,
        // and what that block leads into is the room the copy is printed into rather than a factory of
        // whatever save it happened to be copied out of (see RecursiveFactoryBlockEntity#blueprintOrigin).
        tag.remove(RecursiveFactoryBlockEntity.ORIGIN_TAG);
        // Which cell of that factory the block stood on goes with them: the copy stands in the room that
        // was built for it, not in the cell the block was read out of (see
        // RecursiveFactoryBlockEntity#roomCell).
        tag.remove(RecursiveFactoryBlockEntity.CELL_TAG);
    }

    public int getFactoryId() {
        return factoryId;
    }

    public void setFactoryId(int factoryId) {
        this.factoryId = factoryId;
        setChanged();
        // A block that has just joined a factory is drawn in that factory's colour, and the colour lives on
        // the block's own state (see FactoryColors), so the state is what tells the client - not the block
        // entity, whose data arrives on its own schedule.
        applyColorToState();
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
        applyColorToState();
    }

    /**
     * Writes this block's colour kind onto its state, which is what the client tints with. The state
     * travels with the block, so a block that has just been placed comes out in the right colour on its
     * first frame rather than turning from the plain colour into the factory's a frame or more later. A
     * state that already says the right thing is left alone, which keeps the repeated looks at a room free.
     */
    private void applyColorToState() {
        if (level == null || level.isClientSide() || !hasFactoryId()) {
            return;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(FactoryColors.COLOR_PROPERTY)) {
            return;
        }
        int wanted = FactoryColors.stateValue(FactoryColors.kindOfFactory(colorIndex, factoryId));
        if (state.getValue(FactoryColors.COLOR_PROPERTY) == wanted) {
            return;
        }
        level.setBlock(worldPosition, state.setValue(FactoryColors.COLOR_PROPERTY, wanted), Block.UPDATE_ALL);
    }

    /** Takes a stack through a named face into the one slot it names; only that slot is looked at. */
    public ItemStack offer(int slot, ItemStack stack, @Nullable Direction inputSide, boolean simulate) {
        if (stack.isEmpty() || !hasFactoryId() || inputSide == null
                || slot < 0 || slot >= PENDING_SLOTS) {
            return stack;
        }
        ItemStack pending = pendingStacks.get(slot);
        if (!pending.isEmpty()
                && (pendingInputs[slot] != inputSide || !ItemStack.isSameItemSameComponents(pending, stack))) {
            return stack;
        }
        return accept(slot, stack, inputSide, simulate);
    }

    /**
     * Takes a stack through a named face without naming a slot: a slot already carrying the same item
     * from the same face first, then a free one. A caller that does have a slot of its own - a hopper is
     * handed one per slot of this buffer - uses {@link #offer(int, ItemStack, Direction, boolean)}.
     */
    public ItemStack offer(ItemStack stack, @Nullable Direction inputSide) {
        return offer(stack, inputSide, false);
    }

    /** Simulation and execution share the same capacity, identity and input-face checks. */
    public ItemStack offer(ItemStack stack, @Nullable Direction inputSide, boolean simulate) {
        if (stack.isEmpty() || !hasFactoryId() || inputSide == null) {
            return stack;
        }
        ItemStack remainder = stack;
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < PENDING_SLOTS && !remainder.isEmpty(); slot++) {
                // Merge first, then use empty slots. A full matching stack must not hide free slots.
                if (pendingStacks.get(slot).isEmpty() == (pass == 1)) {
                    remainder = offer(slot, remainder, inputSide, simulate);
                }
            }
        }
        return remainder;
    }

    /** Puts as much of an already-vetted stack into its slot as fits, and answers the rest. */
    private ItemStack accept(int slot, ItemStack stack, Direction inputSide, boolean simulate) {
        ItemStack pending = pendingStacks.get(slot);
        int space = Math.min(64, stack.getMaxStackSize()) - pending.getCount();
        int accepted = Math.min(space, stack.getCount());
        if (accepted <= 0) {
            return stack;
        }
        if (!simulate) {
            if (pending.isEmpty()) {
                pendingStacks.set(slot, stack.copyWithCount(accepted));
            } else {
                pending.grow(accepted);
            }
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

    public ItemStack getPendingStack(int slot) {
        return slot < 0 || slot >= PENDING_SLOTS ? ItemStack.EMPTY : pendingStacks.get(slot);
    }

    public @Nullable Direction getPendingInput(int slot) {
        return slot < 0 || slot >= PENDING_SLOTS ? null : pendingInputs[slot];
    }

    /** True while anything at all is waiting to cross, which is when transport has work to do. */
    public boolean hasPendingItems() {
        for (ItemStack pending : pendingStacks) {
            if (!pending.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void setTransportResult(int slot, ItemStack remainder) {
        if (slot < 0 || slot >= PENDING_SLOTS) {
            return;
        }
        pendingStacks.set(slot, remainder.copy());
        if (remainder.isEmpty()) {
            pendingInputs[slot] = null;
        }
        setChanged();
    }

    /** The first slot, for callers whose items all come in through one face. */
    public void setTransportResult(ItemStack remainder) {
        setTransportResult(0, remainder);
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
        if (stack.isEmpty() || !hasFactoryId() || inputSide == null) {
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
     * The face this end is driving hardest, which is what the block state records. Only one face fits in the
     * state, so an end that emits on several at once reports the strongest of them (ties go to direction
     * order); a face it really emits on wins over the face of an input it is only passing on, so the answer
     * always points at a link that is live on this end. Nothing about the block is drawn from it any more -
     * the model does not change when a relay lights up - but the state turning over is how the neighbours of
     * a still lit end are told that the face it drives has moved (see {@code FactoryRelay.updateOutputState}).
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

    public CompoundTag getPreviewWires() {
        return previewWires;
    }

    public List<CompoundTag> getPreviewBodies() {
        return previewBodies;
    }

    public void acceptPreview(List<PreviewBlock> updatedPreview, List<CompoundTag> updatedEntities,
                              List<CompoundTag> updatedBlockEntities, CompoundTag updatedWires,
                              List<CompoundTag> updatedBodies) {
        previewBlocks = List.copyOf(updatedPreview);
        previewEntities = List.copyOf(updatedEntities);
        previewBlockEntities = List.copyOf(updatedBlockEntities);
        previewWires = updatedWires.copy();
        previewBodies = List.copyOf(updatedBodies);
    }

    public void refreshPreviewSnapshot() {
    }

    public void updatePreview(List<PreviewBlock> updatedPreview, List<CompoundTag> updatedEntities,
                              List<CompoundTag> updatedBlockEntities, CompoundTag updatedWires,
                              List<CompoundTag> updatedBodies) {
        if (previewBlocks.equals(updatedPreview)
                && previewEntities.equals(updatedEntities)
                && previewBlockEntities.equals(updatedBlockEntities) && previewWires.equals(updatedWires)
                && previewBodies.equals(updatedBodies)) {
            return;
        }
        boolean blocksChanged = !previewBlocks.equals(updatedPreview);
        previewBlocks = List.copyOf(updatedPreview);
        previewEntities = List.copyOf(updatedEntities);
        previewBlockEntities = List.copyOf(updatedBlockEntities);
        previewWires = updatedWires.copy();
        previewBodies = List.copyOf(updatedBodies);
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
                        writePreview(previewBlocks, previewEntities, previewBlockEntities, previewWires, previewBodies)
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
        return samplePreview(level, center, size, height, 0);
    }

    /** A one-block halo supplies connected textures, face culling and neighbouring block entity data. */
    public static List<PreviewBlock> samplePreviewWithContext(ServerLevel level, BlockPos center, int size, int height) {
        var blocks = new LinkedHashMap<BlockPos, PreviewBlock>();
        for (PreviewBlock block : samplePreview(level, center, size, height, 1)) {
            blocks.put(new BlockPos(block.x(), block.y(), block.z()), block);
        }
        // A belt's inventory lives only at its controller, possibly several cells away. Include that
        // controller as context so each preview can draw the items within its own cell.
        for (PreviewBlock block : List.copyOf(blocks.values())) {
            if (block.contextOnly() || !block.state().hasBlockEntity()) continue;
            BlockEntity be = level.getBlockEntity(center.offset(block.x(), block.y() - 1, block.z()));
            if (!(be instanceof BeltBlockEntity belt)) continue;
            BlockPos controller = belt.getController();
            if (!level.hasChunkAt(controller)
                    || !(level.getBlockEntity(controller) instanceof BeltBlockEntity controllerBE)
                    || !controllerBE.isController()) continue;
            BlockPos local = controller.subtract(center).above();
            blocks.putIfAbsent(local, new PreviewBlock(local.getX(), local.getY(), local.getZ(),
                    controllerBE.getBlockState(), true));
        }
        return List.copyOf(blocks.values());
    }

    private static List<PreviewBlock> samplePreview(ServerLevel level, BlockPos center, int size, int height,
                                                   int padding) {
        List<PreviewBlock> sampled = new ArrayList<>();
        int half = size / 2;
        for (int y = -padding; y < height + padding; y++) {
            for (int x = -half - padding; x < size - half + padding; x++) {
                for (int z = -half - padding; z < size - half + padding; z++) {
                    BlockPos targetPos = center.offset(x, y - 1, z);
                    if (!level.hasChunkAt(targetPos)) continue;
                    BlockState state = level.getBlockState(targetPos);
                    if (state.isAir() || state.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    boolean contextOnly = y < 0 || y >= height || x < -half || x >= size - half
                            || z < -half || z >= size - half;
                    sampled.add(new PreviewBlock(x, y, z, state, contextOnly));
                }
            }
        }
        return List.copyOf(sampled);
    }

    /**
     * Collects the entities inside the same box {@link #samplePreview} covers, as full NBT so that items,
     * named mobs and the like come back on the client the way they are. Positions are rebased on the centre
     * of the sample, the local frame the blocks use. Players use a public appearance snapshot and a
     * dedicated client proxy because their entity type cannot be rebuilt through EntityType.create.
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
        // Players have their own level list, including arrivals before chunk entity tracking catches up.
        // Sample every visible entity; a fixed count silently hid parts of busy rooms.
        List<Entity> entities = new ArrayList<>();
        for (Player player : level.players()) {
            if (isPreviewable(player) && player.getBoundingBox().intersects(box)) entities.add(player);
        }
        entities.addAll(level.getEntitiesOfClass(Entity.class, box,
                entity -> !(entity instanceof Player) && isPreviewable(entity)));
        for (Entity entity : entities) {
            CompoundTag tag = entity instanceof Player player ? PreviewPlayerData.sample(player) : new CompoundTag();
            if (!(entity instanceof Player)) entity.saveWithoutId(tag);
            // saveWithoutId deliberately leaves the type id out (its NBT is not meant to be recreated from),
            // and EntityType.create looks the type up by exactly this key, so it has to be put back in.
            tag.putString("id", EntityType.getKey(entity.getType()).toString());
            tag.putString(PREVIEW_ID_TAG, entity.getStringUUID());
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
        return !entity.isSpectator() && !entity.isRemoved();
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
            if (!block.state().hasBlockEntity()) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(center.offset(block.x(), block.y() - 1, block.z()));
            if (blockEntity == null) {
                continue;
            }
            CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
            if (blockEntity instanceof BeltBlockEntity belt) {
                tag.put("Controller", NbtUtils.writeBlockPos(belt.getController().subtract(center).above()));
            }
            tag.putInt("x", block.x());
            tag.putInt("y", block.y());
            tag.putInt("z", block.z());
            if (block.contextOnly()) tag.putBoolean(PREVIEW_CONTEXT_TAG, true);
            sampled.add(tag);
        }
        return List.copyOf(sampled);
    }

    private boolean cellLinksChecked;

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide) {
            if (!cellLinksChecked) {
                cellLinksChecked = true;
                KineticRelay.separateSavedLinks(this);
            }
            if (Math.floorMod(level.getGameTime() + worldPosition.asLong(), 20) == 0)
                FactoryRelay.refreshPower(this);
            KineticRelay.tick(this);
            FactoryRelay.transport(this);
            FactoryRelay.transportFluid(this);
        }
    }

    /** The slot an entry asked for, moved along if it is taken, so a hand-edited save still loads. */
    private int readPendingSlot(CompoundTag entry, int fallback) {
        int wanted = entry.contains(PENDING_ITEM_SLOT_TAG)
                ? Mth.clamp(entry.getInt(PENDING_ITEM_SLOT_TAG), 0, PENDING_SLOTS - 1)
                : Math.min(fallback, PENDING_SLOTS - 1);
        for (int step = 0; step < PENDING_SLOTS; step++) {
            int slot = (wanted + step) % PENDING_SLOTS;
            if (pendingStacks.get(slot).isEmpty()) {
                return slot;
            }
        }
        return wanted;
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
        ListTag pending = new ListTag();
        for (int slot = 0; slot < PENDING_SLOTS; slot++) {
            ItemStack stack = pendingStacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt(PENDING_ITEM_SLOT_TAG, slot);
            entry.put(PENDING_ITEM_TAG, stack.save(registries));
            Direction face = pendingInputs[slot];
            if (face != null) {
                entry.putString(PENDING_ITEM_FACE_TAG, face.getSerializedName());
            }
            pending.add(entry);
        }
        if (!pending.isEmpty()) {
            tag.put(PENDING_ITEMS_TAG, pending);
        }
        if (!pendingFluid.isEmpty()) {
            tag.put(PENDING_FLUID_TAG, pendingFluid.saveOptional(registries));
            if (pendingFluidInput != null) {
                tag.putString(PENDING_FLUID_INPUT_TAG, pendingFluidInput.getSerializedName());
            }
        }
        tag.putInt(INPUT_POWER_TAG, powerMask(inputPower));
        tag.putInt(OUTPUT_POWER_TAG, powerMask(outputPower));
        tag.put(PREVIEW_BLOCKS_TAG, writePreview(previewBlocks, previewEntities, previewBlockEntities, previewWires, previewBodies));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (!clientPacket) cellLinksChecked = false;
        // The link is worked out again on the next tick rather than carried over from the save, so a
        // saved speed is not mistaken for a generator of this end's own until the entrance block has
        // looked at it again.
        bridgeFresh = false;
        bridgeSpeed = 0;
        bridgeCapacity = 0;
        bridgeLoad = 0;
        outwardFaces = 0;
        factoryId = tag.contains(FACTORY_ID_TAG) ? tag.getInt(FACTORY_ID_TAG) : -1;
        colorIndex = tag.contains(COLOR_TAG) ? tag.getInt(COLOR_TAG) : FactoryColors.NO_COLOR;
        Arrays.fill(pendingInputs, null);
        pendingStacks.replaceAll(ignored -> ItemStack.EMPTY);
        if (tag.contains(PENDING_ITEMS_TAG)) {
            ListTag pending = tag.getList(PENDING_ITEMS_TAG, Tag.TAG_COMPOUND);
            for (int entry = 0; entry < pending.size(); entry++) {
                CompoundTag item = pending.getCompound(entry);
                ItemStack stack = ItemStack.parse(registries, item.getCompound(PENDING_ITEM_TAG))
                        .orElse(ItemStack.EMPTY);
                if (stack.isEmpty()) {
                    continue;
                }
                int slot = readPendingSlot(item, entry);
                pendingStacks.set(slot, stack);
                pendingInputs[slot] = item.contains(PENDING_ITEM_FACE_TAG)
                        ? Direction.byName(item.getString(PENDING_ITEM_FACE_TAG))
                        : null;
            }
        } else if (tag.contains(PENDING_STACK_TAG)) {
            // Saves from when the link held one stack: it becomes the first slot.
            pendingStacks.set(0, ItemStack.parse(registries, tag.getCompound(PENDING_STACK_TAG))
                    .orElse(ItemStack.EMPTY));
            pendingInputs[0] = tag.contains(PENDING_INPUT_TAG)
                    ? Direction.byName(tag.getString(PENDING_INPUT_TAG))
                    : null;
        }
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
        previewWires = readPreviewWires(tag);
        previewBodies = readPreviewBodies(tag);
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
                                           List<CompoundTag> blockEntities, CompoundTag wires) {
        return writePreview(blocks, entities, blockEntities, wires, List.of());
    }

    public static CompoundTag writePreview(List<PreviewBlock> blocks, List<CompoundTag> entities,
                                           List<CompoundTag> blockEntities, CompoundTag wires,
                                           List<CompoundTag> bodies) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (PreviewBlock previewBlock : blocks) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", previewBlock.x());
            entry.putInt("Y", previewBlock.y());
            entry.putInt("Z", previewBlock.z());
            entry.put("State", NbtUtils.writeBlockState(previewBlock.state()));
            if (previewBlock.contextOnly()) entry.putBoolean(PREVIEW_CONTEXT_TAG, true);
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
        if (!wires.isEmpty()) root.put("Wires", wires.copy());
        ListTag bodyList = new ListTag();
        for (CompoundTag body : bodies) bodyList.add(body.copy());
        if (!bodyList.isEmpty()) root.put("PhysicsBodies", bodyList);
        return root;
    }

    public static List<CompoundTag> readPreviewBodies(CompoundTag tag) {
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG) : tag;
        List<CompoundTag> bodies = new ArrayList<>();
        for (Tag entry : root.getList("PhysicsBodies", Tag.TAG_COMPOUND)) bodies.add(((CompoundTag) entry).copy());
        return List.copyOf(bodies);
    }

    public static CompoundTag readPreviewWires(CompoundTag tag) {
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG) : tag;
        return root.getCompound("Wires").copy();
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
                    state,
                    blockTag.getBoolean(PREVIEW_CONTEXT_TAG)
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

    public record PreviewBlock(int x, int y, int z, BlockState state, boolean contextOnly) {
        public PreviewBlock(int x, int y, int z, BlockState state) {
            this(x, y, z, state, false);
        }
    }
}
