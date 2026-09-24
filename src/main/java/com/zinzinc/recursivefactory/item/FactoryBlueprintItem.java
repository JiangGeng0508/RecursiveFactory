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
 * travel inside the item, so a room full of machinery is a file on disk and a few bytes in a stack. A
 * factory that has grown past one room cell is not copied - the copy is one room, and the printer would
 * only be able to build one cell of it - so the item says so rather than half doing it.
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
        if (record.cells().size() != 1) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.blueprint.grown"), true);
            return InteractionResult.FAIL;
        }
        FactoryData.FactoryRecord.Cell cell = record.anchorCell();
        if (cell == null) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.missing"), true);
            return InteractionResult.FAIL;
        }

        // Nothing walks into a room while its contents are being read out of it.
        FactoryBlueprint blueprint = FactoryLocks.whileLocked(record.id(),
                () -> FactoryBlueprint.capture(roomLevel, record, cell, FactoryBlueprint.newName()));
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
                blueprint.size()), true);
        return InteractionResult.CONSUME;
    }
}