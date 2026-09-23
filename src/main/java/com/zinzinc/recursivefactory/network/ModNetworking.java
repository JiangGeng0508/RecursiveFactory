package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(RecursiveFactory.MODID).versioned("1");
        registrar.playToServer(
                EndpointPreviewPackets.Request.TYPE,
                EndpointPreviewPackets.Request.STREAM_CODEC,
                EndpointPreviewPackets.Request::handle
        );
        registrar.playToClient(
                EndpointPreviewPackets.Sync.TYPE,
                EndpointPreviewPackets.Sync.STREAM_CODEC,
                EndpointPreviewPackets.Sync::handle
        );
        registrar.playToClient(
                EndpointModePackets.Sync.TYPE,
                EndpointModePackets.Sync.STREAM_CODEC,
                EndpointModePackets.Sync::handle
        );
    }
}
