package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.block.entity.MirrorFactoryBlockEntity;
import com.zinzinc.recursivefactory.client.network.ClientEndpointPreviewHandler;
import com.zinzinc.recursivefactory.world.FactoryData;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Carries the sampled outside world ({@link com.zinzinc.recursivefactory.world.FactoryEnclosure})
 * to the client so it can be drawn around the factory platform.
 */
public final class FactoryEnclosurePackets {
    private static final int MAX_BLOCKS = 200_000;

    private static final StreamCodec<ByteBuf, List<EndpointBlockEntity.PreviewBlock>> BLOCKS_CODEC =
            StreamCodec.of(FactoryEnclosurePackets::encodeBlocks, FactoryEnclosurePackets::decodeBlocks);

    private FactoryEnclosurePackets() {
    }

    /** Sent by a client that stands inside a factory and has no enclosure data yet. */
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "request_factory_enclosure"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.unit(new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Request packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)
                        || !(player.level() instanceof ServerLevel serverLevel)
                        || serverLevel.getServer() == null) {
                    return;
                }
                FactoryData.FactoryRecord record = FactoryData.get(serverLevel.getServer())
                        .factoryAt(player.blockPosition());
                if (record == null || record.mirrorDimension() == null
                        || !record.mirrorDimension().equals(serverLevel.dimension().location())) {
                    return;
                }
                if (serverLevel.getBlockEntity(record.mirrorPos()) instanceof MirrorFactoryBlockEntity mirror
                        && mirror.getFactoryId() == record.id()) {
                    mirror.sendEnclosureTo(player);
                }
            });
        }
    }

    /** Server answer containing the sampled blocks, in the relative space described by FactoryEnclosure. */
    public record Sync(int chunkX, int chunkZ, List<EndpointBlockEntity.PreviewBlock> blocks)
            implements CustomPacketPayload {
        public static final Type<Sync> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "sync_factory_enclosure"));
        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                Sync::chunkX,
                ByteBufCodecs.VAR_INT,
                Sync::chunkZ,
                BLOCKS_CODEC,
                Sync::blocks,
                Sync::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Sync packet, IPayloadContext context) {
            context.enqueueWork(() -> ClientEndpointPreviewHandler.handleEnclosure(
                    packet.chunkX(),
                    packet.chunkZ(),
                    packet.blocks()
            ));
        }
    }

    private static void encodeBlocks(ByteBuf buffer, List<EndpointBlockEntity.PreviewBlock> blocks) {
        buffer.writeInt(blocks.size());

        List<BlockState> palette = new ArrayList<>();
        Map<Integer, Integer> paletteIndices = new HashMap<>();
        for (EndpointBlockEntity.PreviewBlock block : blocks) {
            int stateId = Block.BLOCK_STATE_REGISTRY.getId(block.state());
            paletteIndices.computeIfAbsent(stateId, id -> {
                palette.add(block.state());
                return palette.size() - 1;
            });
        }

        buffer.writeInt(palette.size());
        for (BlockState state : palette) {
            VarInt.write(buffer, Block.BLOCK_STATE_REGISTRY.getId(state));
        }
        for (EndpointBlockEntity.PreviewBlock block : blocks) {
            VarInt.write(buffer, block.x());
            VarInt.write(buffer, block.y());
            VarInt.write(buffer, block.z());
            VarInt.write(buffer, paletteIndices.get(Block.BLOCK_STATE_REGISTRY.getId(block.state())));
        }
    }

    private static List<EndpointBlockEntity.PreviewBlock> decodeBlocks(ByteBuf buffer) {
        int count = buffer.readInt();
        if (count < 0 || count > MAX_BLOCKS) {
            throw new DecoderException("Invalid enclosure block count: " + count);
        }

        int paletteSize = buffer.readInt();
        if (paletteSize < 0 || paletteSize > MAX_BLOCKS) {
            throw new DecoderException("Invalid enclosure palette size: " + paletteSize);
        }
        BlockState[] palette = new BlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(VarInt.read(buffer));
            palette[i] = state == null ? Blocks.AIR.defaultBlockState() : state;
        }

        List<EndpointBlockEntity.PreviewBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = VarInt.read(buffer);
            int y = VarInt.read(buffer);
            int z = VarInt.read(buffer);
            int index = VarInt.read(buffer);
            BlockState state = index >= 0 && index < palette.length
                    ? palette[index]
                    : Blocks.AIR.defaultBlockState();
            blocks.add(new EndpointBlockEntity.PreviewBlock(x, y, z, state));
        }
        return List.copyOf(blocks);
    }
}