package dev.custommusic.common.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.server.TrackStore;
import dev.custommusic.server.TransferQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /customdisc list | give <track_id> [player] | reload
 *
 * <p>{@code reload} never blocks the server thread: the folder scan and audio conversion run
 * on a background worker, and only the (short) datapack reload happens on the server thread —
 * and only when the track set actually changed. That keeps the game responsive instead of
 * freezing for the duration of a synchronous conversion.</p>
 */
public final class CustomDiscCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Max per-file problems echoed into chat; the rest stay in latest.log. */
    private static final int MAX_PROBLEMS_IN_CHAT = 8;

    private static final AtomicBoolean SCANNING = new AtomicBoolean();

    private static final SuggestionProvider<CommandSourceStack> TRACK_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (TrackEntry t : TrackStore.get().entries()) {
            if (remaining.isEmpty() || t.slug().startsWith(remaining)) {
                builder.suggest(t.slug());
            }
        }
        return builder.buildFuture();
    };

    private CustomDiscCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("customdisc")
            .then(Commands.literal("list")
                .executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("give")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("track", StringArgumentType.string())
                    .suggests(TRACK_SUGGESTIONS)
                    .executes(ctx -> give(ctx.getSource(), StringArgumentType.getString(ctx, "track"), null))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> give(ctx.getSource(), StringArgumentType.getString(ctx, "track"),
                            EntityArgument.getPlayer(ctx, "player"))))))
            .then(Commands.literal("reload")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> reload(ctx.getSource()))));
    }

    private static int list(CommandSourceStack source) {
        var tracks = TrackStore.get().entries();
        if (tracks.isEmpty()) {
            source.sendFailure(Component.translatable("message.custommusic.no_tracks"));
            source.sendSuccess(() -> Component.translatable("message.custommusic.folder",
                Component.literal(TrackStore.get().musicFolder().toAbsolutePath().toString())
                    .withStyle(ChatFormatting.AQUA)), false);
            source.sendSuccess(() -> Component.translatable("message.custommusic.formats")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Custom Music — " + tracks.size() + " track(s):")
            .withStyle(ChatFormatting.GOLD), false);
        for (TrackEntry t : tracks) {
            source.sendSuccess(() -> Component.literal("  ♪ " + t.slug())
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal("  —  " + t.title() + "  [" + fmt(t.durationSeconds()) + "]")
                    .withStyle(ChatFormatting.GRAY)), false);
        }
        return tracks.size();
    }

    private static int give(CommandSourceStack source, String slug, ServerPlayer target)
        throws CommandSyntaxException {
        ServerPlayer player = target != null ? target : source.getPlayerOrException();
        TrackEntry entry = TrackStore.get().bySlug(slug);
        if (entry == null) {
            source.sendFailure(Component.literal("Unknown track '" + slug + "'. See /customdisc list")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        var disc = TrackStore.get().makeDisc(entry, source.getServer());
        if (!player.getInventory().add(disc)) {
            player.drop(disc, false); // inventory full -> drop at the player's feet
        }
        source.sendSuccess(() -> Component.literal("Gave ")
            .append(Component.literal("♪ " + entry.title()).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" to " + player.getGameProfile().getName())), true);
        return 1;
    }

    /** Async reload: scan/convert off-thread, then (only if changed) reload datapacks. */
    private static int reload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (!SCANNING.compareAndSet(false, true)) {
            source.sendSuccess(() -> Component.translatable("message.custommusic.scan_busy")
                .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        String folder = TrackStore.get().musicFolder().toAbsolutePath().toString();
        source.sendSuccess(() -> Component.translatable("message.custommusic.scanning",
            Component.literal(folder).withStyle(ChatFormatting.AQUA)), true);

        Thread worker = new Thread(() -> {
            TrackStore.ScanReport report;
            try {
                report = TrackStore.get().forceRescan();
            } catch (Throwable t) {
                SCANNING.set(false);
                LOGGER.error("[CustomMusic] Scan failed", t);
                server.execute(() -> source.sendFailure(
                    Component.literal("Custom Music: scan failed — " + t + " (see the log)")));
                return;
            }
            final TrackStore.ScanReport result = report;
            server.execute(() -> finishReload(source, server, result));
        }, "custommusic-scan");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /** Runs on the server thread once the background scan finished. */
    private static void finishReload(CommandSourceStack source, MinecraftServer server,
                                     TrackStore.ScanReport report) {
        SCANNING.set(false);
        if (report.changed()) {
            // Re-run vanilla /reload so the injected jukebox_song registry is rebuilt from the
            // freshly scanned virtual datapack, then push the new track list to all clients.
            source.sendSuccess(() -> Component.translatable("message.custommusic.reloading")
                .withStyle(ChatFormatting.GOLD), true);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                TransferQueue.sendTrackList(p);
            }
        } else {
            // Nothing changed: skip the datapack reload entirely — no reason to freeze the game.
            source.sendSuccess(() -> Component.translatable("message.custommusic.no_changes")
                .withStyle(ChatFormatting.GREEN), false);
        }
        source.sendSuccess(() -> Component.translatable("message.custommusic.scan_done",
            report.tracks(), report.converted(), report.reused(), report.skipped())
            .withStyle(ChatFormatting.GOLD), false);
        if (report.unsupportedFiles() > 0) {
            source.sendSuccess(() -> Component.translatable("message.custommusic.unsupported_files",
                report.unsupportedFiles()).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        int shown = 0;
        for (String problem : report.problems()) {
            if (shown++ >= MAX_PROBLEMS_IN_CHAT) {
                int rest = report.problems().size() - MAX_PROBLEMS_IN_CHAT;
                source.sendSuccess(() -> Component.literal("  … +" + rest + " more (see latest.log)")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
                break;
            }
            String line = problem;
            source.sendSuccess(() -> Component.literal("  ✗ " + line)
                .withStyle(ChatFormatting.RED), false);
        }
    }

    private static String fmt(float seconds) {
        int s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
