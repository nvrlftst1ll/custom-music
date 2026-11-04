package dev.custommusic.common.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /customdisc list | give <track_id> [player] | reload
 */
public final class CustomDiscCommand {
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

    private static int reload(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int count = TrackStore.get().forceRescan();
        // Re-run vanilla /reload so the injected jukebox_song registry is rebuilt from the
        // freshly scanned virtual datapack, then push the new track list to all clients.
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            TransferQueue.sendTrackList(p);
        }
        source.sendSuccess(() -> Component.literal("Custom Music: scanned " + count
            + " track(s), datapacks reloaded, clients re-syncing."), true);
        return count;
    }

    private static String fmt(float seconds) {
        int s = Math.round(seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
