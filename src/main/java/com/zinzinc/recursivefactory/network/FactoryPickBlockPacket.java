package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.RecursiveFactoryItem;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.FactoryLocks;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Creative pick-block captures a snapshot and lets the normal block item placement build each copy. */
public record FactoryPickBlockPacket(BlockPos pos) implements CustomPacketPayload {
    public static final Type<FactoryPickBlockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "pick_factory_block"));
    public static final StreamCodec<ByteBuf, FactoryPickBlockPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(FactoryPickBlockPacket::new, FactoryPickBlockPacket::pos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FactoryPickBlockPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                pick(player, packet.pos());
            }
        });
    }

    public static boolean pick(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!player.isCreative() || !level.isLoaded(pos) || !player.canInteractWithBlock(pos, 0)
                || !(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity)) {
            return false;
        }
        try {
            ItemStack stack = copyStack(level, pos, player.getUUID().toString());
            player.getInventory().setPickedItem(stack);
            player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
            player.inventoryMenu.broadcastChanges();
            return true;
        } catch (FactoryBlueprint.Refusal refusal) {
            player.displayClientMessage(refusal.reason(), true);
            return false;
        }
    }

    /** Uses the picked entrance's cell as origin, preserving placement alignment in expanded factories. */
    public static ItemStack copyStack(ServerLevel level, BlockPos pos, String owner) {
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity entrance)) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }
        FactoryData data = FactoryData.get(level.getServer());
        var record = data.factory(entrance.getFactoryId());
        var cell = record == null ? null : record.cellAt(pos);
        ServerLevel roomLevel = level.getServer().getLevel(FactoryDimension.LEVEL_KEY);
        if (record == null || cell == null || roomLevel == null) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }
        FactoryBlueprint blueprint = FactoryLocks.whileLocked(record.id(), () ->
                FactoryBlueprint.capture(roomLevel, data, record, cell,
                        owner + FactoryBlueprint.OWNER_SEPARATOR + FactoryBlueprint.newName()));
        if (!blueprint.write(level.getServer())) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.blueprint.failed"));
        }

        ItemStack stack = RecursiveFactoryItem.colored(blueprint.colorIndex());
        CompoundTag tag = new CompoundTag();
        tag.putString(RecursiveFactoryBlockEntity.BLUEPRINT_TAG, blueprint.name());
        CompoundTag nodes = entrance.blueprintEntranceNodes();
        if (!nodes.isEmpty()) tag.put(RecursiveFactoryBlockEntity.ENTRANCE_NODES_TAG, nodes);
        // The other entrance blocks the factory was expanded into travel with the copy: the room is copied
        // with a cell for each of them, but the blocks themselves stand in the world and would be left
        // behind. They are written as offsets from this block, and placed together with it.
        ListTag doors = new ListTag();
        for (FactoryData.FactoryRecord.Cell door : record.connectedBoundCells(cell)) {
            BlockPos offset = door.entrance().subtract(pos);
            if (offset.equals(BlockPos.ZERO)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", offset.getX());
            entry.putInt("Y", offset.getY());
            entry.putInt("Z", offset.getZ());
            doors.add(entry);
        }
        if (!doors.isEmpty()) tag.put(RecursiveFactoryBlockEntity.DOORS_TAG, doors);
        // Carry only placement data, never the live factory ID, buffers, power network or preview.
        BlockItem.setBlockEntityData(stack, ModBlockEntities.RECURSIVE_FACTORY.get(), tag);
        return stack;
    }
}
