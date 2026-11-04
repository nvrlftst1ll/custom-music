package dev.custommusic.common.payload;

import dev.custommusic.CustomMusicMod;
import dev.custommusic.common.TrackEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * All custom payloads + registration. The server is the source of truth: it pushes the track
 * list to clients; clients then request missing audio and receive it in chunks.
 */
@EventBusSubscriber(modid = CustomMusicMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CMPayloads {
    /** Audio chunk size used by the transfer, ~28 KB to stay below any payload size cap. */
    public static final int CHUNK_SIZE = 28 * 1024;

    private CMPayloads() {
    }

    /** Server -> client: the full, current track list. */
    public record TrackListPayload(List<TrackEntry> tracks) implements CustomPacketPayload {
        public static final Type<TrackListPayload> TYPE = new Type<>(CustomMusicMod.id("track_list"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TrackListPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TrackListPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = Math.min(buf.readVarInt(), 1024);
                    List<TrackEntry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(TrackEntry.decode(buf));
                    return new TrackListPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, TrackListPayload payload) {
                    buf.writeVarInt(payload.tracks.size());
                    for (TrackEntry t : payload.tracks) TrackEntry.encode(t, buf);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: "send me the audio for these slugs" (missing or stale ones). */
    public record RequestTracksPayload(List<String> slugs) implements CustomPacketPayload {
        public static final Type<RequestTracksPayload> TYPE = new Type<>(CustomMusicMod.id("request_tracks"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTracksPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RequestTracksPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = Math.min(buf.readVarInt(), 1024);
                    List<String> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(buf.readUtf(64));
                    return new RequestTracksPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, RequestTracksPayload payload) {
                    buf.writeVarInt(payload.slugs.size());
                    for (String s : payload.slugs) buf.writeUtf(s, 64);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: one chunk of a converted .ogg (RandomAccessFile-friendly offsets). */
    public record TrackChunkPayload(String slug, int index, int total, byte[] data)
        implements CustomPacketPayload {
        public static final Type<TrackChunkPayload> TYPE = new Type<>(CustomMusicMod.id("track_chunk"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TrackChunkPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TrackChunkPayload decode(RegistryFriendlyByteBuf buf) {
                    return new TrackChunkPayload(
                        buf.readUtf(64),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readByteArray(CHUNK_SIZE + 1024));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, TrackChunkPayload payload) {
                    buf.writeUtf(payload.slug, 64);
                    buf.writeVarInt(payload.index);
                    buf.writeVarInt(payload.total);
                    buf.writeByteArray(payload.data);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: bake this track into the blank disc I am holding. */
    public record BakeTrackPayload(String slug) implements CustomPacketPayload {
        public static final Type<BakeTrackPayload> TYPE = new Type<>(CustomMusicMod.id("bake_track"));

        public static final StreamCodec<RegistryFriendlyByteBuf, BakeTrackPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BakeTrackPayload decode(RegistryFriendlyByteBuf buf) {
                    return new BakeTrackPayload(buf.readUtf(64));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BakeTrackPayload payload) {
                    buf.writeUtf(payload.slug, 64);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: open the track chooser screen (right-click with a blank disc). */
    public record OpenChooserPayload(int trackCount) implements CustomPacketPayload {
        public static final Type<OpenChooserPayload> TYPE = new Type<>(CustomMusicMod.id("open_chooser"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenChooserPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OpenChooserPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OpenChooserPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OpenChooserPayload payload) {
                    buf.writeVarInt(payload.trackCount);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RequestTracksPayload.TYPE, RequestTracksPayload.STREAM_CODEC,
            dev.custommusic.server.ServerPayloadHandlers::handleRequest);
        registrar.playToServer(BakeTrackPayload.TYPE, BakeTrackPayload.STREAM_CODEC,
            dev.custommusic.server.ServerPayloadHandlers::handleBake);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.custommusic.client.ClientPayloadHandler.register(registrar);
        }
    }
}
