package com.zinzinc.recursivefactory;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.ModBlocks;
import com.zinzinc.recursivefactory.block.entity.ModBlockEntities;
import com.zinzinc.recursivefactory.data.ModAttachments;
import com.zinzinc.recursivefactory.network.ModNetworking;
import com.zinzinc.recursivefactory.world.FactoryDimension;
import com.zinzinc.recursivefactory.world.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.chunk_loading.PlayerChunkLoading;

@Mod(RecursiveFactory.MODID)
public final class RecursiveFactory {
    public static final String MODID = "recursivefactory";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** How often {@link #onServerTick} re-checks Immersive Portals' per-player performance level. */
    private static final int PERFORMANCE_LEVEL_INTERVAL = 20;
    private static int performanceLevelTimer;

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.recursivefactory.main"))
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .icon(() -> ModBlocks.RECURSIVE_FACTORY_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.RECURSIVE_FACTORY_ITEM.get());
                        output.accept(ModBlocks.RECURSIVE_FACTORY_SHORT_ITEM.get());
                        output.accept(ModBlocks.MIRROR_FACTORY_ITEM.get());
                    })
                    .build());

    public RecursiveFactory(IEventBus modEventBus) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModEntities.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(ModNetworking::register);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("Recursive Factory initialized");
    }

    private void onServerStarted(ServerStartedEvent event) {
        FactoryDimension.initialize(event.getServer());
    }

    /**
     * Keeps Immersive Portals' per-player performance level at good.
     *
     * <p>That field is written by nothing but the client's report, which Immersive Portals sends from a snooper
     * hook that in practice never fires, so it keeps its initial value of bad for the whole session - and bad
     * means the portal chunk loader around a doorway is one chunk wide. The room then stops being kept loaded for
     * anyone standing more than sixteen blocks from the entrance, which is the whole point of a factory that is
     * meant to be looked at from across the yard.
     */
    private void onServerTick(ServerTickEvent.Post event) {
        if (++performanceLevelTimer < PERFORMANCE_LEVEL_INTERVAL) {
            return;
        }
        performanceLevelTimer = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PlayerChunkLoading info = ImmPtlChunkTracking.getPlayerInfo(player);
            if (info.performanceLevel != PerformanceLevel.good) {
                LOGGER.info("Immersive Portals server performance level of {} was {}; forcing good",
                        player.getName().getString(), info.performanceLevel);
                info.performanceLevel = PerformanceLevel.good;
            }
        }
    }
}
