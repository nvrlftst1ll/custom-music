package dev.custommusic.common;

import dev.custommusic.CustomMusicMod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * common/custommusic-common.toml — sane limits so a broken or huge file can never
 * take the server down (files beyond the limits are skipped with a warning).
 */
public final class CMConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_TRACKS = BUILDER
        .comment("Maximum number of tracks (hard-capped by the sound slot pool size).")
        .defineInRange("maxTracks", 128, 1, CMSounds.SLOT_COUNT);

    public static final ModConfigSpec.IntValue MAX_FILE_SIZE_MB = BUILDER
        .comment("Maximum size of one audio file, in megabytes.")
        .defineInRange("maxFileSizeMb", 50, 1, 2048);

    public static final ModConfigSpec.IntValue MAX_DURATION_SECONDS = BUILDER
        .comment("Maximum track duration, in seconds.")
        .defineInRange("maxDurationSeconds", 600, 5, 86400);

    public static final ModConfigSpec.BooleanValue ALLOW_UNCONVERTIBLE_STEREO = BUILDER
        .comment("If true, stereo files that cannot be converted to mono are kept and played",
            "WITHOUT 3D attenuation (audible everywhere). If false, they are skipped.")
        .define("allowUnconvertibleStereo", false);

    public static final ModConfigSpec.ConfigValue<String> FFMPEG_PATH = BUILDER
        .comment("Path to the ffmpeg binary used as a fallback encoder.",
            "\"ffmpeg\" = use from PATH, \"\" = disable the ffmpeg fallback.")
        .define("ffmpegPath", "ffmpeg");

    public static final ModConfigSpec.IntValue CHUNKS_PER_TICK = BUILDER
        .comment("How many ~28 KB audio chunks are sent to a client per server tick during sync.")
        .defineInRange("chunksPerTick", 8, 1, 64);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CMConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}
