package com.zinzinc.recursivefactory.preview;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.zinzinc.recursivefactory.world.PreviewPlayerData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity.PreviewBlock;
import static com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity.*;

public final class PreviewSampler {
    private static final String PREVIEW_BLOCKS_LIST_TAG = "Blocks";
    private static final String PREVIEW_ENTITIES_LIST_TAG = "Entities";
    private static final String PREVIEW_BLOCK_ENTITIES_LIST_TAG = "BlockEntities";
    private static final List<String> VOLATILE_ENTITY_TAGS = List.of(
            "Motion", "FallDistance", "OnGround", "PortalCooldown", "Air", "Fire", "UUID");
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
            CompoundTag tag = PreviewDisplayData.sample(blockEntity);
            if (blockEntity instanceof EndpointBlockEntity endpoint) {
                // Nested entrances still render their contents, but only the preview transport carries
                // this cache. Ordinary saves, schematics and debug NBT queries must not include it.
                if (!block.contextOnly() && PreviewRecursion.allows(endpoint)) {
                    if (endpoint instanceof RecursiveFactoryBlockEntity entrance) {
                        if (PreviewScheduler.sampling()) PreviewScheduler.nested(entrance);
                        else entrance.ensurePreviewSnapshot();
                    }
                    CompoundTag nested = endpoint.previewTag();
                    var ancestors = new java.util.HashSet<Integer>();
                    ancestors.add(endpoint.getFactoryId());
                    var parent = com.zinzinc.recursivefactory.world.FactoryData.get(level.getServer()).factoryAt(center);
                    if (parent != null) ancestors.add(parent.id());
                    PreviewDisplayData.trimNested(nested, ancestors, 2);
                    tag.put(PREVIEW_BLOCKS_TAG, nested);
                    tag.putInt("RecursiveFactoryPreviewInterval", PreviewTransport.sampledInterval(endpoint));
                } else {
                    tag.put(PREVIEW_BLOCKS_TAG, new CompoundTag());
                }
            }
            if (tag.getString("id").startsWith("simulated:") && tag.contains("OwnStrand")) {
                // Ropes have their own preview stream. Do not recreate server physics objects in a
                // virtual client world or rebuild the entire room mesh whenever rope points move.
                tag.remove("Strand");
            }
            if (blockEntity instanceof BeltBlockEntity belt) {
                tag.put("Controller", NbtUtils.writeBlockPos(belt.getController().subtract(center).above()));
            }
            if (blockEntity instanceof ItemVaultBlockEntity) {
                // Vault CT compares each part's controller address. The controller itself uses its
                // local block position, so leaving followers in world coordinates disconnects it.
                for (String key : List.of("Controller", "LastKnownPos")) {
                    NbtUtils.readBlockPos(tag, key).ifPresent(pos ->
                            tag.put(key, NbtUtils.writeBlockPos(pos.subtract(center).above())));
                }
            }
            tag.putInt("x", block.x());
            tag.putInt("y", block.y());
            tag.putInt("z", block.z());
            if (block.contextOnly()) tag.putBoolean(PREVIEW_CONTEXT_TAG, true);
            sampled.add(tag);
        }
        return List.copyOf(sampled);
    }

}
