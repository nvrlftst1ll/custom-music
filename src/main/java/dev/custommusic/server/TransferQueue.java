package dev.custommusic.server;

import com.mojang.logging.LogUtils;
import dev.custommusic.common.CMConfig;
import dev.custommusic.common.TrackEntry;
import dev.custommusic.common.payload.CMPayloads;
import dev.custommusic.common.payload.CMPayloads.TrackChunkPayload;
import dev.custommusic.common.payload.CMPayloads.TrackListPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes the track list and audio chunks to clients. Chunks are paced on the server thread
 * (chunksPerTick) so a 50 MB track never floods the connection in one burst.
 */
public final class TransferQueue {
    public static final int CHUNK_SIZE = CMPayloads.CHUNK_SIZE;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Queue<Chunk>> QUEUES = new ConcurrentHashMap<>();

    private record Chunk(String slug, int index, int total, long fileOffset, int length) {
    }

    private TransferQueue() {
    }

    public static void sendTrackList(ServerPlayer player) {
        TrackStore.get().ensureScanned();
        List<TrackEntry> entries = new ArrayList<>(TrackStore.get().entries());
        PacketDistributor.sendToPlayer(player, new TrackListPayload(entries));
    }

    public static synchronized void request(ServerPlayer player, List<String> slugs) {
        clear(player.getUUID());
        Queue<Chunk> queue = new ArrayDeque<>();
        for (String slug : slugs) {
            TrackEntry entry = TrackStore.get().bySlug(slug);
            if (entry == null) continue;
            try {
                Path ogg = TrackStore.CONVERTED_DIR.resolve(slug + ".ogg");
                long size = Files.size(ogg);
                int total = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
                for (int i = 0; i < total; i++) {
                    long off = (long) i * CHUNK_SIZE;
                    int len = (int) Math.min(CHUNK_SIZE, size - off);
                    queue.add(new Chunk(slug, i, total, off, len));
                }
            } catch (IOException e) {
                LOGGER.warn("[CustomMusic] Cannot queue '{}': {}", slug, e.toString());
            }
        }
        if (queue.isEmpty()) return;
        QUEUES.put(player.getUUID(), queue);
        LOGGER.info("[CustomMusic] Sending {} chunk(s) to {}", queue.size(), player.getGameProfile().getName());
        player.server.execute(() -> step(player));
    }

    public static synchronized void clear(UUID playerId) {
        QUEUES.remove(playerId);
    }

    private static synchronized void step(ServerPlayer player) {
        Queue<Chunk> queue = QUEUES.get(player.getUUID());
        if (queue == null || player.isRemoved()) {
            QUEUES.remove(player.getUUID());
            return;
        }
        MinecraftServer server = player.server;
        int budget = CMConfig.CHUNKS_PER_TICK.get();
        while (budget-- > 0 && !queue.isEmpty()) {
            Chunk c = queue.poll();
            try (RandomAccessFile raf = new RandomAccessFile(
                TrackStore.CONVERTED_DIR.resolve(c.slug() + ".ogg").toFile(), "r")) {
                raf.seek(c.fileOffset());
                byte[] data = new byte[c.length()];
                raf.readFully(data);
                PacketDistributor.sendToPlayer(player,
                    new TrackChunkPayload(c.slug(), c.index(), c.total(), data));
            } catch (IOException e) {
                LOGGER.warn("[CustomMusic] Chunk read failed for '{}': {}", c.slug(), e.toString());
                QUEUES.remove(player.getUUID());
                return;
            }
        }
        if (queue.isEmpty()) {
            QUEUES.remove(player.getUUID());
            LOGGER.info("[CustomMusic] Transfer to {} finished", player.getGameProfile().getName());
        } else {
            server.execute(() -> step(player)); // next server tick
        }
    }
}
