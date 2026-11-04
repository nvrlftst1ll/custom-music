package dev.custommusic.common;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One track, as seen by both sides. The slug is a safe identifier ([a-z0-9_.-]{1,48}) used for:
 * the jukebox_song registry id, the converted .ogg file name and the sound file path.
 */
public record TrackEntry(
    String slug,
    String title,
    String fileName,
    float durationSeconds,
    long sizeBytes,
    String sha256Source,
    String sha256Ogg,
    int slot
) {
    public static void encode(TrackEntry t, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(t.slug, 64);
        buf.writeUtf(t.title, 256);
        buf.writeUtf(t.fileName, 512);
        buf.writeFloat(t.durationSeconds);
        buf.writeLong(t.sizeBytes);
        buf.writeUtf(t.sha256Source, 64);
        buf.writeUtf(t.sha256Ogg, 64);
        buf.writeVarInt(t.slot);
    }

    public static TrackEntry decode(RegistryFriendlyByteBuf buf) {
        return new TrackEntry(
            buf.readUtf(64),
            buf.readUtf(256),
            buf.readUtf(512),
            buf.readFloat(),
            buf.readLong(),
            buf.readUtf(64),
            buf.readUtf(64),
            buf.readVarInt());
    }
}
