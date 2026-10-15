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
    private static final int MAX_ENTRIES = 2048;
    /** Saved chunks only change while loaded, and then the live chunk is used; this only limits how stale an unloaded one can get. */
    private static final long SAVED_TTL_MS = 120_000L;
    private static final long MISSING_TTL_MS = 10_000L;

    private final Map<Key, Cached> entries = new LinkedHashMap<>(512, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return this.size() > MAX_ENTRIES;
        }
    };

    /** The cached state of a chunk: {@code null} when unknown, a lookup without summary when the chunk has no data. */
    synchronized @Nullable Lookup get(Identifier dimension, long chunk) {
        Cached entry = this.entries.get(new Key(dimension, chunk));
        if (entry == null) {
            return null;
        }
        long age = Util.getMillis() - entry.created;
        if (age > (entry.summary == null ? MISSING_TTL_MS : SAVED_TTL_MS)) {
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

    record Lookup(@Nullable ChunkSummary summary) {
    }

    private record Key(Identifier dimension, long chunk) {
    }

    private record Cached(@Nullable ChunkSummary summary, long created) {
    }
}
