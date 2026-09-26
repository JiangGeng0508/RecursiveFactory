package com.zinzinc.recursivefactory.power;

import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.zinzinc.recursivefactory.RecursiveFactory;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The electricity half of the mod, which only exists when Create: Electro Energetics is installed.
 *
 * <p>A factory's face carries whatever is put against it through to the room's matching wall: items,
 * fluids, redstone and rotation already do (see FactoryRelay and KineticRelay). This is the same link for
 * electricity. A factory_power_terminal stands at each end - one against the entrance block outside, one
 * against the room's wall inside - and the two are tied together, so the room's grid and the grid outside
 * are one grid as far as the player is concerned.
 *
 * <p>The electricity mod is optional, so nothing in this package may be touched unless the mod is there
 * (RecursiveFactory#powerAvailable asks, on a class that names none of its types): every class here names
 * one of its types, and loading one without the mod installed would take the whole game with it.
 */
public final class FactoryPower {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RecursiveFactory.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RecursiveFactory.MODID);
    private static final DeferredRegister<SimulatedDeviceType<?>> DEVICES =
            DeferredRegister.create(CEERegistries.SIMULATED_DEVICE_TYPE, RecursiveFactory.MODID);

    /**
     * A terminal is a small machine rather than a block of stone: its model is a stub with a node on it,
     * so it must not be asked to hide the faces of the blocks around it, the way the printer is built.
     */
    private static final BlockBehaviour.Properties TERMINAL_PROPERTIES = BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(3.0F, 6.0F)
            .sound(SoundType.METAL)
            .noOcclusion()
            .pushReaction(PushReaction.BLOCK);

    /** The simulated device behind a terminal: what wires the terminal into the electrical simulation. */
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<FactoryPowerTerminalDevice>>
            TERMINAL_DEVICE = DEVICES.register("factory_power_terminal", () -> new SimulatedDeviceType<>(
                    ResourceLocation.fromNamespaceAndPath(RecursiveFactory.MODID, "factory_power_terminal"),
                    (type, level, pos, devices) -> new FactoryPowerTerminalDevice(level, pos, devices, type)));

    public static final DeferredBlock<FactoryPowerTerminalBlock> TERMINAL = BLOCKS.register(
            "factory_power_terminal",
            () -> new FactoryPowerTerminalBlock(TERMINAL_PROPERTIES));

    public static final DeferredItem<BlockItem> TERMINAL_ITEM = ITEMS.registerSimpleBlockItem(
            "factory_power_terminal",
            TERMINAL);

    private FactoryPower() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        DEVICES.register(modEventBus);
    }

    /** The tab entry for the terminal, which only exists while the electricity mod does. */
    public static void addItems(CreativeModeTab.Output output) {
        output.accept(TERMINAL_ITEM.get());
    }

    /**
     * One look at the electrical links, taken at the head of the server tick: before the electricity
     * mod's own simulation for the tick, so what is worked out here is what that simulation is run with.
     */
    public static void tick(MinecraftServer server) {
        if (!FactoryConfig.powerLinkEnabled()) {
            return;
        }
        FactoryPowerLinks.tick(server);
    }
}
