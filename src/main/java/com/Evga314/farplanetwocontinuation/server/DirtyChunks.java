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

    /** Block changes by kind, old to new, for the performance log of the debug build; see {@link #noteChange}. */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> CHANGES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CHANGE_KINDS = 4096;

    /** Counts a block change by its blocks, in the debug build only. */
    public static void noteChange(net.minecraft.world.level.block.Block from, net.minecraft.world.level.block.Block to) {
        if (!com.Evga314.farplanetwocontinuation.FarPlaneDebug.ENABLED || CHANGES.size() >= MAX_CHANGE_KINDS) {
            return;
        }
        String kind = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(from).getPath() + ">"
            + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(to).getPath();
        CHANGES.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
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
