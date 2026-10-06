package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.RecursiveFactoryItem;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import net.minecraft.core.GlobalPos;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Creative pick-block records the source coordinates; placement reads and expands the current contents. */
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

    /** No room scan or blueprint file: picking records only the entrance location and its display colour. */
    public static ItemStack copyStack(ServerLevel level, BlockPos pos, String owner) {
        if (!(level.getBlockEntity(pos) instanceof RecursiveFactoryBlockEntity)) {
            throw FactoryBlueprint.Refusal.of(Component.translatable("message.recursivefactory.missing"));
        }
        int color = FactoryColors.kindOfState(level.getBlockState(pos));
        ItemStack stack = color == FactoryColors.NO_COLOR
                ? new ItemStack(ModBlocks.RECURSIVE_FACTORY_ITEM.get())
                : RecursiveFactoryItem.colored(color);
        stack.set(ModDataComponents.COPY_SOURCE.get(), GlobalPos.of(level.dimension(), pos.immutable()));
        return stack;
    }
}
