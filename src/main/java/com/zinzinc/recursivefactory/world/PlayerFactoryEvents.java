package com.zinzinc.recursivefactory.world;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Keeps a player who is in the factory dimension but outside every room from being stranded there. */
@EventBusSubscriber
public final class PlayerFactoryEvents {
    private PlayerFactoryEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }

        if (player.level().dimension() != FactoryDimension.LEVEL_KEY || player.level().getServer() == null) {
            return;
        }

        FactoryData data = FactoryData.get(player.level().getServer());
        if (data.factoryAt(player.blockPosition()) == null) {
            // Outside every room: either walked off the platform, or a room that no longer exists.
            FactoryTeleporter.exitFromEdge(player);
        }
    }
}
