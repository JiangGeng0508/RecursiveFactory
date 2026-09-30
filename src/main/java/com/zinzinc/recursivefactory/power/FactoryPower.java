package com.zinzinc.recursivefactory.power;

import com.zinzinc.recursivefactory.config.FactoryConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The electricity half of the mod, which only exists when Create: Electro Energetics is installed.
 *
 * <p>A factory's face carries whatever is put against it through to the room's matching wall: items,
 * fluids, redstone and rotation already do (see FactoryRelay and KineticRelay). This is the same link for
 * electricity, and it stands on the factory's own surface: a node on the entrance block's face outside and
 * a node on the room's wall inside, tied together (see FactoryPowerNodes and FactoryPowerLinks), so the
 * room's grid and the grid outside are one grid as far as the player is concerned. No block and no item of
 * ours stands in it - both nodes are the electricity mod's own.
 *
 * <p>The electricity mod is optional, so nothing in this package may be touched unless the mod is there
 * (RecursiveFactory#powerAvailable asks, on a class that names none of its types): every class here names
 * one of its types, and loading one without the mod installed would take the whole game with it.
 */
public final class FactoryPower {
    private FactoryPower() {
    }

    /**
     * Wires the mod into the electricity mod's own tick. Called once, from the mod constructor, and only
     * while the electricity mod is installed.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(FactoryPowerNodes::onAddToElectricGraph);
        NeoForge.EVENT_BUS.addListener(FactoryPowerNodes::onFinishElectricSimulation);
    }

    /** Forgets the nodes and the links the server before this one was holding. */
    public static void initialize() {
        FactoryPowerNodes.reset();
        FactoryPowerLinks.reset();
    }

    /**
     * One look at the factory faces, taken at the head of the server tick, before the electricity mod's
     * own simulation for the tick: the nodes are put where the factories stand and the links are settled,
     * so that what is worked out here is what that simulation is run with.
     */
    public static void tick(MinecraftServer server) {
        if (!FactoryConfig.powerLinkEnabled()) {
            return;
        }
        FactoryPowerNodes.tick(server);
        FactoryPowerLinks.tick();
    }
}
