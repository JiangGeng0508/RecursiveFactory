package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * The entrance block's item form. There is one item and sixteen colour kinds: which one a stack stands for
 * is carried by {@link ModDataComponents#COLOR}, so the sixteen recipes all hand out the same item and the
 * creative tab lists it once per colour.
 */
public final class RecursiveFactoryItem extends BlockItem {
    public RecursiveFactoryItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    /** The stack a recipe hands out: an entrance block of the colour kind at {@code colorIndex}. */
    public static ItemStack colored(int colorIndex) {
        ItemStack stack = new ItemStack(ModBlocks.RECURSIVE_FACTORY_ITEM.get());
        stack.set(ModDataComponents.COLOR.get(), colorIndex);
        return stack;
    }

    /** A stack that carries a colour kind says so in its name; one that carries none keeps the plain name. */
    @Override
    public Component getName(ItemStack stack) {
        int colorIndex = FactoryColors.colorOf(stack);
        return colorIndex == FactoryColors.NO_COLOR
                ? super.getName(stack)
                : Component.translatable("block.recursivefactory.recursive_factory.colored",
                        FactoryColors.nameOf(colorIndex));
    }
}