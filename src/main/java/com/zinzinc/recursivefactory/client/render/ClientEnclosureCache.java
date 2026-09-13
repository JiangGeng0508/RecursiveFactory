package com.zinzinc.recursivefactory.client.render;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.network.FactoryEnclosurePackets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Client side store of the sampled outside world. One entry per factory chunk, rebuilt into render
 * geometry lazily by {@link FactoryEnclosureRenderer}.
 */
public final class ClientEnclosureCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long REQUEST_INTERVAL_TICKS = 20L;
    private static final int MAX_ENTRIES = 16;

    private static final Map<ChunkPos, List<EndpointBlockEntity.PreviewBlock>> RECEIVED = new HashMap<>();
    private static final Map<ChunkPos, Built> BUILT = new HashMap<>();

    private static ClientLevel level;
    private static long lastRequestTick = Long.MIN_VALUE;

    private ClientEnclosureCache() {
    }

    public static void accept(int chunkX, int chunkZ, List<EndpointBlockEntity.PreviewBlock> blocks) {
        if (RECEIVED.size() >= MAX_ENTRIES && !RECEIVED.containsKey(new ChunkPos(chunkX, chunkZ))) {
            RECEIVED.clear();
            BUILT.clear();
        }
        RECEIVED.put(new ChunkPos(chunkX, chunkZ), blocks);
        LOGGER.info("Received factory enclosure for chunk [{}, {}] with {} blocks", chunkX, chunkZ, blocks.size());
    }

    public static void onLevelChanged(ClientLevel clientLevel) {
        if (clientLevel != level) {
            level = clientLevel;
            RECEIVED.clear();
            BUILT.clear();
            lastRequestTick = Long.MIN_VALUE;
        }
    }

    public static boolean has(ChunkPos chunk) {
        return RECEIVED.containsKey(chunk);
    }

    public static FactoryProjectionCache get(ClientLevel clientLevel, ChunkPos chunk) {
        List<EndpointBlockEntity.PreviewBlock> blocks = RECEIVED.get(chunk);
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        int hash = blocks.hashCode();
        Built cached = BUILT.get(chunk);
        if (cached != null && cached.hash() == hash) {
            return cached.cache();
        }

        LOGGER.info("Building factory enclosure render cache for chunk [{}, {}] with {} blocks", chunk.x, chunk.z, blocks.size());
        FactoryProjectionCache cache = new FactoryProjectionCache(clientLevel, blocks);
        BUILT.put(chunk, new Built(hash, cache));
        return cache;
    }

    public static void requestIfMissing(ClientLevel clientLevel, ChunkPos chunk) {
        if (has(chunk)) {
            return;
        }
        long gameTime = clientLevel.getGameTime();
        if (lastRequestTick != Long.MIN_VALUE && gameTime - lastRequestTick < REQUEST_INTERVAL_TICKS) {
            return;
        }
        lastRequestTick = gameTime;
        PacketDistributor.sendToServer(new FactoryEnclosurePackets.Request());
    }

    private record Built(int hash, FactoryProjectionCache cache) {
    }
}