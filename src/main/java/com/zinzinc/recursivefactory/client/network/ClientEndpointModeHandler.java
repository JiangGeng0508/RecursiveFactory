package com.zinzinc.recursivefactory.client.network;

import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointModePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Takes the face modes of an entrance block off the wire and puts them on the block that is standing there,
 * then hands the level a block update so the frame is drawn again in its new colours.
 *
 * <p>The modes decide how each face of the block is tinted, and a block's tint is part of the mesh of the
 * section it stands in rather than something the client works out as it draws. Telling the block entity is
 * therefore only half the job: the block has to be drawn again, or it would keep the colours it was baked
 * with until something else happened to touch that section.
 *
 * <p>Drawing it again is {@link ClientLevel#sendBlockUpdated}, which is what the level itself calls when a
 * block changes: it hands the block's own section and the sections around it to the mesher without asking
 * anything about the block. The states it is given are both the state standing there, because nothing about
 * the state moved - the modes live on the block entity.
 */
public final class ClientEndpointModeHandler {
    private ClientEndpointModeHandler() {
    }

    public static void handle(EndpointModePackets.Sync packet) {
        if (!(Minecraft.getInstance().level instanceof ClientLevel level)
                || !(level.getBlockEntity(packet.pos()) instanceof RecursiveFactoryBlockEntity entrance)) {
            return;
        }
        entrance.applyFaceModes(packet.modes());
        BlockState state = level.getBlockState(packet.pos());
        level.sendBlockUpdated(packet.pos(), state, state, Block.UPDATE_ALL);
    }
}
