package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RecursiveFactory.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RecursiveFactoryBlockEntity>> RECURSIVE_FACTORY =
            BLOCK_ENTITY_TYPES.register(
                    "recursive_factory",
                    () -> BlockEntityType.Builder.of(
                            RecursiveFactoryBlockEntity::new,
                            ModBlocks.RECURSIVE_FACTORY.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryBarrierBlockEntity>> FACTORY_BARRIER =
            BLOCK_ENTITY_TYPES.register(
                    "factory_barrier",
                    () -> BlockEntityType.Builder.of(
                            FactoryBarrierBlockEntity::new,
                            ModBlocks.FACTORY_BARRIER.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModBlockEntities::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerEndpoint(event, RECURSIVE_FACTORY.get());
        registerEndpoint(event, FACTORY_BARRIER.get());
    }

    private static void registerEndpoint(RegisterCapabilitiesEvent event, BlockEntityType<? extends EndpointBlockEntity> type) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                type,
                (blockEntity, side) -> new EndpointItemHandler(blockEntity, side)
        );
    }

    private record EndpointItemHandler(EndpointBlockEntity endpoint, Direction side) implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        /**
         * The one slot this end offers: the stack it is holding on to, or - when it is holding none - what
         * an extraction would take, so that a block asking what is here before pulling finds the far end's
         * items (see FactoryRelay#extract).
         */
        @Override
        public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
            if (slot != 0) {
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
            net.minecraft.world.item.ItemStack waiting = endpoint.getPendingStack();
            return waiting.isEmpty() ? FactoryRelay.extract(endpoint, side, 64, true) : waiting;
        }

        @Override
        public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
            if (slot != 0) {
                return stack;
            }
            if (simulate) {
                return endpoint.getPendingStack().isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : stack;
            }
            return endpoint.offer(stack, side);
        }

        @Override
        public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == 0
                    ? FactoryRelay.extract(endpoint, side, amount, simulate)
                    : net.minecraft.world.item.ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
            return slot == 0;
        }
    }
}