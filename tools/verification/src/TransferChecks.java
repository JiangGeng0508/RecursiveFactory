package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.world.*;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Real capabilities and Create networks; injected only by verify.init.gradle. */
final class TransferChecks {
    static void run(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        boolean expected = !ModList.get().isLoaded("createendertransmission");
        check.accept(FactoryConfig.KINETIC_TRANSFER_ENABLED.getDefault() == expected
                        && FactoryConfig.FLUID_TRANSFER_ENABLED.getDefault() == expected
                        && FactoryConfig.ITEM_TRANSFER_ENABLED.getDefault() == expected,
                "transfer defaults follow actual createendertransmission presence: " + !expected);
        String loaded = "" + bit(FactoryConfig.kineticTransferEnabled()) + bit(FactoryConfig.fluidTransferEnabled())
                + bit(FactoryConfig.itemTransferEnabled());
        check.accept(loaded.equals(System.getProperty("rfaudit.expectedTransfers", expected ? "111" : "000")),
                "loaded configuration preserves defaults or explicit choices: " + loaded);
        try {
            settings(true, true, true);
            capabilities(outside, inside, check);
            kinetics(outside, inside, check);
        } finally {
            // The existing project suite exercises enabled relays, even in an Ender Transmission pack.
            // ConfigValue.set changes memory only: the original on-disk defaults remain available for inspection.
            settings(true, true, true);
        }
    }

    private static int bit(boolean value) { return value ? 1 : 0; }

    private static void settings(boolean kinetic, boolean fluid, boolean item) {
        FactoryConfig.KINETIC_TRANSFER_ENABLED.set(kinetic);
        FactoryConfig.FLUID_TRANSFER_ENABLED.set(fluid);
        FactoryConfig.ITEM_TRANSFER_ENABLED.set(item);
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }

    private static void capabilities(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        var entrance = door(outside, new BlockPos(900, 200, 100));
        var room = FactoryData.get(outside.getServer()).factory(entrance.getFactoryId());
        BlockPos wall = FactoryDimension.wallLine(room, room.anchorCell(), Direction.NORTH).getFirst();
        var barrier = (EndpointBlockEntity) inside.getBlockEntity(wall);
        entrance.activateRedstone(Direction.NORTH);
        barrier.activateRedstone(Direction.SOUTH);
        BlockPos receive = FactoryDimension.inward(room, wall, Direction.NORTH);
        inside.setBlockAndUpdate(receive, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) inside.getBlockEntity(receive);
        BlockPos tankWall = FactoryDimension.wallLine(room, room.anchorCell(), Direction.SOUTH).getFirst();
        BlockPos tankPos = FactoryDimension.inward(room, tankWall, Direction.SOUTH);
        inside.setBlockAndUpdate(tankPos, AllBlocks.FLUID_TANK.getDefaultState());
        var tank = inside.getCapability(Capabilities.FluidHandler.BLOCK, tankPos, Direction.SOUTH);
        var items = outside.getCapability(Capabilities.ItemHandler.BLOCK, entrance.getBlockPos(), Direction.NORTH);
        var fluids = outside.getCapability(Capabilities.FluidHandler.BLOCK, entrance.getBlockPos(), Direction.NORTH);
        var wallItems = inside.getCapability(Capabilities.ItemHandler.BLOCK, wall, Direction.SOUTH);
        var wallFluids = inside.getCapability(Capabilities.FluidHandler.BLOCK, wall, Direction.SOUTH);
        for (int mask = 0; mask < 8; mask++) {
            settings(true, true, true);
            chest.clearContent();
            tank.drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
            entrance.setTransportResult(ItemStack.EMPTY);
            entrance.setFluidTransportResult(Integer.MAX_VALUE);
            barrier.setFluidTransportResult(Integer.MAX_VALUE);
            items.insertItem(0, new ItemStack(Items.STONE, 12), false);
            entrance.offerFluid(new FluidStack(Fluids.WATER, 500), Direction.SOUTH);
            barrier.offerFluid(new FluidStack(Fluids.WATER, 1000), Direction.SOUTH);
            boolean kinetic = (mask & 4) != 0, fluid = (mask & 2) != 0, item = (mask & 1) != 0;
            settings(kinetic, fluid, item);
            String label = " [kinetic/fluid/item=" + bit(kinetic) + bit(fluid) + bit(item) + "]";
            FactoryRelay.transport(entrance);
            FactoryRelay.transportFluid(entrance);
            check.accept(chest.getItem(0).getCount() == (item ? 12 : 0)
                            && entrance.getPendingStack(0).getCount() == (item ? 0 : 12)
                            && items.insertItem(0, new ItemStack(Items.DIAMOND), true).isEmpty() == item
                            && (items.getSlots() > 0) == item && (wallItems.getSlots() > 0) == item,
                    "item toggle controls cached capabilities and pending delivery" + label);
            chest.setItem(1, new ItemStack(Items.EMERALD, 3));
            check.accept(items.extractItem(EndpointBlockEntity.PENDING_SLOTS + 1, 1, true).is(Items.EMERALD) == item
                            && !FactoryRelay.extract(entrance, Direction.NORTH, 1, true).isEmpty() == item,
                    "item toggle controls remote slot and direct extraction" + label);
            check.accept(tank.getFluidInTank(0).getAmount() == (fluid ? 500 : 0)
                            && entrance.getPendingFluid().getAmount() == (fluid ? 0 : 500)
                            && fluids.drain(100, FluidAction.SIMULATE).getAmount() == (fluid ? 100 : 0)
                            && fluids.drain(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE).getAmount() == (fluid ? 100 : 0)
                            && barrier.getPendingFluid().getAmount() == (fluid ? 900 : 1000)
                            && fluids.getTanks() == (fluid ? 1 : 0) && wallFluids.getTanks() == (fluid ? 1 : 0),
                    "fluid toggle controls cached capabilities, pending delivery and both drains" + label);
            if (!item) check.accept(items.insertItem(0, new ItemStack(Items.DIAMOND), false).getCount() == 1
                            && items.extractItem(EndpointBlockEntity.PENDING_SLOTS + 1, 1, false).isEmpty()
                            && items.getStackInSlot(0).isEmpty() && wallItems.getSlotLimit(0) == 0,
                    "disabled item handlers reject execution without exposing buffers" + label);
            if (!fluid) check.accept(fluids.fill(new FluidStack(Fluids.WATER, 10), FluidAction.SIMULATE) == 0
                            && fluids.fill(new FluidStack(Fluids.WATER, 10), FluidAction.EXECUTE) == 0
                            && wallFluids.fill(new FluidStack(Fluids.WATER, 10), FluidAction.EXECUTE) == 0
                            && fluids.getFluidInTank(0).isEmpty() && wallFluids.getTankCapacity(0) == 0,
                    "disabled fluid handlers reject filling without exposing buffers" + label);
            check.accept(KineticRelay.takesShaft(inside, wall, barrier.getBlockState(), Direction.SOUTH) == kinetic,
                    "kinetic toggle is independent of items and fluids" + label);
            int[] signals = new int[6];
            signals[Direction.NORTH.ordinal()] = 9;
            FactoryRelay.syncPower(entrance, signals);
            check.accept(barrier.getOutputPower(Direction.SOUTH) == 9, "redstone remains available" + label);
            FactoryRelay.syncPower(entrance, new int[6]);
            settings(true, true, true);
            FactoryRelay.transport(entrance);
            FactoryRelay.transportFluid(entrance);
            check.accept(!entrance.hasPendingItems() && entrance.getPendingFluid().isEmpty()
                            && chest.getItem(0).getCount() == 12 && tank.getFluidInTank(0).getAmount() == 500,
                    "reenabling delivers preserved buffers exactly once" + label);
        }
    }

