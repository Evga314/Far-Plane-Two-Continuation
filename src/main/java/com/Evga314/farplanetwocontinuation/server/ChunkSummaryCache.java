package com.Evga314.farplanetwocontinuation.server;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Summaries of chunks read from region files, shared by all detailed tiles. Parsing a chunk is by far the most expensive part of a
 * detailed tile, and every chunk is part of several tiles: one per detail level, plus the borders of its neighbours. Chunks that do not
 * exist yet are remembered for a short while too, so asking about unexplored land again stays cheap.
 */
final class ChunkSummaryCache {
    /**
     * About 9 KB each, so one entry per megabyte of heap (4096 with 4 GB): enough for the ring of detailed tiles around a player,
     * with every detail level over the same chunks, without crowding small heaps on phones.
     */
    private static final int MAX_ENTRIES = (int) Math.clamp(Runtime.getRuntime().maxMemory() >> 20, 1024L, 6144L);
    /**
     * Saved chunks only change while loaded: then the live chunk is used, and the change drops the cached summary. This only limits how
     * stale a chunk changed by other tools can get.
     */
    private static final long SAVED_TTL_MS = 900_000L;
    private static final long MISSING_TTL_MS = 10_000L;

    private final Map<Key, Cached> entries = new LinkedHashMap<>(512, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return this.size() > MAX_ENTRIES;
        }
    };

    /**
     * The cached state of a chunk: {@code null} when unknown, a lookup without summary when the chunk has no data. A summary made
     * with another scan (the depth was changed) counts as unknown.
     */
    synchronized @Nullable Lookup get(Identifier dimension, long chunk, ChunkSummary.Scan scan) {
        Cached entry = this.entries.get(new Key(dimension, chunk));
        if (entry == null) {
            return null;
        }
        long age = Util.getMillis() - entry.created;
        if (age > (entry.summary == null ? MISSING_TTL_MS : SAVED_TTL_MS) || entry.summary != null && !entry.summary.scan().equals(scan)) {
            this.entries.remove(new Key(dimension, chunk));
            return null;
        }
        return new Lookup(entry.summary);
    }

    synchronized void put(Identifier dimension, long chunk, @Nullable ChunkSummary summary) {
        this.entries.put(new Key(dimension, chunk), new Cached(summary, Util.getMillis()));
    }

    synchronized void remove(Identifier dimension, long chunk) {
        this.entries.remove(new Key(dimension, chunk));
    }

    synchronized void clear() {
        this.entries.clear();
    }

    synchronized void clear(Identifier dimension) {
        this.entries.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    record Lookup(@Nullable ChunkSummary summary) {
    }

    private record Key(Identifier dimension, long chunk) {
    }

    private record Cached(@Nullable ChunkSummary summary, long created) {
    }
}
