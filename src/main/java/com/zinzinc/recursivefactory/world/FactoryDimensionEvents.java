package com.zinzinc.recursivefactory.world;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/**
 * No fall damage inside a factory room.
 *
 * <p>Arriving through the ceiling window drops the player the room's full height to the platform, and the
 * room is a workplace: a thirty block fall at the door should not be lethal. The reference mod cancels
 * fall damage in its own box dimension for exactly this reason.
 */
@EventBusSubscriber
public final class FactoryDimensionEvents {
    private FactoryDimensionEvents() {
    }

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().dimension() == FactoryDimension.LEVEL_KEY) {
            event.setCanceled(true);
        }
    }
}