    private static void kinetics(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        var entrance = door(outside, new BlockPos(920, 200, 100));
        var room = FactoryData.get(outside.getServer()).factory(entrance.getFactoryId());
        var walls = new ArrayList<EndpointBlockEntity>();
        // Tick every shell block, as a real loaded room does. This also detects stale descendants when
        // a network contains multiple wall blocks between its generated source and the output shaft.
        var cell = room.anchorCell();
        for (BlockPos pos : BlockPos.betweenClosed(cell.roomX(), cell.baseY(), cell.roomZ(),
                cell.roomX() + 15, cell.baseY() + 15, cell.roomZ() + 15)) {
            if (inside.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint) walls.add(endpoint);
        }
        BlockPos wall = FactoryDimension.wallLine(room, cell, Direction.NORTH).getLast();
        BlockPos shaftPos = FactoryDimension.inward(room, wall, Direction.NORTH);
        inside.setBlockAndUpdate(shaftPos, AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        inside.setBlockAndUpdate(shaftPos.south(), AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        var shaft = (KineticBlockEntity) inside.getBlockEntity(shaftPos);
        var last = (KineticBlockEntity) inside.getBlockEntity(shaftPos.south());
        BlockPos motorPos = entrance.getBlockPos().north();
        outside.setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        var motor = (CreativeMotorBlockEntity) outside.getBlockEntity(motorPos);
        motor.initialize();
        for (boolean enabled : new boolean[]{true, false, true, false, true}) {
            settings(enabled, true, true);
            for (int step = 0; step < 3; step++) {
                entrance.tick();
                for (var endpoint : walls) endpoint.tick();
                shaft.tick(); last.tick(); motor.tick();
            }
            check.accept((Math.abs(last.getTheoreticalSpeed()) > 0) == enabled
                            && (Math.abs(entrance.getTheoreticalSpeed()) > 0) == enabled
                            && (enabled || walls.stream().allMatch(endpoint -> endpoint.getTheoreticalSpeed() == 0
                                    && endpoint.getGeneratedSpeed() == 0 && !endpoint.hasNetwork())),
                    "live kinetic network " + (enabled ? "connects or recovers" : "fully detaches")
                            + ": entrance=" + entrance.getTheoreticalSpeed() + ", shaft=" + last.getTheoreticalSpeed());
        }
    }
}
