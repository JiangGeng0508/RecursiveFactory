package com.zinzinc.recursivefactory;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllCreativeModeTabs;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.RecursiveFactoryItem;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.config.FactoryConfig;
import com.zinzinc.recursivefactory.data.FactoryColors;
import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.data.ModDataComponents;
import com.zinzinc.recursivefactory.network.ModNetworking;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
                    // Last in the row, behind Create's two tabs: the frame is built out of Create
                    // machines, so it belongs beside them rather than in the middle of the vanilla
                    // tabs. Anchoring on a tab that none of them points at leaves every vanilla tab
                    // where it has always been - the ten of them still fill the first page on their
                    // own - and the front of the row stays free for anyone else.
                    .withTabsBefore(AllCreativeModeTabs.PALETTES_CREATIVE_TAB.getKey())
                    // Every colour kind is the same item with the colour carried as a component, so
                    // the tab lists all sixteen of them.
                    .icon(() -> RecursiveFactoryItem.colored(0))
                    .displayItems((parameters, output) -> {
                        for (int colorIndex = 0; colorIndex < FactoryColors.COLOR_COUNT; colorIndex++) {
                            output.accept(RecursiveFactoryItem.colored(colorIndex));
                        }
                        output.accept(ModBlocks.FACTORY_BARRIER_ITEM.get());
                        output.accept(ModBlocks.FACTORY_PRINTER_ITEM.get());
                        output.accept(ModBlocks.FACTORY_BLUEPRINT_ITEM.get());
                    })
                    .build());

    public RecursiveFactory(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModDataComponents.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, FactoryConfig.SPEC);
        modEventBus.addListener(ModNetworking::register);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onBlockBreakAttempt);
        LOGGER.info("Recursive Factory initialized");
    }

    /**
     * A room is walled with barriers and a survival player is not supposed to be able to open one up. The
     * block itself already answers with zero destroy progress in survival; this covers the rest, anything
     * that breaks the block without ever asking for that progress. Creative is left alone, so a room can
     * still be edited while it is being built.
     */
    private void onBlockBreakAttempt(BlockEvent.BreakEvent event) {
        if (!event.getPlayer().isCreative() && event.getState().is(ModBlocks.FACTORY_BARRIER.get())) {
            event.setCanceled(true);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        FactoryDimension.initialize(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        FactoryDimension.tick(event.getServer());
    }
}
