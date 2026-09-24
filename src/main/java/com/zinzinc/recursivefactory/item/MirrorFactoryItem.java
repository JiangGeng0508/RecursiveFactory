package com.zinzinc.recursivefactory.item;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryBlock;
import com.zinzinc.recursivefactory.block.entity.FactoryRelay;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The mirror factory: what a printer hands out once it has finished a room. Putting it down places an
 * ordinary entrance block and binds it to the room the printer built, so the copy is a factory like any
 * other - the same colour, the same walls, the same free walk in through any barrier.
 *
 * <p>The room a printer builds is already standing by the time this item exists, so nothing has to be
 * built again here: the entrance is bound to it, keeping the floor that was laid for it, and the room
 * takes the block as its own cell. The one case where something is made is a second copy of the same
 * factory - the room already has an entrance, so the item was printed from something that was already
 * handed out - and that copy is built from the blocks of the room itself rather than from the file it
 * came from, so it carries whatever the player has built there since.
 */
public final class MirrorFactoryItem extends Item {
    public MirrorFactoryItem(Item.Properties properties) {
        super(properties);
    }

    /** The stack a finished print hands out: the room it built, and the blueprint it built it from. */
    public static ItemStack create(int roomId, @Nullable String blueprintName, int colorIndex) {
        ItemStack stack = new ItemStack(ModBlocks.MIRROR_FACTORY_ITEM.get());
        stack.set(ModDataComponents.ROOM.get(), roomId);
        if (blueprintName != null) {
            stack.set(ModDataComponents.BLUEPRINT.get(), blueprintName);
        }
        stack.set(ModDataComponents.COLOR.get(), colorIndex);
        return stack;
    }

    /**
     * The entrance block goes down before one of the mod's own blocks can answer. A player laying a mirror
     * factory may well be aiming at the block they just left - a factory barrier, which walks them back out
     * of the room, or the entrance block of the factory they are copying, which walks them in - and the
     * placement, not the walk, is what they meant. Anything else is left to the ordinary click, which
     * reaches {@link #place}.
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        BlockState clicked = context.getLevel().getBlockState(context.getClickedPos());
        if (!clicked.is(ModBlocks.RECURSIVE_FACTORY.get()) && !clicked.is(ModBlocks.FACTORY_BARRIER.get())) {
            return InteractionResult.PASS;
        }
        return place(context);
    }

    /** The ordinary click: the entrance block goes down beside whatever was clicked. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        return place(context);
    }

    private InteractionResult place(UseOnContext context) {
        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        int roomId = stack.getOrDefault(ModDataComponents.ROOM.get(), -1);
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        if (roomId <= 0) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.mirror.empty"), true);
            return InteractionResult.FAIL;
        }
        MinecraftServer server = player.getServer();
        ServerLevel roomLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        if (server == null || roomLevel == null) {
            return InteractionResult.PASS;
        }
        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord source = data.factory(roomId);
        if (source == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.missing"), true);
            return InteractionResult.FAIL;
        }

        Direction face = context.getClickedFace();
        BlockPos pos = context.getClickedPos().relative(face);
        if (!player.mayUseItemAt(pos, face, stack)) {
            return InteractionResult.PASS;
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.mirror.blocked"), true);
            return InteractionResult.FAIL;
        }
        int colorIndex = FactoryColors.kindOfFactory(source.colorIndex(), source.id());
        BlockState state = RecursiveFactoryBlock.withConnections(
                ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState()
                        .setValue(FactoryColors.COLOR_PROPERTY, FactoryColors.stateValue(colorIndex)),
                level,
                pos);
        if (!level.isUnobstructed(state, pos, CollisionContext.empty()) || !level.setBlock(pos, state, Block.UPDATE_ALL)) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.mirror.blocked"), true);
            return InteractionResult.FAIL;
        }

        int targetId = bindToRoom(stack, server, roomLevel, data, source);
        data.bindRoomEntrance(targetId, level.dimension().location(), pos);
        if (level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance) {
            entrance.setFactoryId(targetId);
            FactoryData.FactoryRecord room = data.factory(targetId);
            entrance.setColorIndex(room == null ? colorIndex : room.colorIndex());
            // The frame only carries on into another entrance block of the same room, and the copy just put
            // down is a factory of its own: laying it beside an entrance block of another factory must leave
            // both frames whole. The state worked out above could not know that yet, so it is asked again
            // here, for this block and for the blocks beside it.
            RecursiveFactoryBlock.refreshConnections(level, pos);
            FactoryRelay.updateFromNeighbours(entrance);
        }
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.recursivefactory.mirror.placed", targetId), true);
        return InteractionResult.CONSUME;
    }

    /**
     * The room this copy stands for: the room the printer built, if nothing has been bound to it yet, or a
     * copy of that room if it already has an entrance - the same factory put down twice is two factories.
     * A room whose entrance block was broken and whose cell went with it is stood back up where it was,
     * so the way back to a copy of a factory is never lost for good.
     */
    private static int bindToRoom(ItemStack stack, MinecraftServer server, ServerLevel roomLevel,
                                  FactoryData data, FactoryData.FactoryRecord source) {
        if (source.entranceDimension() == null) {
            if (source.cells().isEmpty()) {
                data.bindRoom(source.id());
                FactoryData.FactoryRecord room = data.factory(source.id());
                if (room != null) {
                    FactoryDimension.prepare(roomLevel, room);
                }
            }
            return source.id();
        }

        int colorIndex = FactoryColors.kindOfFactory(source.colorIndex(), source.id());
        FactoryData.FactoryRecord copy = data.create(source.owner(), colorIndex);
        data.bindRoom(copy.id());
        FactoryData.FactoryRecord room = data.factory(copy.id());
        if (room == null) {
            return copy.id();
        }
        FactoryDimension.prepare(roomLevel, room);
        FactoryData.FactoryRecord.Cell from = source.anchorCell();
        FactoryData.FactoryRecord.Cell to = room.anchorCell();
        if (to == null) {
            return copy.id();
        }
        if (from != null) {
            FactoryBlueprint.copyInto(roomLevel, from, to);
            return copy.id();
        }
        // The room the copy was printed into is gone; build it from the file it was printed from instead.
        String fileName = stack.get(ModDataComponents.BLUEPRINT.get());
        FactoryBlueprint blueprint = fileName == null ? null : FactoryBlueprint.read(server, fileName);
        if (blueprint != null) {
            blueprint.placeAll(roomLevel, to);
        }
        return copy.id();
    }
}