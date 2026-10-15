package com.Evga314.farplanetwocontinuation.server;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Chunks where a block changed to one that looks different from above, collected until the tile server picks them up. Only chunk
 * positions are kept, a few bytes each; the blocks themselves are read from the world when the detailed tiles are built again.
 */
public final class DirtyChunks {
    private static final Map<ResourceKey<Level>, LongOpenHashSet> CHUNKS = new HashMap<>();

    private DirtyChunks() {
    }

    public static void mark(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        synchronized (CHUNKS) {
            CHUNKS.computeIfAbsent(dimension, key -> new LongOpenHashSet()).add(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    static Map<ResourceKey<Level>, long[]> drain() {
        synchronized (CHUNKS) {
            if (CHUNKS.isEmpty()) {
                return Map.of();
            }
            Map<ResourceKey<Level>, long[]> result = new HashMap<>();
            CHUNKS.forEach((dimension, chunks) -> result.put(dimension, chunks.toLongArray()));
            CHUNKS.clear();
            return result;
        }
    }
}
