package com.zinzinc.recursivefactory.compat;

import net.minecraft.core.BlockPos;

/**
 * A cannon shot that is meant for a factory room: the flight is aimed at the entrance the player can see
 * (see {@code LaunchedItemRoomMixin}), and the shot remembers where inside the room it really belongs.
 */
public interface RoomLaunchedItem {
    /** Marks the shot as one for a room: it lands at {@code roomTarget} of factory {@code roomId}. */
    void recursivefactory$landInRoom(BlockPos roomTarget, int roomId);

    /** Where in the factory dimension this shot is placed, or null for an ordinary cannon shot. */
    BlockPos recursivefactory$roomTarget();

    /** The room's factory id, kept loaded until the shot arrives; 0 for an ordinary cannon shot. */
    int recursivefactory$roomId();
}