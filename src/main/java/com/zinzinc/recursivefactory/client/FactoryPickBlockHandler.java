package com.zinzinc.recursivefactory.client;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.network.FactoryPickBlockPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = RecursiveFactory.MODID, value = Dist.CLIENT)
public final class FactoryPickBlockHandler {
    private FactoryPickBlockHandler() { }

    @SubscribeEvent
    public static void pickBlock(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.isPickBlock() || minecraft.player == null || !minecraft.player.isCreative()
                || minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !minecraft.level.getBlockState(hit.getBlockPos()).is(ModBlocks.RECURSIVE_FACTORY.get())) {
            return;
        }
        // The client only has a preview. Capture the complete room on the server, including nested rooms.
        event.setCanceled(true);
        event.setSwingHand(false);
        PacketDistributor.sendToServer(new FactoryPickBlockPacket(hit.getBlockPos()));
    }
}
