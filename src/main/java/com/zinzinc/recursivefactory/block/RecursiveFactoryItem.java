package com.zinzinc.recursivefactory.block;

import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryCopyLink;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The entrance block's item form. There is one item and sixteen colour kinds: which one a stack stands for
 * is carried by {@link ModDataComponents#COLOR}, so the sixteen recipes all hand out the same item and the
 * creative tab lists it once per colour.
 */
public final class RecursiveFactoryItem extends BlockItem {
    /** The tag a copy of an expanded factory carries its other entrance blocks in, see the block entity. */
    private static final String DOORS_TAG = RecursiveFactoryBlockEntity.DOORS_TAG;

    public RecursiveFactoryItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    /** The stack a recipe hands out: an entrance block of the colour kind at {@code colorIndex}. */
    public static ItemStack colored(int colorIndex) {
        ItemStack stack = new ItemStack(ModBlocks.RECURSIVE_FACTORY_ITEM.get());
        stack.set(ModDataComponents.COLOR.get(), colorIndex);
        return stack;
    }

    /**
     * A copy of an expanded factory is placed all at once: the block the player aims with, and the other
     * entrance blocks the factory was grown through. Those stand apart from it, so the whole layout is
     * checked before anything goes down - a position already taken by another block turns the placement
     * down and the player is told, rather than half a factory being built.
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        var target = context.getItemInHand().get(ModDataComponents.COPY_SOURCE.get());
        if (target != null) {
            // The server resolves the live source. Predicting an ordinary empty factory on the client
            // would use stale colour/layout and briefly show the wrong result.
            if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
            if (!(context.getLevel() instanceof ServerLevel level)
                    || !context.canPlace() || getPlacementState(context) == null) return InteractionResult.FAIL;
            try {
                var source = FactoryCopyLink.resolve(level.getServer(), target);
                if (!canPlaceDoors(context, source.doors())) return InteractionResult.FAIL;
                return super.place(new CopyContext(context, source.capture()));
            } catch (FactoryBlueprint.Refusal refusal) {
                if (context.getPlayer() != null) context.getPlayer().displayClientMessage(refusal.reason(), true);
                return InteractionResult.FAIL;
            }
        }
        List<BlockPos> doors = doorOffsets(context.getItemInHand());
        if (context.getLevel() instanceof ServerLevel level
                && !context.getItemInHand().has(DataComponents.BLOCK_ENTITY_DATA)
                && !RecursiveFactoryBlock.canExpandAt(level, context.getClickedPos())) {
            if (context.getPlayer() != null) context.getPlayer().displayClientMessage(
                    Component.translatable("message.recursivefactory.room.invalid"), true);
            return InteractionResult.FAIL;
        }
        if (!canPlaceDoors(context, doors)) return InteractionResult.FAIL;
        return super.place(context);
    }

    private static boolean canPlaceDoors(BlockPlaceContext context, List<BlockPos> doors) {
        if (!doors.isEmpty()) {
            Level level = context.getLevel();
            BlockPos anchor = context.getClickedPos();
            for (BlockPos offset : doors) {
                BlockPos target = anchor.offset(offset);
                var player = context.getPlayer();
                if (canPlaceDoorAt(level, target) && level.getWorldBorder().isWithinBounds(target)
                        && (player == null || level.mayInteract(player, target)
                                && player.mayUseItemAt(target, context.getClickedFace(), context.getItemInHand()))
                        && level.isUnobstructed(ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), target,
                                player == null ? net.minecraft.world.phys.shapes.CollisionContext.empty()
                                        : net.minecraft.world.phys.shapes.CollisionContext.of(player))) {
                    continue;
                }
                if (!level.isClientSide() && context.getPlayer() != null) {
                    context.getPlayer().displayClientMessage(
                            Component.translatable("message.recursivefactory.place.blocked"), true);
                }
                return false;
            }
        }
        return true;
    }

    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state != null && context instanceof CopyContext copy
                ? state.setValue(FactoryColors.COLOR_PROPERTY, FactoryColors.stateValue(copy.prepared.blueprint().colorIndex()))
                : state;
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        if (!super.placeBlock(context, state)) return false;
        if (context instanceof CopyContext copy
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof RecursiveFactoryBlockEntity entrance) {
            entrance.prepareCopy(copy.prepared);
        }
        return true;
    }

    private static final class CopyContext extends BlockPlaceContext {
        private final FactoryCopyLink.Prepared prepared;

        private CopyContext(BlockPlaceContext original, FactoryCopyLink.Prepared prepared) {
            super(original);
            this.prepared = prepared;
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        var target = stack.get(ModDataComponents.COPY_SOURCE.get());
        if (target != null) tooltip.add(Component.translatable("item.recursivefactory.copy_source",
                target.dimension().location().toString(), target.pos().getX(), target.pos().getY(), target.pos().getZ())
                .withStyle(ChatFormatting.GRAY));
    }

    /** Whether an entrance block can be put down at {@code pos}: nothing but air or something replaceable. */
    private static boolean canPlaceDoorAt(Level level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getBlockState(pos).canBeReplaced();
    }

    /** The other entrance blocks a stack carries, as offsets from the block it is placed as. */
    static List<BlockPos> doorOffsets(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return List.of();
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(DOORS_TAG, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag list = tag.getList(DOORS_TAG, Tag.TAG_COMPOUND);
        List<BlockPos> offsets = new ArrayList<>(list.size());
        for (Tag entry : list) {
            CompoundTag door = (CompoundTag) entry;
            BlockPos offset = new BlockPos(door.getInt("X"), door.getInt("Y"), door.getInt("Z"));
            if (!offset.equals(BlockPos.ZERO)) {
                offsets.add(offset);
            }
        }
        return offsets;
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
