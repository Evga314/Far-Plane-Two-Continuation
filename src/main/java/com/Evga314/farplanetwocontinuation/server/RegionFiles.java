package com.Evga314.farplanetwocontinuation.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Whether a region file exists, remembered for a short while. Without a region file none of its 1024 chunks were ever saved, so the
 * chunks of unexplored land are not asked for one by one through the server's chunk storage, which its own chunk loading also uses.
 */
final class RegionFiles {
    private static final long TTL_MS = 30_000L;
    /** Region files appear as soon as a new chunk is saved, so "missing" is only trusted for a moment. */
    private static final long MISSING_TTL_MS = 3_000L;
    private static final Set<Key> REAL = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Key, Long> CHECKED = new ConcurrentHashMap<>();

    private RegionFiles() {
    }

    /** True if the region file of a chunk exists, or might. */
    static boolean mayContain(ServerLevel level, int chunkX, int chunkZ) {
        Key key = new Key(level.dimension(), chunkX >> 5, chunkZ >> 5);
        long now = Util.getMillis();
        Long checked = CHECKED.get(key);
        if (REAL.contains(key)) {
            return true;
        }
        if (checked != null && now - Math.abs(checked) < (checked > 0 ? TTL_MS : MISSING_TTL_MS)) {
            return checked > 0;
        }
        Path folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        boolean exists = Files.exists(folder.resolve("region").resolve("r." + key.x + "." + key.z + ".mca"));
        // The sign holds the answer, the value the time of the check.
        CHECKED.put(key, exists ? now : -now);
        if (CHECKED.size() > 4096) {
            CHECKED.clear();
        }
        return exists;
    }

    /**
     * Blocks changed in a chunk, so it is real: its region file exists or will as soon as the chunk is saved. Until then the server's
     * chunk storage hands out the unsaved data, so such regions are always read.
     */
    static void markReal(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (REAL.size() > 65536) {
            REAL.clear();
        }
        REAL.add(new Key(dimension, chunkX >> 5, chunkZ >> 5));
    }

    private record Key(ResourceKey<Level> dimension, int x, int z) {
    }
}
