package com.zinzinc.recursivefactory.client.network;

import com.zinzinc.recursivefactory.block.entity.EndpointBlockEntity;
import com.zinzinc.recursivefactory.client.render.ClientEnclosureCache;
import com.zinzinc.recursivefactory.network.EndpointPreviewPackets;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public final class ClientEndpointPreviewHandler {
    private ClientEndpointPreviewHandler() {
    }

    public static void handle(BlockPos pos, CompoundTag previewTag) {
        if (!(net.minecraft.client.Minecraft.getInstance().player instanceof LocalPlayer player)
                || !(player.level() instanceof ClientLevel level)
                || !(level.getBlockEntity(pos) instanceof EndpointBlockEntity endpoint)) {
            return;
        }
        endpoint.acceptPreview(EndpointBlockEntity.readPreviewBlocks(
                previewTag,
                level.registryAccess()
        ));
    }

    public static void handle(EndpointPreviewPackets.Sync packet) {
        handle(packet.pos(), packet.previewTag());
    }

    public static void handleEnclosure(int chunkX, int chunkZ, List<EndpointBlockEntity.PreviewBlock> blocks) {
        if (!(net.minecraft.client.Minecraft.getInstance().player instanceof LocalPlayer player)
                || !(player.level() instanceof ClientLevel)) {
            return;
        }
        ClientEnclosureCache.accept(chunkX, chunkZ, blocks);
    }
}