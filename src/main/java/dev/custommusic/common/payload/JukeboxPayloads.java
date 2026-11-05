package dev.custommusic.common.payload;

import dev.custommusic.CustomMusicMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Payloads for the Juke Box block.
 *
 * <p>The server is the single source of truth: it holds the jukebox state (track, playing,
 * loop, volume, range) and pushes {@link JukeboxStatePayload} to players. Clients reconcile
 * their sound instances against the latest state and open {@code JukeboxScreen} on
 * {@link OpenJukeboxPayload}. User actions travel back through {@link JukeboxActionPayload}
 * and are validated (slug exists, player near the block) on the server.</p>
 */
public final class JukeboxPayloads {

    /** How far (in blocks) the player may be from the Juke Box while the server accepts actions. */
    public static final double INTERACTION_RANGE = 24.0;

    private JukeboxPayloads() {
    }

    /** Server -> client: the full jukebox state. Drives the sound manager and any open screen. */
    public record JukeboxStatePayload(BlockPos pos, String slug, boolean playing, boolean loop,
                                      float volume, int range) implements CustomPacketPayload {
        public static final Type<JukeboxStatePayload> TYPE = new Type<>(CustomMusicMod.id("jukebox_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxStatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public JukeboxStatePayload decode(RegistryFriendlyByteBuf buf) {
                    return new JukeboxStatePayload(buf.readBlockPos(), buf.readUtf(64), buf.readBoolean(),
                        buf.readBoolean(), buf.readFloat(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, JukeboxStatePayload payload) {
                    buf.writeBlockPos(payload.pos);
                    buf.writeUtf(payload.slug, 64);
                    buf.writeBoolean(payload.playing);
                    buf.writeBoolean(payload.loop);
                    buf.writeFloat(payload.volume);
                    buf.writeVarInt(payload.range);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: open the Juke Box screen (sent right after the state payload). */
    public record OpenJukeboxPayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenJukeboxPayload> TYPE = new Type<>(CustomMusicMod.id("open_jukebox"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenJukeboxPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OpenJukeboxPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OpenJukeboxPayload(buf.readBlockPos());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OpenJukeboxPayload payload) {
                    buf.writeBlockPos(payload.pos);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client -> server: one action from the Juke Box screen. One payload for everything keeps
     * the codec + handler boilerplate down; unused fields are ignored per action.
     */
    public record JukeboxActionPayload(BlockPos pos, int action, String slug, boolean loop,
                                       float volume, int range) implements CustomPacketPayload {
        public static final int ACTION_PLAY = 0;
        public static final int ACTION_STOP = 1;
        public static final int ACTION_SETTINGS = 2;

        public static final Type<JukeboxActionPayload> TYPE =
            new Type<>(CustomMusicMod.id("jukebox_action"));

        public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxActionPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public JukeboxActionPayload decode(RegistryFriendlyByteBuf buf) {
                    return new JukeboxActionPayload(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(64),
                        buf.readBoolean(), buf.readFloat(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, JukeboxActionPayload payload) {
                    buf.writeBlockPos(payload.pos);
                    buf.writeVarInt(payload.action);
                    buf.writeUtf(payload.slug, 64);
                    buf.writeBoolean(payload.loop);
                    buf.writeFloat(payload.volume);
                    buf.writeVarInt(payload.range);
                }
            };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static JukeboxActionPayload play(BlockPos pos, String slug) {
            return new JukeboxActionPayload(pos, ACTION_PLAY, slug, false, 1.0f, 16);
        }

        public static JukeboxActionPayload stop(BlockPos pos) {
            return new JukeboxActionPayload(pos, ACTION_STOP, "", false, 1.0f, 16);
        }

        public static JukeboxActionPayload settings(BlockPos pos, boolean loop, float volume, int range) {
            return new JukeboxActionPayload(pos, ACTION_SETTINGS, "", loop, volume, range);
        }
    }
}
