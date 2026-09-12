package com.zinzinc.recursivefactory.block.entity;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public abstract class EndpointBlockEntity extends BlockEntity {
    private static final String FACTORY_ID_TAG = "FactoryId";
    private static final String PENDING_STACK_TAG = "PendingStack";
    private static final String PENDING_INPUT_TAG = "PendingInput";
    private static final String LOCAL_POWERED_TAG = "LocalPowered";
    private static final String REMOTE_POWERED_TAG = "RemotePowered";
    private static final String OUTPUT_DIRECTION_TAG = "OutputDirection";

    private int factoryId = -1;
    private ItemStack pendingStack = ItemStack.EMPTY;
    private @Nullable Direction pendingInput;
    private boolean localPowered;
    private boolean remotePowered;
    private @Nullable Direction outputDirection;

    protected EndpointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    public boolean hasFactoryId() {
        return factoryId > 0;
    }

    public int getFactoryId() {
        return factoryId;
    }

    public void setFactoryId(int factoryId) {
        this.factoryId = factoryId;
        setChanged();
    }

    public ItemStack offer(ItemStack stack, @Nullable Direction inputSide) {
        if (stack.isEmpty() || !hasFactoryId()) {
            return stack;
        }

        if (!pendingStack.isEmpty()) {
            if (pendingInput != inputSide || !ItemStack.isSameItemSameComponents(pendingStack, stack)) {
                return stack;
            }

            int space = Math.min(stack.getMaxStackSize(), Integer.MAX_VALUE) - pendingStack.getCount();
            int accepted = Math.min(space, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }

            pendingStack.grow(accepted);
            setChanged();
            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }

        pendingInput = inputSide;
        pendingStack = stack.copy();
        setChanged();
        return ItemStack.EMPTY;
    }

    public ItemStack getPendingStack() {
        return pendingStack;
    }

    public @Nullable Direction getPendingInput() {
        return pendingInput;
    }

    public void setTransportResult(ItemStack remainder) {
        pendingStack = remainder.copy();
        if (pendingStack.isEmpty()) {
            pendingInput = null;
        }
        setChanged();
    }

    public boolean isLocalPowered() {
        return localPowered;
    }

    public boolean isRemotePowered() {
        return remotePowered;
    }

    public boolean isOutputPowered() {
        return localPowered || remotePowered;
    }

    public void setLocalPowered(boolean powered) {
        if (localPowered != powered) {
            localPowered = powered;
            setChanged();
        }
    }

    public void setRemotePowered(boolean powered) {
        if (remotePowered != powered) {
            remotePowered = powered;
            setChanged();
        }
    }

    public @Nullable Direction getOutputDirection() {
        return outputDirection;
    }

    public void setOutputDirection(@Nullable Direction direction) {
        if (outputDirection != direction) {
            outputDirection = direction;
            setChanged();
        }
    }

    public void serverTick() {
        if (level != null && !level.isClientSide) {
            FactoryRelay.transport(this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (hasFactoryId()) {
            tag.putInt(FACTORY_ID_TAG, factoryId);
        }
        if (!pendingStack.isEmpty()) {
            tag.put(PENDING_STACK_TAG, pendingStack.save(registries));
            if (pendingInput != null) {
                tag.putString(PENDING_INPUT_TAG, pendingInput.getSerializedName());
            }
        }
        tag.putBoolean(LOCAL_POWERED_TAG, localPowered);
        tag.putBoolean(REMOTE_POWERED_TAG, remotePowered);
        if (outputDirection != null) {
            tag.putString(OUTPUT_DIRECTION_TAG, outputDirection.getSerializedName());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        factoryId = tag.contains(FACTORY_ID_TAG) ? tag.getInt(FACTORY_ID_TAG) : -1;
        pendingStack = tag.contains(PENDING_STACK_TAG)
                ? ItemStack.parse(registries, tag.getCompound(PENDING_STACK_TAG)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
        pendingInput = tag.contains(PENDING_INPUT_TAG)
                ? Direction.byName(tag.getString(PENDING_INPUT_TAG))
                : null;
        localPowered = tag.getBoolean(LOCAL_POWERED_TAG);
        remotePowered = tag.getBoolean(REMOTE_POWERED_TAG);
        outputDirection = tag.contains(OUTPUT_DIRECTION_TAG)
                ? Direction.byName(tag.getString(OUTPUT_DIRECTION_TAG))
                : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        loadAdditional(tag == null ? new CompoundTag() : tag, registries);
    }

    public record PreviewBlock(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
    }

    public interface Ticker {
        void tick();
    }

    public abstract List<PreviewBlock> getPreviewBlocks();
}
