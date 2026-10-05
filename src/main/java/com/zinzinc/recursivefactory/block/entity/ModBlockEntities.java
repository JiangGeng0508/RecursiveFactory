package com.zinzinc.recursivefactory.block.entity;

import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
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
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                type,
                (blockEntity, side) -> new EndpointFluidHandler(blockEntity, side)
        );
    }

    /**
     * The one tank this end offers, and the fluid side of the link: the fluid it is holding on to, or -
     * when it is holding none - what a pull would take, so that a pipe asking what is here before draining
     * finds the far end's tanks (see FactoryRelay#extractFluid). A pipe pushing fluid in is buffered the
     * same way an item is, and carried across on the next tick.
     */
    private record EndpointFluidHandler(EndpointBlockEntity endpoint, Direction side) implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            if (tank != 0) {
                return FluidStack.EMPTY;
            }
            FluidStack waiting = endpoint.getPendingFluid();
            return waiting.isEmpty()
                    ? FactoryRelay.extractFluid(endpoint, side, FluidStack.EMPTY,
                            EndpointBlockEntity.FLUID_CAPACITY, true)
                    : waiting;
        }

        @Override
        public int getTankCapacity(int tank) {
            return EndpointBlockEntity.FLUID_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return action.simulate()
                    ? endpoint.roomForFluid(resource, side)
                    : endpoint.offerFluid(resource, side);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FactoryRelay.extractFluid(endpoint, side, resource, resource.getAmount(), action.simulate());
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FactoryRelay.extractFluid(endpoint, side, FluidStack.EMPTY, maxDrain, action.simulate());
        }
    }

    private record EndpointItemHandler(EndpointBlockEntity endpoint, Direction side) implements IItemHandler {
        @Override
        public int getSlots() {
            int slots = EndpointBlockEntity.PENDING_SLOTS;
            for (IItemHandler source : FactoryRelay.itemSources(endpoint, side)) {
                slots += source.getSlots();
            }
            return slots;
        }

        /**
         * Input buffers come first, followed by every slot in the far end's inventories. Buffers only
         * accept items; remote slots only allow extraction. Reading and extracting a remote slot must
         * address the same stack, so a filtered funnel can reach items beyond the first occupied slot.
         */
        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot < EndpointBlockEntity.PENDING_SLOTS) {
                return endpoint.getPendingStack(slot);
            }
            RemoteSlot remote = remoteSlot(slot);
            return remote == null ? ItemStack.EMPTY : remote.handler().getStackInSlot(remote.slot());
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return endpoint.offer(slot, stack, side, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            RemoteSlot remote = remoteSlot(slot);
            return remote == null ? ItemStack.EMPTY : remote.handler().extractItem(remote.slot(), amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            if (slot >= 0 && slot < EndpointBlockEntity.PENDING_SLOTS) {
                return 64;
            }
            RemoteSlot remote = remoteSlot(slot);
            return remote == null ? 0 : remote.handler().getSlotLimit(remote.slot());
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= 0 && slot < EndpointBlockEntity.PENDING_SLOTS && side != null && endpoint.hasFactoryId();
        }

        private record RemoteSlot(IItemHandler handler, int slot) { }

        private RemoteSlot remoteSlot(int slot) {
            slot -= EndpointBlockEntity.PENDING_SLOTS;
            if (slot < 0) {
                return null;
            }
            for (IItemHandler source : FactoryRelay.itemSources(endpoint, side)) {
                int size = source.getSlots();
                if (slot < size) {
                    return new RemoteSlot(source, slot);
                }
                slot -= size;
            }
            return null;
        }
    }
}
