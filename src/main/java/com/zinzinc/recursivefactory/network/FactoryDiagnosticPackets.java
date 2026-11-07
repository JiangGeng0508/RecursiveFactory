package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.*;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class FactoryDiagnosticPackets {
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();

    public static CompoundTag capture(EndpointBlockEntity endpoint, Direction face) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Face", face.ordinal()); tag.putInt("Factory", endpoint.getFactoryId());
        tag.putBoolean("Kinetic", FactoryConfig.kineticTransferEnabled());
        tag.putBoolean("Items", FactoryConfig.itemTransferEnabled());
        tag.putBoolean("Fluid", FactoryConfig.fluidTransferEnabled());
        tag.putBoolean("Redstone", endpoint.isRedstoneEnabled(face));
        if (endpoint.getLevel() instanceof ServerLevel level) {
            var room = FactoryData.get(level.getServer()).factory(endpoint.getFactoryId());
            if (room != null) {
                Direction wall = endpoint instanceof RecursiveFactoryBlockEntity ? face
                        : FactoryDimension.shellSide(room, endpoint.getBlockPos());
                boolean valid;
                if (endpoint instanceof RecursiveFactoryBlockEntity) {
                    var cell = room.cellAt(endpoint.getBlockPos());
                    valid = cell != null && level.dimension().location().equals(room.entranceDimension())
                            && FactoryDimension.wallLine(room, cell, face).stream()
                            .anyMatch(pos -> FactoryDimension.shellSide(room, pos) == face);
                } else valid = wall != null && wall.getOpposite() == face;
                tag.putBoolean("Valid", valid);
                if (wall != null) tag.putInt("Wall", wall.ordinal());
            }
        }
        int count = 0;
        for (int slot = 0; slot < EndpointBlockEntity.PENDING_SLOTS; slot++)
            if (endpoint.getPendingInput(slot) == face) count += endpoint.getPendingStack(slot).getCount();
        tag.putInt("ItemCount", count);
        tag.putInt("FluidAmount", endpoint.getPendingFluidInput() == face ? endpoint.getPendingFluid().getAmount() : 0);
        tag.putBoolean("ItemBlocked", count > 0 && endpoint.itemTransportBlocked(face));
        tag.putBoolean("FluidBlocked", tag.getInt("FluidAmount") > 0 && endpoint.fluidTransportBlocked(face));
        return tag;
    }

    public record Request(BlockPos pos, int face) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "diagnostic_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, ByteBufCodecs.VAR_INT, Request::face, Request::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Request packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player) || packet.face < 0 || packet.face >= 6
                        || player.distanceToSqr(com.zinzinc.recursivefactory.compat.sable.SablePhysicsBodies.worldPosition(
                                player.serverLevel(), packet.pos.getCenter())) > 64 || !player.level().isLoaded(packet.pos)
                        || !(player.level().getBlockEntity(packet.pos) instanceof EndpointBlockEntity endpoint)) return;
                long now = player.level().getGameTime();
                Long last = LAST_REQUEST.get(player);
                if (last != null && now >= last && now - last < 10) return;
                LAST_REQUEST.put(player, now);
                PacketDistributor.sendToPlayer(player, new Sync(packet.pos, capture(endpoint, Direction.values()[packet.face])));
            });
        }
    }

    public record Sync(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "diagnostic_sync"));
        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Sync::pos, ByteBufCodecs.COMPOUND_TAG, Sync::data, Sync::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public static void handle(Sync packet, IPayloadContext context) {
            context.enqueueWork(() -> com.zinzinc.recursivefactory.client.FactoryGoggles.receive(packet));
        }
    }
}
