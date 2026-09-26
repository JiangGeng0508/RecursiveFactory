package com.zinzinc.recursivefactory.item;

import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The mirror factory blueprint: used on an entrance block, it takes a copy of what stands inside that
 * factory's room and hands the player a stack standing for the copy, which a factory printer then prints
 * into a room of its own.
 *
 * <p>What the item carries is only the name of the file the copy was written to; the room's blocks never
 * travel inside the item, so a room full of machinery is a file on disk and a few bytes in a stack.
 *
 * <p>A factory standing inside the room - an entrance block that was put down in there - is copied with
 * it, room and all, and so is any factory standing inside that one: a room as wide as it was, one cell or
 * several (see {@link FactoryBlueprint}). One nested deeper than {@link FactoryBlueprint#MAX_NESTING_DEPTH}
 * is not followed, and a factory that leads back into one this capture is already reading would never end:
 * on either of those the capture is given up on rather than half done, and the item says which it was.
 */
public final class FactoryBlueprintItem extends Item {
    public FactoryBlueprintItem(Item.Properties properties) {
        super(properties);
    }

    /** The stack a capture hands out: a blueprint of the room that was read out, in its factory's colour. */
    public static ItemStack of(FactoryBlueprint blueprint) {
        ItemStack stack = new ItemStack(ModBlocks.FACTORY_BLUEPRINT_ITEM.get());
        stack.set(ModDataComponents.BLUEPRINT.get(), blueprint.name());
        stack.set(ModDataComponents.COLOR.get(), blueprint.colorIndex());
        return stack;
    }

    /** The blueprint file a stack stands for, or null for a stack that carries none. */
    public static String fileName(ItemStack stack) {
        return stack.get(ModDataComponents.BLUEPRINT.get());
    }

    /**
     * The capture runs before the entrance block gets the click. A bare right click on an entrance block
     * walks a player into the factory, so an item that only ever acts on that same block has to take the
     * click first or it would never see one.
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)
                || !entrance.hasFactoryId()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        MinecraftServer server = serverLevel.getServer();
        ServerLevel roomLevel = server == null ? null : server.getLevel(FactoryDimension.LEVEL_KEY);
        if (server == null || roomLevel == null) {
            return InteractionResult.PASS;
        }

        FactoryData data = FactoryData.get(server);
        FactoryData.FactoryRecord record = data.factory(entrance.getFactoryId());
        if (record == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.missing"), true);
            return InteractionResult.FAIL;
        }
        FactoryData.FactoryRecord.Cell cell = record.anchorCell();
        if (cell == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.missing"), true);
            return InteractionResult.FAIL;
        }

        // Nothing walks into a room while its contents are being read out of it - including the rooms of
        // the factories nested inside it, which are read out with it.
        FactoryBlueprint blueprint;
        try {
            blueprint = FactoryLocks.whileLocked(record.id(),
                    () -> FactoryBlueprint.capture(roomLevel, data, record, cell, FactoryBlueprint.newName()));
        } catch (FactoryBlueprint.Refusal refusal) {
            // Something standing inside the room, or inside a factory standing in it, cannot be copied:
            // the capture says which, and nothing is written.
            player.displayClientMessage(refusal.reason(), true);
            return InteractionResult.FAIL;
        }
        if (!blueprint.write(server)) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.blueprint.failed"), true);
            return InteractionResult.FAIL;
        }

        ItemStack copy = of(blueprint);
        if (!player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
        player.displayClientMessage(Component.translatable("message.recursivefactory.blueprint.taken",
                blueprint.size(), blueprint.rooms().size()), true);
        return InteractionResult.CONSUME;
    }
}