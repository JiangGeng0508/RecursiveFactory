package com.zinzinc.recursivefactory.preview;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.zinzinc.recursivefactory.config.FactoryConfig;
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

import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity.PreviewBlock;
import static com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity.*;

public final class PreviewCodec {
    private static final String PREVIEW_BLOCKS_LIST_TAG = "Blocks";
    private static final String PREVIEW_ENTITIES_LIST_TAG = "Entities";
    private static final String PREVIEW_BLOCK_ENTITIES_LIST_TAG = "BlockEntities";
    private static final List<String> VOLATILE_ENTITY_TAGS = List.of(
            "Motion", "FallDistance", "OnGround", "PortalCooldown", "Air", "Fire", "UUID");
    public static CompoundTag writePreview(List<PreviewBlock> blocks, List<CompoundTag> entities,
                                           List<CompoundTag> blockEntities, CompoundTag wires) {
        return writePreview(blocks, entities, blockEntities, wires, List.of());
    }

    public static CompoundTag writePreview(List<PreviewBlock> blocks, List<CompoundTag> entities,
                                           List<CompoundTag> blockEntities, CompoundTag wires,
                                           List<CompoundTag> bodies) {
        return writePreview(blocks, entities, blockEntities, wires, bodies, List.of());
    }

    public static CompoundTag writePreview(List<PreviewBlock> blocks, List<CompoundTag> entities,
                                           List<CompoundTag> blockEntities, CompoundTag wires,
                                           List<CompoundTag> bodies, List<CompoundTag> ropes) {
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
        ListTag ropeList = new ListTag();
        for (CompoundTag rope : ropes) ropeList.add(rope.copy());
        if (!ropeList.isEmpty()) root.put("Ropes", ropeList);
        return root;
    }

    public static List<CompoundTag> readPreviewRopes(CompoundTag tag) {
        CompoundTag root = tag.contains(PREVIEW_BLOCKS_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(PREVIEW_BLOCKS_TAG) : tag;
        List<CompoundTag> ropes = new ArrayList<>();
        for (Tag entry : root.getList("Ropes", Tag.TAG_COMPOUND)) ropes.add(((CompoundTag) entry).copy());
        return List.copyOf(ropes);
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

}
