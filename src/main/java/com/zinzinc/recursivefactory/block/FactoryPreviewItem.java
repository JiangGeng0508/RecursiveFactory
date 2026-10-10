package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = RecursiveFactory.MODID)
public final class FactoryPreviewItem extends BlockItem {
    public FactoryPreviewItem(Block block, Properties properties) { super(block, properties); }

    /** Intercept before the entrance teleports the player, including when sneaking or using the offhand. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof FactoryPreviewItem)
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof RecursiveFactoryBlockEntity entrance)) return;
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!entrance.hasFactoryId()) {
            event.getEntity().displayClientMessage(Component.translatable("message.recursivefactory.preview.missing"), true);
            return;
        }
        held.set(ModDataComponents.PREVIEW_SOURCE.get(), GlobalPos.of(level.dimension(), event.getPos()));
        event.getEntity().displayClientMessage(Component.translatable("message.recursivefactory.preview.bound",
                event.getPos().getX(), event.getPos().getY(), event.getPos().getZ()), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.recursivefactory.factory_preview.usage").withStyle(ChatFormatting.GRAY));
        GlobalPos source = stack.get(ModDataComponents.PREVIEW_SOURCE.get());
        if (source != null) tooltip.add(Component.translatable("item.recursivefactory.factory_preview.source",
                source.dimension().location().toString(), source.pos().getX(), source.pos().getY(), source.pos().getZ())
                .withStyle(ChatFormatting.AQUA));
    }
}
