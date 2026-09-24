package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.client.network.ClientEndpointModeHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Carries the face modes of one entrance block to the clients that can see it.
 *
 * <p>The modes are drawn on the block - a face that carries something is tinted in that thing's colour - and
 * a block's tint is baked into the mesh of the section it stands in, so the client has to be told twice: once
 * that the modes changed, and once that the block now looks different. The second half is a re-mesh, and it
 * is done where the packet lands (see {@link ClientEndpointModeHandler}).
 *
 * <p>The modes are not part of the block's state, because five modes on each of six faces is far more than a
 * block state has room for; they travel as block entity data, which is also where they are saved.
 */
public final class EndpointModePackets {
    private EndpointModePackets() {
    }

    /** The six face modes of one entrance block, packed three bits per face. */
    public record Sync(BlockPos pos, int modes) implements CustomPacketPayload {
        public static final Type<Sync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "sync_endpoint_modes"));
        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Sync::pos,
                ByteBufCodecs.VAR_INT,
                Sync::modes,
                Sync::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Sync packet, IPayloadContext context) {
            context.enqueueWork(() -> ClientEndpointModeHandler.handle(packet));
        }
    }
}