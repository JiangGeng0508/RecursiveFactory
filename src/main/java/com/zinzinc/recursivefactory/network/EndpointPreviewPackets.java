package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.FactoryPreviewSource;
import com.zinzinc.recursivefactory.block.entity.FactoryPreviewBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;
import com.zinzinc.recursivefactory.client.network.ClientEndpointPreviewHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class EndpointPreviewPackets {
    private static final Map<FactoryPreviewSource, Map<ServerPlayer, Long>> REQUESTS = new WeakHashMap<>();

    public static boolean allowRequest(ServerPlayer player, FactoryPreviewSource endpoint) {
        if (!(endpoint instanceof RecursiveFactoryBlockEntity || endpoint instanceof FactoryPreviewBlockEntity)
                || !endpoint.canView(player)) return false;
        long now = player.level().getGameTime();
        Map<ServerPlayer, Long> viewers = REQUESTS.computeIfAbsent(endpoint, ignored -> new WeakHashMap<>());
        Long previous = viewers.get(player);
        if (previous != null && now >= previous && now - previous < 40) return false;
        viewers.put(player, now);
        return true;
    }

    private EndpointPreviewPackets() {
    }

    public record Request(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "request_endpoint_preview"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC =
                BlockPos.STREAM_CODEC.map(Request::new, Request::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Request packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)
                        || !player.level().isLoaded(packet.pos())
                        || !(player.level().getBlockEntity(packet.pos()) instanceof FactoryPreviewSource endpoint)
                        || !allowRequest(player, endpoint)) {
                    return;
                }
                if (endpoint instanceof RecursiveFactoryBlockEntity entrance) entrance.refreshPreviewForDisplay();
                else endpoint.refreshPreviewSnapshot();
                PacketDistributor.sendToPlayer(player, new Sync(
                        packet.pos(),
                        EndpointBlockEntity.writePreview(
                                endpoint.getPreviewBlocks(),
                                endpoint.getPreviewEntities(),
                                endpoint.getPreviewBlockEntities(), endpoint.getPreviewWires(), endpoint.getPreviewBodies(), endpoint.getPreviewRopes()
                        )
                ));
            });
        }
    }

    public record Sync(BlockPos pos, CompoundTag previewTag) implements CustomPacketPayload {
        public static final Type<Sync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "sync_endpoint_preview"));
        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Sync::pos,
                ByteBufCodecs.COMPOUND_TAG,
                Sync::previewTag,
                Sync::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Sync packet, IPayloadContext context) {
            context.enqueueWork(() -> ClientEndpointPreviewHandler.handle(packet));
        }
    }
}
