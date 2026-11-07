package com.zinzinc.recursivefactory.network;

import com.zinzinc.recursivefactory.RecursiveFactory;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetworking {
    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(RecursiveFactory.MODID).versioned("2");
        registrar.playToServer(FactoryDiagnosticPackets.Request.TYPE, FactoryDiagnosticPackets.Request.STREAM_CODEC,
                FactoryDiagnosticPackets.Request::handle);
        registrar.playToClient(FactoryDiagnosticPackets.Sync.TYPE, FactoryDiagnosticPackets.Sync.STREAM_CODEC,
                FactoryDiagnosticPackets.Sync::handle);
        registrar.playToServer(
                FactoryPickBlockPacket.TYPE,
                FactoryPickBlockPacket.STREAM_CODEC,
                FactoryPickBlockPacket::handle
        );
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
    }
}
