package com.zinzinc.recursivefactory.block;

import com.mojang.serialization.MapCodec;
import com.zinzinc.recursivefactory.block.entity.FactoryPrinterBlockEntity;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The factory printer. It builds a copy of a factory room out of the materials in the containers around
 * it, one block at a time; what it has been asked to print and how far it has got live on its block
 * entity, and travel with the block when a player picks it up in the middle of a print.
 *
 * <p>There is no screen: right clicking it with a blueprint starts a print, right clicking it with sugar
 * tops its fuel up, and right clicking it with an empty hand takes the finished copy out or says where
 * the print has got to.
 */
public final class FactoryPrinterBlock extends BaseEntityBlock {
    public static final MapCodec<FactoryPrinterBlock> CODEC = simpleCodec(FactoryPrinterBlock::new);
    private static final VoxelShape SHAPE = Shapes.block();

    public FactoryPrinterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof FactoryPrinterBlockEntity printer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!printer.accepts(player.getItemInHand(hand))) {
            // Something the printer has no use for is left to whoever else wants it rather than being
            // swallowed here.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        return printer.useItem(player, player.getItemInHand(hand))
                ? ItemInteractionResult.CONSUME
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof FactoryPrinterBlockEntity printer)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        printer.useEmptyHand(player);
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof FactoryPrinterBlockEntity printer)) {
            return;
        }
        // A printer that was picked up in the middle of a print carries on where it left off.
        printer.readFromItem(stack);
        printer.setOwner(placer instanceof Player player ? player.getUUID() : null);
    }

    /**
     * What a broken printer drops: the machine itself, told what it was printing and how far it had got,
     * so putting it back down carries the print on rather than starting the room over.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                instanceof FactoryPrinterBlockEntity printer) {
            for (ItemStack drop : drops) {
                if (drop.is(asItem())) {
                    printer.writeToItem(drop);
                }
            }
        }
        return drops;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactoryPrinterBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        return type == ModBlockEntities.FACTORY_PRINTER.get()
                ? (tickerLevel, tickerPos, tickerState, blockEntity) ->
                        ((FactoryPrinterBlockEntity) blockEntity).tick()
                : null;
    }
}
