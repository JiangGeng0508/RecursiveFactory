package com.zinzinc.recursivefactory;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryItem;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.network.ModNetworking;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(RecursiveFactory.MODID)
public final class RecursiveFactory {
    public static final String MODID = "recursivefactory";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.recursivefactory.main"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    // Every colour kind is the same item with the colour carried as a component, so
                    // the tab lists all sixteen of them.
                    .icon(() -> RecursiveFactoryItem.colored(0))
                    .displayItems((parameters, output) -> {
                        for (int colorIndex = 0; colorIndex < FactoryColors.COLOR_COUNT; colorIndex++) {
                            output.accept(RecursiveFactoryItem.colored(colorIndex));
                        }
                        output.accept(ModBlocks.FACTORY_BARRIER_ITEM.get());
                    })
                    .build());

    public RecursiveFactory(IEventBus modEventBus) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModDataComponents.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(ModNetworking::register);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        LOGGER.info("Recursive Factory initialized");
    }

    private void onServerStarted(ServerStartedEvent event) {
        FactoryDimension.initialize(event.getServer());
    }
}
