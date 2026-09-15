package com.zinzinc.recursivefactory.client;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Lets the player drop into a factory through the entrance block's top window by crouching on it.
 *
 * <p>The window is horizontal, and Immersive Portals does not carry an entity that only stands on the
 * plane: the feet have to get across it. Crouching pushes the player down a little every tick until they
 * cross and the portal takes over. This is the same trick the reference mod uses for dropping into a scale
 * box from above, and it runs on the client for the same reason it does there: a player's position is
 * client authoritative, so a server side push would only be undone by the next movement packet.
 */
@EventBusSubscriber(modid = RecursiveFactory.MODID, value = Dist.CLIENT)
public final class TopWindowDescend {
    /** How far crouching pushes the player down each tick. */
    private static final double NUDGE_PER_TICK = 0.02D;
    /** How far above the window the nudge still counts as standing on it. */
    private static final double HOVER_TOLERANCE = 0.5D;

    private TopWindowDescend() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !player.isShiftKeyDown()) {
            return;
        }

        // The tall entrance's window sits two blocks above its block, the short entrance's one block above.
        // Checking the block rather than a portal keeps this working in any dimension an entrance is placed
        // in, nested factories included.
        Level level = player.level();
        BlockPos entrance = BlockPos.containing(player.getX(), player.getY() - 2.0D, player.getZ());
        double windowY = entrance.getY() + 2.0D;
        if (!level.getBlockState(entrance).is(ModBlocks.RECURSIVE_FACTORY.get())) {
            entrance = BlockPos.containing(player.getX(), player.getY() - 1.0D, player.getZ());
            if (!level.getBlockState(entrance).is(ModBlocks.RECURSIVE_FACTORY_SHORT.get())) {
                return;
            }
            windowY = entrance.getY() + 1.0D;
        }
        boolean overWindow = Math.abs(player.getX() - (entrance.getX() + 0.5D)) <= 0.5D
                && Math.abs(player.getZ() - (entrance.getZ() + 0.5D)) <= 0.5D
                && player.getY() >= windowY
                && player.getY() <= windowY + HOVER_TOLERANCE;
        if (!overWindow) {
            return;
        }

        player.setPos(player.getX(), player.getY() - NUDGE_PER_TICK, player.getZ());
    }
}
