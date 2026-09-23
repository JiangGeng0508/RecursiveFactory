package com.zinzinc.recursivefactory.block.entity;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.data.FaceMode;
import com.zinzinc.recursivefactory.network.EndpointModePackets;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

public final class RecursiveFactoryBlockEntity extends EndpointBlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
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
    /** How many bits of {@link #faceModeMask} one face takes up: enough for five modes. */
    private static final int FACE_MODE_BITS = 3;
    private static final int FACE_MODE_MASK = (1 << FACE_MODE_BITS) - 1;
    private static final String FACE_MODES_TAG = "FaceModes";

    /**
     * What each face of this block is for, indexed by {@link Direction#get3DDataValue()}. A face is a
     * channel of its own: the room's north side is the north face of this block, and setting that face to
     * fluids makes the room's north side carry fluids and nothing else.
     */
    private final FaceMode[] faceModes = new FaceMode[Direction.values().length];

    private long lastRefreshTick = Long.MIN_VALUE;
    /** Whether the sides of the frame have been worked out yet, which a block from an older save needs. */
    private boolean connectionsChecked;
    private boolean hadAudience;

    public RecursiveFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECURSIVE_FACTORY.get(), pos, state);
        for (int face = 0; face < faceModes.length; face++) {
            faceModes[face] = FaceMode.TRANSPARENT;
        }
    }

    /**
     * What {@code face} of this block carries, see {@link FaceMode}. The six faces do not disturb each
     * other, so one side of a factory can be a fluid side while the next one carries items.
     */
    @Override
    public FaceMode faceMode(Direction face) {
        return faceModes[face.get3DDataValue()];
    }

    /**
     * Sets what {@code face} carries, answering whether that is a change. The change is drawn on the block,
     * so it is sent to whoever can see it; a face set to the mode it already had is left alone, which is
     * what keeps a click whose outcome the player cannot see from costing a packet.
     */
    public boolean setFaceMode(Direction face, FaceMode mode) {
        FaceMode previous = faceModes[face.get3DDataValue()];
        if (previous == mode) {
            return false;
        }
        faceModes[face.get3DDataValue()] = mode;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            PacketDistributor.sendToPlayersTrackingChunk(
                    serverLevel,
                    new ChunkPos(worldPosition),
                    new EndpointModePackets.Sync(worldPosition, faceModeMask())
            );
            // Both relays read the mode live, but neither would look at this face again on its own: the
            // redstone one only ever re-reads on a neighbour change, and Create only walks a block's
            // neighbours again when something about the block moves. A face that has just been given a
            // signal or a shaft is therefore looked at here, at once.
            FactoryRelay.onFaceModeChanged(this, face);
            if (previous == FaceMode.STRESS || mode == FaceMode.STRESS) {
                reapplyLink();
            }
        }
        return true;
    }

    /** Puts the modes a client was sent straight onto this block, without sending anything back. */
    public void applyFaceModes(int mask) {
        for (Direction face : Direction.values()) {
            faceModes[face.get3DDataValue()] = FaceMode.of(
                    mask >> (face.get3DDataValue() * FACE_MODE_BITS) & FACE_MODE_MASK
            );
        }
    }

    /** The six modes as one number, three bits per face in {@link Direction#get3DDataValue()} order. */
    public int faceModeMask() {
        int mask = 0;
        for (Direction face : Direction.values()) {
            mask |= faceMode(face).ordinal() << (face.get3DDataValue() * FACE_MODE_BITS);
        }
        return mask;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(FACE_MODES_TAG, faceModeMask());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        // A block from a save older than the modes, or one that has never been configured, is all windows.
        applyFaceModes(tag.contains(FACE_MODES_TAG) ? tag.getInt(FACE_MODES_TAG) : 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
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
        for (ServerPlayer player : serverLevel.players()) {
            if (player.blockPosition().closerThan(worldPosition, PREVIEW_RANGE)) {
                return true;
            }
        }
        return false;
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
            updatePreview(List.of(), List.of(), List.of());
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
                samplePreviewBlockEntities(factoryLevel, previewCenter, blocks)
        );
    }
}
