package dev.custommusic.client;

import dev.custommusic.common.payload.CMPayloads.OpenChooserPayload;
import dev.custommusic.common.payload.CMPayloads.TrackChunkPayload;
import dev.custommusic.common.payload.CMPayloads.TrackListPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client-side payload handlers, registered only on the client (see CMPayloads).
 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TrackListPayload.TYPE, TrackListPayload.STREAM_CODEC,
            ClientPayloadHandler::handleTrackList);
        registrar.playToClient(TrackChunkPayload.TYPE, TrackChunkPayload.STREAM_CODEC,
            ClientPayloadHandler::handleChunk);
        registrar.playToClient(OpenChooserPayload.TYPE, OpenChooserPayload.STREAM_CODEC,
            ClientPayloadHandler::handleOpenChooser);
    }

    private static void handleTrackList(TrackListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientTrackCache.applyServerList(payload.tracks()));
    }

    private static void handleChunk(TrackChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientTrackCache.handleChunk(payload));
    }

    private static void handleOpenChooser(OpenChooserPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new ChooseTrackScreen()));
    }
}
