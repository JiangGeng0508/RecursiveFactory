package com.zinzinc.recursivefactory.client;

import com.zinzinc.recursivefactory.RecursiveFactory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = RecursiveFactory.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FactoryConfigScreen {
    private FactoryConfigScreen() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(RecursiveFactory.MODID).orElseThrow()
                .registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
