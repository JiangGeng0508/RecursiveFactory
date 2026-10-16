package com.zinzinc.recursivefactory.world;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Dust installs a connection before block use can teleport the player or place a wire. */
@EventBusSubscriber(modid = RecursiveFactory.MODID)
public final class FactoryRedstone {
    private FactoryRedstone() {}

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        ItemStack stack = event.getItemStack();
        // An empty main hand would otherwise enter/leave before Minecraft tries the off hand.
        if (event.getHand() == InteractionHand.MAIN_HAND && stack.isEmpty())
            stack = player.getOffhandItem();
        if (!stack.is(Items.REDSTONE)
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof EndpointBlockEntity endpoint)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Direction face = event.getHitVec().getDirection();
        if (!player.mayBuild() || !level.mayInteract(player, event.getPos()) || !validFace(level, endpoint, face)) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.redstone.invalid"), true);
            return;
        }
        if (!endpoint.activateRedstone(face)) {
            player.displayClientMessage(Component.translatable("message.recursivefactory.redstone.installed"), true);
            return;
        }
        stack.consume(1, player);
        level.playSound(null, event.getPos(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.6F, 1.2F);
        player.displayClientMessage(Component.translatable("message.recursivefactory.redstone.activated"), true);
    }

    private static boolean validFace(ServerLevel level, EndpointBlockEntity endpoint, Direction face) {
        var record = FactoryData.get(level.getServer()).factory(endpoint.getFactoryId());
        if (record == null) return false;
        if (endpoint instanceof RecursiveFactoryBlockEntity) {
            var cell = record.cellAt(endpoint.getBlockPos());
            return cell != null && level.dimension().location().equals(record.entranceDimension())
                    && FactoryDimension.wallLine(record, cell, face).stream()
                            .anyMatch(wall -> FactoryDimension.shellSide(record, wall) == face);
        }
        Direction side = FactoryDimension.shellSide(record, endpoint.getBlockPos());
        return level.dimension().equals(FactoryDimension.LEVEL_KEY) && side == face.getOpposite()
                && FactoryDimension.inward(record, endpoint.getBlockPos(), side) != null;
    }
}
