package com.zinzinc.recursivefactory.client.network;

import com.mojang.logging.LogUtils;
import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

public final class ClientEndpointPreviewHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientEndpointPreviewHandler() {
    }

    public static void handle(BlockPos pos, CompoundTag previewTag) {
        if (!(net.minecraft.client.Minecraft.getInstance().player instanceof LocalPlayer player)
                || !(player.level() instanceof ClientLevel level)
                || !(level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint)) {
            return;
        }
        List<EndpointBlockEntity.PreviewBlock> blocks = EndpointBlockEntity.readPreviewBlocks(
                previewTag,
                level.registryAccess()
        );
        if (!blocks.equals(endpoint.getPreviewBlocks())) {
            LOGGER.info("Received endpoint preview at {} with {} blocks", pos, blocks.size());
        }
        endpoint.acceptPreview(blocks);
    }

    public static void handle(EndpointPreviewPackets.Sync packet) {
        handle(packet.pos(), packet.previewTag());
    }
}