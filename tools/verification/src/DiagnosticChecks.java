package com.zinzinc.recursivefactory.probe;

import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.*;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.network.FactoryDiagnosticPackets;
import com.zinzinc.recursivefactory.preview.PreviewDisplayData;
import java.util.function.BiConsumer;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

final class DiagnosticChecks {
    static void run(ServerLevel outside, BiConsumer<Boolean, String> check) {
        BlockPos pos = new BlockPos(416, 160, 416);
        BlockHelper.placeSchematicBlock(outside, ModBlocks.RECURSIVE_FACTORY.get().defaultBlockState(), pos,
                ModBlocks.RECURSIVE_FACTORY_ITEM.toStack(), null);
        var entrance = (RecursiveFactoryBlockEntity) outside.getBlockEntity(pos);
        entrance.offer(new ItemStack(Items.IRON_INGOT, 5), Direction.NORTH);
        entrance.offer(new ItemStack(Items.GOLD_INGOT, 7), Direction.EAST);
        FactoryRelay.transport(entrance);
        var north = FactoryDiagnosticPackets.capture(entrance, Direction.NORTH);
        var east = FactoryDiagnosticPackets.capture(entrance, Direction.EAST);
        check.accept(north.getInt("ItemCount") == 5 && east.getInt("ItemCount") == 7
                && north.getBoolean("ItemBlocked") && east.getBoolean("ItemBlocked"),
                "diagnostics isolate each face and report actual failed transfer attempts");
        check.accept(north.getBoolean("Valid") && north.getInt("Wall") == Direction.NORTH.ordinal()
                && !north.toString().contains("minecraft:iron_ingot") && entrance.getPendingStack(0).getCount() == 5,
                "diagnostics describe wall mapping and counts without inventory contents or mutation");
        boolean enabled = FactoryConfig.ITEM_TRANSFER_ENABLED.get();
        try {
            FactoryConfig.ITEM_TRANSFER_ENABLED.set(false);
            check.accept(!FactoryDiagnosticPackets.capture(entrance, Direction.NORTH).getBoolean("Items"),
                    "diagnostics use the effective server transfer setting");
        } finally { FactoryConfig.ITEM_TRANSFER_ENABLED.set(enabled); }
        entrance.setTransportResult(0, ItemStack.EMPTY);
        FactoryRelay.transport(entrance);
        check.accept(!FactoryDiagnosticPackets.capture(entrance, Direction.NORTH).getBoolean("ItemBlocked")
                && FactoryDiagnosticPackets.capture(entrance, Direction.EAST).getBoolean("ItemBlocked"),
                "clearing one buffer removes its stale blocked indication without clearing another face");

        BlockPos beltPos = pos.east(5);
        outside.setBlockAndUpdate(beltPos, AllBlocks.BELT.getDefaultState());
        var belt = (BeltBlockEntity) outside.getBlockEntity(beltPos);
        belt.setController(beltPos); belt.beltLength = 2; belt.index = 0;
        var transported = new TransportedItemStack(new ItemStack(Items.IRON_INGOT, 3));
        transported.beltPosition = 0.5f; belt.getInventory().addItem(transported);
        check.accept(PreviewDisplayData.sample(belt).getCompound("Inventory").contains("Items"),
                "display adapter retains visible belt items while ordinary chest inventory is removed");

        for (String scene : new String[]{"connections", "expansion", "printing"}) {
            try (var input = DiagnosticChecks.class.getResourceAsStream("/assets/recursivefactory/ponder/" + scene + ".nbt")) {
                if (input == null) throw new IllegalStateException("Missing scene " + scene);
                CompoundTag tag = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                StructureTemplate template = new StructureTemplate(); template.load(outside.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), tag);
                check.accept(template.getSize().equals(new Vec3i(9, 4, 9)) && tag.getList("blocks", Tag.TAG_COMPOUND).size() > 81,
                        "original Ponder scene loads as a Minecraft structure: " + scene);
            } catch (Exception error) { throw new IllegalStateException(scene, error); }
        }
    }
}
