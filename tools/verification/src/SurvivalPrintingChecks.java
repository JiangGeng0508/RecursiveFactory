package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.RecursiveFactoryBlockEntity;
import com.zinzinc.recursivefactory.compat.FactoryCannonAccess;
import com.zinzinc.recursivefactory.world.FactoryBlueprint;
import com.zinzinc.recursivefactory.world.FactoryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Real survival cannon payments, safe-NBT projectiles and persisted in-flight work. */
final class SurvivalPrintingChecks {
    private static final BlockPos CHEST = new BlockPos(2, 0, 2);
    private static final BlockPos TANK = new BlockPos(4, 0, 2);
    private static final BlockPos FURNACE = new BlockPos(6, 0, 2);
    private static final BlockPos DEPOT = new BlockPos(8, 0, 2);
    private static final BlockPos BASIN = new BlockPos(10, 0, 2);
    private static final BlockPos NESTED = new BlockPos(2, 0, 6);
    private static final BlockPos ENERGY = new BlockPos(6, 0, 6);

    static void run(ServerLevel outside, ServerLevel inside, BiConsumer<Boolean, String> check) {
        try {
            var data = FactoryData.get(outside.getServer());
            var source = door(outside, new BlockPos(1500, 200, 100));
            door(outside, source.getBlockPos().east());
            var origin = FactoryBlueprint.origin(data.factory(source.getFactoryId()).anchorCell());
            source.activateRedstone(Direction.NORTH);
            seedBuffers(source);
            seedStorage(inside, origin);

            FurnaceBlockEntity furnace = (FurnaceBlockEntity) place(inside, origin.offset(FURNACE), Blocks.FURNACE);
            furnace.setItem(0, new ItemStack(Items.RAW_IRON, 7));
            furnace.setItem(1, new ItemStack(Items.COAL, 9));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
            CompoundTag furnaceData = furnace.saveWithFullMetadata(inside.registryAccess());
            furnaceData.putShort("BurnTime", (short) 123);
            furnaceData.putShort("CookTime", (short) 80);
            furnaceData.putShort("CookTimeTotal", (short) 200);
            furnace.loadWithComponents(furnaceData, inside.registryAccess());
            var depot = (DepotBlockEntity) place(inside, origin.offset(DEPOT), AllBlocks.DEPOT.get());
            depot.setHeldItem(new ItemStack(Items.GOLD_INGOT, 6));
            var basin = (BasinBlockEntity) place(inside, origin.offset(BASIN), AllBlocks.BASIN.get());
            basin.getInputInventory().setStackInSlot(0, new ItemStack(Items.COPPER_INGOT, 8));
            basin.getOutputInventory().setStackInSlot(0, new ItemStack(Items.GOLD_NUGGET, 3));

            var child = door(inside, origin.offset(NESTED));
            seedBuffers(child);
            var childOrigin = FactoryBlueprint.origin(data.factory(child.getFactoryId()).anchorCell());
            seedStorage(inside, childOrigin);

            Block accumulator = null;
            if (RecursiveFactory.powerAvailable()) {
                var id = ResourceLocation.fromNamespaceAndPath("electroenergetics", "accumulator");
                if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalStateException("Missing CEE accumulator");
                accumulator = BuiltInRegistries.BLOCK.get(id);
                var stacked = accumulator.defaultBlockState();
                for (var property : stacked.getProperties()) {
                    if (property.getName().equals("stack")) stacked = setProperty(stacked, property, "double_parallel");
                }
                inside.setBlockAndUpdate(origin.offset(ENERGY), stacked);
                BlockEntity battery = inside.getBlockEntity(origin.offset(ENERGY));
                CompoundTag charged = battery.saveWithFullMetadata(inside.registryAccess());
                charged.putDouble("Cell1Charge", 12000);
                charged.putDouble("Cell2Charge", 6000);
                charged.putBoolean("IsDoubleCell", true);
                battery.loadWithComponents(charged, inside.registryAccess());
                check.accept(battery.saveWithFullMetadata(inside.registryAccess()).getDouble("Cell1Charge") == 12000,
                        "conservation fixture contains a charged double CEE accumulator");
            }
            check.accept(furnace.getItem(0).getCount() == 7 && saved(inside, origin.offset(FURNACE)).getShort("CookTime") == 80
                            && depot.getHeldItem().getCount() == 6 && basin.getOutputInventory().getStackInSlot(0).getCount() == 3,
                    "conservation fixture contains fuel, work in progress, depot items and basin products");

            BlockPos target = source.getBlockPos().east(20);
            outside.getChunk(target); outside.getChunk(target.south(4));
            StructureTemplate template = new StructureTemplate();
            template.fillFromWorld(outside, source.getBlockPos(), new Vec3i(2, 1, 1), false, null);
            Path path = Path.of("schematics/uploaded/audit/conservation.nbt");
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(template.save(new CompoundTag()), path);
            ItemStack schematic = SchematicItem.create(outside, "conservation.nbt", "audit");
            schematic.set(AllDataComponents.SCHEMATIC_ANCHOR, target);
            schematic.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);

            Map<Item, Integer> required = new LinkedHashMap<>();
            required.put(ModBlocks.RECURSIVE_FACTORY_ITEM.get(), 3);
            required.put(Items.CHEST, 2);
            required.put(AllBlocks.FLUID_TANK.asItem(), 2);
            required.put(Items.FURNACE, 1);
            required.put(AllBlocks.DEPOT.asItem(), 1);
            required.put(AllBlocks.BASIN.asItem(), 1);
            if (accumulator != null) required.put(accumulator.asItem(), 2);
            ItemStackHandler supply = new ItemStackHandler(required.size());
            int slot = 0, chestSlot = -1;
            for (var entry : required.entrySet()) {
                if (entry.getKey() == Items.CHEST) chestSlot = slot;
                else supply.setStackInSlot(slot, new ItemStack(entry.getKey(), entry.getValue() + 1));
                slot++;
            }
            TestCannon cannon = new TestCannon(target.south(4));
            cannon.setLevel(outside);
            cannon.inventory.setStackInSlot(0, schematic);
            cannon.remainingFuel = 100;
            cannon.resume(); cannon.step();
            if (accumulator != null) check.accept(cannon.checklist.required.getInt(accumulator.asItem()) == 2,
                    "survival checklist includes both cells of a double CEE accumulator");
            cannon.attachedInventories.add(supply);
            for (int i = 0; i < 30 && (cannon.missingItem == null || !cannon.missingItem.is(Items.CHEST)); i++) {
                cannon.resume(); cannon.step(); cannon.land(outside);
            }
            var printedDoor = (RecursiveFactoryBlockEntity) outside.getBlockEntity(target);
            BlockPos printedOrigin = FactoryBlueprint.origin(data.factory(printedDoor.getFactoryId()).anchorCell());
            check.accept(!cannon.hasCreativeCrate && cannon.missingItem != null && cannon.missingItem.is(Items.CHEST)
                            && supply.getStackInSlot(0).getCount() == 3 && inside.getBlockState(printedOrigin.offset(CHEST)).isAir(),
                    "survival cannon pays for the entrance and pauses before a chest when its material is missing");
            CompoundTag pendingSupply = supply.serializeNBT(outside.registryAccess());
            int pausedFuel = cannon.remainingFuel;
            for (int i = 0; i < 4; i++) cannon.step();
            check.accept(pendingSupply.equals(supply.serializeNBT(outside.registryAccess())) && pausedFuel == cannon.remainingFuel,
                    "missing-material retries consume neither materials nor fuel");

            supply.setStackInSlot(chestSlot, new ItemStack(Items.CHEST, 3));
            cannon.remainingFuel = 0;
            cannon.resume(); cannon.step();
            check.accept(cannon.statusMsg.equals("noGunpowder") && supply.getStackInSlot(chestSlot).getCount() == 3
                            && cannon.flyingBlocks.isEmpty(), "zero fuel prevents room construction without charging materials");
            cannon.remainingFuel = pausedFuel;
            cannon.resume(); cannon.step();
            check.accept(supply.getStackInSlot(chestSlot).getCount() == 2 && cannon.remainingFuel == pausedFuel - 1
                            && !cannon.flyingBlocks.isEmpty(), "a room projectile pays once for its chest and one fuel unit before flight");

            CompoundTag progress = cannon.saveWithFullMetadata(outside.registryAccess());
            TestCannon resumed = new TestCannon(target.south(4));
            resumed.setLevel(outside);
            resumed.loadWithComponents(progress, outside.registryAccess());
            check.accept(resumed.remainingFuel == cannon.remainingFuel && resumed.flyingBlocks.size() == cannon.flyingBlocks.size(),
                    "saving in flight preserves paid fuel and pending room projectiles");
            resumed.step();
            resumed.attachedInventories.add(supply);
            for (int i = 0; i < 100 && !resumed.statusMsg.equals("finished"); i++) {
                resumed.resume(); resumed.step(); resumed.land(outside);
            }
            check.accept(resumed.statusMsg.equals("finished"), "survival room and nested room finish after an in-flight reload");
            check.accept(outside.getBlockEntity(target.east()) instanceof RecursiveFactoryBlockEntity expanded
                            && expanded.getFactoryId() == printedDoor.getFactoryId()
                            && data.factory(printedDoor.getFactoryId()).cells().size() == 2,
                    "survival printing restores expanded entrances into the same two-cell room");
            slot = 0;
            for (Item item : required.keySet()) {
                ItemStack leftover = supply.getStackInSlot(slot++);
                check.accept(leftover.is(item) && leftover.getCount() == 1,
                        "survival printing consumes exactly the placed material count: " + BuiltInRegistries.ITEM.getKey(item));
            }

            checkStorage(inside, printedOrigin, check, "root");
            var printedChild = (RecursiveFactoryBlockEntity) inside.getBlockEntity(printedOrigin.offset(NESTED));
            checkStorage(inside, FactoryBlueprint.origin(data.factory(printedChild.getFactoryId()).anchorCell()), check, "nested");
            check.accept(!printedDoor.hasPendingItems() && printedDoor.getPendingFluid().isEmpty()
                            && !printedChild.hasPendingItems() && printedChild.getPendingFluid().isEmpty()
                            && printedDoor.isRedstoneEnabled(Direction.NORTH),
                    "printed root and nested entrances clear item/fluid buffers but retain redstone settings");
            var copiedFurnace = (FurnaceBlockEntity) inside.getBlockEntity(printedOrigin.offset(FURNACE));
            CompoundTag copyData = copiedFurnace.saveWithFullMetadata(inside.registryAccess());
            check.accept(copiedFurnace.isEmpty() && copyData.getShort("CookTime") == 0 && copyData.getShort("BurnTime") == 0,
                    "printed furnace carries no inputs, products, fuel or paid processing progress");
            check.accept(((DepotBlockEntity) inside.getBlockEntity(printedOrigin.offset(DEPOT))).getHeldItem().isEmpty(),
                    "printed depot does not copy its displayed gold ingots");
            var copiedBasin = (BasinBlockEntity) inside.getBlockEntity(printedOrigin.offset(BASIN));
            check.accept(copiedBasin.getInputInventory().isEmpty() && copiedBasin.getOutputInventory().isEmpty(),
                    "printed basin does not copy ingredients or completed products");
            if (accumulator != null) {
                check.accept(saved(inside, printedOrigin.offset(ENERGY)).getDouble("Cell1Charge") == 0
                                && saved(inside, printedOrigin.offset(ENERGY)).getDouble("Cell2Charge") == 0
                                && saved(inside, origin.offset(ENERGY)).getDouble("Cell1Charge") == 12000
                                && saved(inside, origin.offset(ENERGY)).getDouble("Cell2Charge") == 6000,
                        "printed double CEE accumulator starts empty and leaves both source charges untouched");
            }
            check.accept(storageIntact(inside, origin) && storageIntact(inside, childOrigin)
                            && source.getPendingStack(0).getCount() == 11 && child.getPendingStack(0).getCount() == 11
                            && source.getPendingFluid().getAmount() == 700 && child.getPendingFluid().getAmount() == 700
                            && furnace.getItem(0).getCount() == 7 && furnace.getItem(1).getCount() == 9 && furnace.getItem(2).getCount() == 4
                            && saved(inside, origin.offset(FURNACE)).getShort("CookTime") == 80
                            && saved(inside, origin.offset(FURNACE)).getShort("BurnTime") == 123
                            && depot.getHeldItem().getCount() == 6 && basin.getInputInventory().getStackInSlot(0).getCount() == 8
                            && basin.getOutputInventory().getStackInSlot(0).getCount() == 3,
                    "printing and reload leave every source inventory, fluid buffer and processing state unchanged");
        } catch (Exception error) { throw new IllegalStateException("survival conservation", error); }
    }

    private static RecursiveFactoryBlockEntity door(ServerLevel level, BlockPos pos) {
        BlockHelper.placeSchematicBlock(level, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        return (RecursiveFactoryBlockEntity) level.getBlockEntity(pos);
    }
    private static BlockEntity place(ServerLevel level, BlockPos pos, Block block) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        return level.getBlockEntity(pos);
    }
    private static CompoundTag saved(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
    }
    private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState setProperty(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow());
    }
    private static void seedBuffers(RecursiveFactoryBlockEntity door) {
        door.offer(new ItemStack(Items.EMERALD, 11), Direction.NORTH);
        door.offerFluid(new FluidStack(Fluids.LAVA, 700), Direction.NORTH);
    }
    private static void seedStorage(ServerLevel level, BlockPos origin) {
        ((ChestBlockEntity) place(level, origin.offset(CHEST), Blocks.CHEST)).setItem(0, new ItemStack(Items.DIAMOND, 13));
        var tank = (FluidTankBlockEntity) place(level, origin.offset(TANK), AllBlocks.FLUID_TANK.get());
        tank.getTankInventory().setFluid(new FluidStack(Fluids.LAVA, 3000));
    }
    private static boolean storageIntact(ServerLevel level, BlockPos origin) {
        return ((ChestBlockEntity) level.getBlockEntity(origin.offset(CHEST))).getItem(0).getCount() == 13
                && ((FluidTankBlockEntity) level.getBlockEntity(origin.offset(TANK))).getTankInventory().getFluidAmount() == 3000;
    }
    private static void checkStorage(ServerLevel level, BlockPos origin, BiConsumer<Boolean, String> check, String label) {
        check.accept(((ChestBlockEntity) level.getBlockEntity(origin.offset(CHEST))).isEmpty(),
                "printed " + label + " chest contains no copied diamonds");
        check.accept(((FluidTankBlockEntity) level.getBlockEntity(origin.offset(TANK))).getTankInventory().isEmpty(),
                "printed " + label + " tank contains no copied lava");
    }
    private static final class TestCannon extends SchematicannonBlockEntity {
        TestCannon(BlockPos pos) { super(AllBlockEntityTypes.SCHEMATICANNON.get(), pos, AllBlocks.SCHEMATICANNON.getDefaultState()); }
        void resume() { state = State.RUNNING; }
        void step() { ((FactoryCannonAccess) (Object) this).recursivefactory$cooldown(0); tickPrinter(); }
        void land(ServerLevel level) {
            for (var shot : flyingBlocks) { shot.ticksRemaining = 0; shot.update(level); }
            flyingBlocks.clear();
        }
    }
}
