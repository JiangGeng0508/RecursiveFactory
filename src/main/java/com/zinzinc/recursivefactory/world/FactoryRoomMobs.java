package com.zinzinc.recursivefactory.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;

/**
 * A mob standing in a factory room is only ever judged against the players standing in that same room.
 *
 * <p>Every room of every factory shares one level, and their slots are hundreds of blocks apart -
 * a factory is given its own chunk slot to build its room in (see {@code FactoryData#create}) - so
 * vanilla's despawn rule, "throw the mob away when the nearest player in the level is further away than
 * the despawn distance", reads a player standing in some other room as this room's own player and
 * empties a room the moment the player walks out of it. As far as a room is concerned there is no
 * player in it at all, and that is the case vanilla leaves alone (a mob with nobody around does not
 * despawn), so the discarding is called off here. A mob that does have a player in its own room keeps
 * the ordinary rules.
 *
 * <p>This only ever keeps a room from eating its own mobs; it does not keep the room itself running.
 * A room nobody is in is still let go as a whole (see {@code FactoryDimension#holdRoomsOpen}), and its
 * mobs go with it, saved and brought back the way every other mob in an unloaded chunk is.
 */
@EventBusSubscriber(modid = "recursivefactory")
public final class FactoryRoomMobs {
    private FactoryRoomMobs() {
    }

    @SubscribeEvent
    public static void onDespawn(MobDespawnEvent event) {
        if (event.getResult() != MobDespawnEvent.Result.DEFAULT) {
            return;
        }
        Mob mob = event.getEntity();
        if (!(mob.level() instanceof ServerLevel level) || level.dimension() != FactoryDimension.LEVEL_KEY) {
            return;
        }
        // Peaceful still empties the rooms, the way it empties the rest of the world.
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        FactoryData.FactoryRecord record = FactoryData.get(level.getServer()).factoryAt(mob.blockPosition());
        if (record == null) {
            return;
        }
        for (Player player : level.players()) {
            if (!player.isSpectator() && record.roomContains(player.blockPosition())) {
                return;
            }
        }
        event.setResult(MobDespawnEvent.Result.DENY);
    }
}
