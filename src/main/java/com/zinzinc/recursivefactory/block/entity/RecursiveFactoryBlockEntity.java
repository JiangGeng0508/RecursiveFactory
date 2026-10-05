package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.power.FactoryWires;
import com.zinzinc.recursivefactory.power.FactoryWireSchematics;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class RecursiveFactoryBlockEntity extends EndpointBlockEntity implements TransformableBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The blueprint file this block is the door of, written onto the block by the file it came out of and
     * read back the moment the block is put down. It is what turns the block into a factory's door: the
     * room, its contents and the factories standing inside it are built behind the block here and now,
     * rather than an empty factory being started. It is let go as soon as that is done, so a block that is
     * broken and put down again is an ordinary entrance block, and so a cannon that fires the same file at
     * a hundred places builds a hundred factories rather than one that is re-entered at every block.
     */
    public static final String BLUEPRINT_TAG = "FactoryRoom";
    /**
     * The save the blueprint a block was put down out of was taken in, written onto the block by the file
     * and read back with the block. It is what tells the factory the block stands for apart from a factory
     * of another save that happens to carry the same number (see FactoryData#origin): a block taken out of
     * this save is a door for the factory it names, one taken out of another save starts an empty factory
     * the way any entrance block does.
     */
    public static final String ORIGIN_TAG = "FactoryOrigin";
    /**
     * Which cell of the factory this block stands on, as the offset of that cell's room from the room of
     * the factory's anchor cell, in blocks and never anything but a whole number of cells. A snapshot of
     * the world carries the block and not the room it stands on - the room is in another dimension - so
     * what says where in the factory the block stood is this offset, and without it a door put down away
     * from the factory's anchor cell would build the factory around the wrong cell (see
     * FactoryBlueprint#copy).
     */
    public static final String CELL_TAG = "FactoryCell";
    /**
     * The other entrance blocks this block was copied with, as offsets from it in blocks. An expanded
     * factory is walked into through several entrance blocks, and those blocks are not part of the room -
     * they stand in the world, one per cell - so a copy of the factory carries them along and lays them
     * down beside the block it is placed as (see {@code RecursiveFactoryBlock#setPlacedBy}). Each offset
     * is a whole number of cells, and a position already taken by something else makes the placement fail.
     */
    public static final String DOORS_TAG = "FactoryDoors";
    public static final String ENTRANCE_NODES_TAG = "FactoryEntranceNodes";
    public static final String ENTRANCE_WIRES_TAG = "FactoryEntranceWires";
    public static final String PENDING_WIRES_TAG = "FactoryPendingEntranceWires";
    private CompoundTag entranceNodes = new CompoundTag();
    private CompoundTag entranceWires = new CompoundTag();
    private CompoundTag pendingEntranceWires = new CompoundTag();
    /**
     * One whole cell, edge to edge. The sixteen columns of the cell are the sixteen sixteenths of the
     * block's face, so the preview of one entrance block meets the preview of the entrance block next to
     * it exactly: nothing of either cell is cut off at the seam between them.
     */
    private static final int PREVIEW_SIZE = FactoryData.CELL_SIZE;
    /**
     * The whole room, base layer to ceiling. A room is as tall as it is wide, so a sample this far around
     * the cell's {@link FactoryData.FactoryRecord.Cell#previewCenter() preview centre} is the room's own
     * shell box: the shell takes the outer sixteenth of every direction, the free space fills the middle,
     * and the frame of the entrance block is where the shell ends up.
     */
    private static final int PREVIEW_HEIGHT = FactoryData.ROOM_HEIGHT;
    /**
     * How often the room is sampled again. Every tick, so the preview keeps up with what is built; the
     * sample is only taken while a player is near enough to see the preview, see {@link #PREVIEW_RANGE}.
     */
    private static final int REFRESH_INTERVAL = 1;
    /**
     * Sampling a whole cell costs a few thousand block lookups, so an entrance block nobody is looking at
     * stops sampling entirely. Walking up to it samples on that very tick, so the preview is never stale.
     */
    private static final double PREVIEW_RANGE = 160.0D;
    private long lastRefreshTick = Long.MIN_VALUE;
    /** Whether the sides of the frame have been worked out yet, which a block from an older save needs. */
    private boolean connectionsChecked;
    private boolean hadAudience;
    /**
     * The blueprint file this block was put down out of, for as long as the factory it names has not been
     * built behind it yet. It is both how a file reaches a block that is put down and how a block that is
     * carried about - in a Create cannon's shot, or as a blueprint read by Create's own tools - hands the
     * name on to the block that is finally placed, see {@link #writeSafe}.
     */
    @Nullable
    private String blueprintFile;
    /**
     * The save the blueprint this block was put down out of was taken in, for as long as the factory it
     * names has not been built behind it yet. It travels the same way the file name does, through
     * {@link #writeSafe}, and says the same sort of thing about the block: not what the block leads into,
     * but which factory it was copied from and where that factory is.
     */
    @Nullable
    private String blueprintOrigin;
    /**
     * The cell of the factory the block was copied out of, for as long as the factory it names has not
     * been built behind it yet. It travels the way the file name and the save's name do, through
     * {@link #writeSafe} and a snapshot of the world, and it is where the copy puts the cell this block
     * stood on (see {@link #CELL_TAG}).
     */
    @Nullable
    private BlockPos roomCell;
    private CompoundTag cannonRoom = new CompoundTag();

    public CompoundTag takeCannonRoom() {
        CompoundTag result = cannonRoom;
        cannonRoom = new CompoundTag();
        return result;
    }

    public RecursiveFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECURSIVE_FACTORY.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (RecursiveFactory.powerAvailable() && !pendingEntranceWires.isEmpty()
                && level.getGameTime() % 10 == 0
                && FactoryWireSchematics.restore(serverLevel, worldPosition, pendingEntranceWires)) {
            if (pendingEntranceWires.getList("Connections", Tag.TAG_COMPOUND).isEmpty())
                pendingEntranceWires = new CompoundTag();
            setChanged();
        }

        if (!connectionsChecked) {
            // A block from a save older than the joined sides carries none of them, and the frame the client
            // draws is worked out from them. The first tick of every entrance block settles the question once
            // and writes the answer onto the state, which also tells the clients looking at it to re-mesh.
            connectionsChecked = true;
            BlockState state = getBlockState();
            BlockState joined = RecursiveFactoryBlock.withConnections(state, serverLevel, worldPosition);
            if (joined != state) {
                LOGGER.debug("Entrance block at {}: the sides of the frame were missing, they read {} now",
                        worldPosition, joined);
                serverLevel.setBlock(worldPosition, joined, Block.UPDATE_ALL);
            }
        }

        if (!hasAudience(serverLevel)) {
            hadAudience = false;
            return;
        }

        long gameTime = serverLevel.getGameTime();
        if (hadAudience && gameTime - lastRefreshTick < REFRESH_INTERVAL) {
            return;
        }

        hadAudience = true;
        refreshPreviewSnapshot();
    }

    private boolean hasAudience(ServerLevel serverLevel) {
        var worldPos = SablePhysicsBodies.worldPosition(serverLevel, Vec3.atCenterOf(worldPosition));
        for (ServerPlayer player : serverLevel.players()) {
            if (player.position().distanceToSqr(worldPos) < PREVIEW_RANGE * PREVIEW_RANGE) {
                return true;
            }
        }
        return false;
    }

    /**
     * The blueprint file this block is the door of, or null for a block that starts a factory of its own -
     * which is every block but one that has just been put down out of a blueprint.
     */
    public @Nullable String blueprintFile() {
        return blueprintFile;
    }

    /**
     * The save the blueprint this block was put down out of was taken in, or null for a block that did not
     * come out of one - a block a player put down, and a block that came out of this save's own doorway to
     * Create's blueprint folder alike (see {@link #ORIGIN_TAG}).
     */
    public @Nullable String blueprintOrigin() {
        return blueprintOrigin;
    }

    /** The cell of the factory this block was copied out of, or null for a block that names none. */
    public @Nullable BlockPos roomCell() {
        return roomCell;
    }

    /** Real worlds are sampled now; schematic worlds carry the captured/transformed snapshot. */
    public CompoundTag blueprintEntranceNodes() {
        if (RecursiveFactory.powerAvailable() && level instanceof ServerLevel serverLevel) {
            // Saving an unloading chunk must not ask Level to load that same chunk again.
            var chunk = serverLevel.getChunkSource().getChunkNow(worldPosition.getX() >> 4, worldPosition.getZ() >> 4);
            if (chunk != null && chunk.getBlockEntity(worldPosition) == this)
                return FactoryWires.captureEntrance(serverLevel, worldPosition);
        } else if (RecursiveFactory.powerAvailable() && level != null && level.getBlockEntity(worldPosition) == this) {
            if (level.isClientSide) return com.zinzinc.recursivefactory.client.FactoryEntranceNodeCapture
                    .capture(level, worldPosition, entranceNodes);
        }
        return entranceNodes.copy();
    }

    public CompoundTag blueprintEntranceWires() {
        return entranceWires.copy();
    }

    /** Only placement restores a snapshot. Loading a world must never resurrect a removed terminal. */
    public void restoreBlueprintEntranceNodes() {
        if (RecursiveFactory.powerAvailable() && level instanceof ServerLevel serverLevel && !entranceNodes.isEmpty()) {
            FactoryWires.place(serverLevel, worldPosition, entranceNodes);
            LOGGER.info("Restored blueprint terminals on factory entrance at {}", worldPosition);
        }
        entranceNodes = new CompoundTag();
        pendingEntranceWires = entranceWires;
        entranceWires = new CompoundTag();
        setChanged();
    }

    @Override
    public void transform(BlockEntity blockEntity, StructureTransform transform) {
        if (!RecursiveFactory.powerAvailable()) return;
        for (Tag value : entranceNodes.getList("Nodes", Tag.TAG_COMPOUND)) {
            CompoundTag node = (CompoundTag) value;
            node.put("Position", FactoryWires.vector(transform.applyWithoutOffset(
                    FactoryWires.vector(node.getCompound("Position")))));
        }
        FactoryWireSchematics.transform(entranceWires, transform);
    }

    /** Drops what this block was a door of, once the factory it stands for is built behind it. */
    public void clearDoor() {
        if (blueprintFile != null || blueprintOrigin != null || roomCell != null) {
            blueprintFile = null;
            blueprintOrigin = null;
            roomCell = null;
            setChanged();
        }
    }

    /** Samples the middle of this entrance block's own cell, which is what its face preview shows. */
    public void refreshPreviewSnapshot() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // Stamped before anything can bail out, so the interval check never sees a stale, unset tick.
        lastRefreshTick = serverLevel.getGameTime();
        FactoryData data = serverLevel.getServer() == null ? null : FactoryData.get(serverLevel.getServer());
        FactoryData.FactoryRecord record = data == null || !hasFactoryId() ? null : data.factory(getFactoryId());
        ServerLevel factoryLevel = serverLevel.getServer() == null
                ? null
                : serverLevel.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        // Every entrance block shows its own cell. A factory that has grown covers several cells, and an
        // entrance block that is not the anchor would otherwise keep showing the anchor's cell however
        // much is built in its own one.
        FactoryData.FactoryRecord.Cell cell = record == null ? null : record.cellAt(worldPosition);
        if (cell == null && record != null) {
            cell = record.anchorCell();
        }
        if (factoryLevel == null || cell == null) {
            updatePreview(List.of(), List.of(), List.of(), new CompoundTag(), List.of());
            return;
        }

        // The barrier shell is not part of the preview: only the room's free space is drawn. Its block
        // entities are dropped with it, so the shell's own block entities never travel either.
        BlockPos previewCenter = cell.previewCenter();
        List<PreviewBlock> blocks = samplePreview(factoryLevel, previewCenter, PREVIEW_SIZE, PREVIEW_HEIGHT)
                .stream()
                .filter(block -> !block.state().is(ModBlocks.FACTORY_BARRIER.get()))
                .toList();
        updatePreview(
                blocks,
                samplePreviewEntities(factoryLevel, previewCenter, PREVIEW_SIZE, PREVIEW_HEIGHT),
                samplePreviewBlockEntities(factoryLevel, previewCenter, blocks),
                RecursiveFactory.powerAvailable()
                        ? FactoryWires.capture(factoryLevel, record, previewCenter.below())
                        : new CompoundTag(),
                SablePhysicsBodies.sample(factoryLevel, previewCenter, PREVIEW_SIZE, PREVIEW_HEIGHT)
        );
    }

    /**
     * Reads what this block was put down out of, which the file itself wrote onto the block. The block is a
     * factory's door for as long as it carries any of it, see {@link #BLUEPRINT_TAG} and {@link #ORIGIN_TAG}.
     */
    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        entranceNodes = tag.getCompound(ENTRANCE_NODES_TAG).copy();
        entranceWires = tag.getCompound(ENTRANCE_WIRES_TAG).copy();
        pendingEntranceWires = tag.getCompound(PENDING_WIRES_TAG).copy();
        cannonRoom = tag.getCompound("FactoryCannonRoom").copy();
        blueprintFile = tag.contains(BLUEPRINT_TAG) ? tag.getString(BLUEPRINT_TAG) : null;
        blueprintOrigin = tag.contains(ORIGIN_TAG) ? tag.getString(ORIGIN_TAG) : null;
        int[] cell = tag.getIntArray(CELL_TAG);
        // A snapshot taken before a factory could grow upwards carries the offset as a pair, on the base
        // layer; one taken since carries all three axes.
        roomCell = cell.length == 2 ? new BlockPos(cell[0], 0, cell[1])
                : cell.length == 3 ? new BlockPos(cell[0], cell[1], cell[2]) : null;
    }

    /**
     * Writes the blueprint file this block is still a door of, and the save it came out of. A block that has
     * been put down has built its factory and let both go, so this is only ever written for a block that is
     * being carried about - or read back with the factory it stands for, which is what a snapshot of the
     * world is written with: the factory a block leads into travels with it as a number, and that number is
     * only worth anything next to the save it was taken in.
     */
    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        CompoundTag nodes = blueprintEntranceNodes();
        if (!nodes.isEmpty()) tag.put(ENTRANCE_NODES_TAG, nodes);
        if (!entranceWires.isEmpty()) tag.put(ENTRANCE_WIRES_TAG, entranceWires.copy());
        if (!pendingEntranceWires.isEmpty()) tag.put(PENDING_WIRES_TAG, pendingEntranceWires.copy());
        if (blueprintFile != null) {
            tag.putString(BLUEPRINT_TAG, blueprintFile);
        }
        // What is written is this save's name, not the one the block was read with: a block that was copied
        // into this save leads into a factory of this save, and is taken out of it as one of its own.
        if (!clientPacket && hasFactoryId() && level instanceof ServerLevel serverLevel
                && serverLevel.getServer() != null) {
            FactoryData data = FactoryData.get(serverLevel.getServer());
            tag.putString(ORIGIN_TAG, data.origin().toString());
            // Which cell the block stands on, worked out from the factory it leads into rather than carried
            // along: that is what a snapshot of this block has to hold on to.
            FactoryData.FactoryRecord record = data.factory(getFactoryId());
            FactoryData.FactoryRecord.Cell anchor = record == null ? null : record.anchorCell();
            FactoryData.FactoryRecord.Cell cell = record == null ? null : record.cellAt(worldPosition);
            if (anchor != null && cell != null) {
                tag.putIntArray(CELL_TAG, new int[] {
                        cell.roomX() - anchor.roomX(),
                        cell.roomY() - anchor.roomY(),
                        cell.roomZ() - anchor.roomZ()
                });
            }
        }
    }

    /**
     * What Create carries of this block when it copies it into a blueprint. Create takes only what a block
     * entity itself says is safe to carry, and that leaves out every tag this mod writes - a blueprint of a
     * factory's door would be a blueprint of an entrance block with no factory behind it, which is exactly
     * what a cannon or a creative deploy used to build. What has to be carried is the name of the file the
     * block came out of, for a door that is still one, and the factory the block stands for together with
     * the save it was taken in, for a block that was copied out of the world - neither Create nor the block
     * that is put down in the end can work either out for itself.
     */
    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        CompoundTag nodes = blueprintEntranceNodes();
        if (!nodes.isEmpty()) tag.put(ENTRANCE_NODES_TAG, nodes);
        if (!entranceWires.isEmpty()) tag.put(ENTRANCE_WIRES_TAG, entranceWires.copy());
        if (blueprintFile != null) {
            tag.putString(BLUEPRINT_TAG, blueprintFile);
        }
        if (hasFactoryId()) {
            tag.putInt(FACTORY_ID_TAG, getFactoryId());
            if (blueprintOrigin != null) {
                tag.putString(ORIGIN_TAG, blueprintOrigin);
            }
            if (roomCell != null) {
                tag.putIntArray(CELL_TAG, new int[] {roomCell.getX(), roomCell.getY(), roomCell.getZ()});
            }
        }
    }
}
