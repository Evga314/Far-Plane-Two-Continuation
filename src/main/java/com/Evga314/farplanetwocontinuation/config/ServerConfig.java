package com.Evga314.farplanetwocontinuation.config;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.minecraft.util.Mth;

/**
 * Settings of the tile generator. On a dedicated server this file is edited by the server admin, in singleplayer it belongs to the player.
 */
public class ServerConfig {
    private static final String FILE = FarPlaneConstants.MOD_ID + "-server.json";

    public boolean enabled = true;
    /** Farthest distance from a player, in blocks, for which tiles are generated. */
    public int maxDistance = FarPlaneConstants.MAX_LOD_DISTANCE;
    /** Finest LOD level clients may request. 0 means one sample per block. */
    public int minLevel = 0;
    /** 0 picks a value based on the CPU core count. */
    public int generatorThreads = 0;
    public int maxQueuedRequestsPerPlayer = 512;
    public int maxInFlightPerPlayer = 6;
    public boolean diskCache = true;
    public int memoryCacheTiles = 2048;
    /** Detailed tiles built from real chunks around players. */
    public boolean detailEnabled = true;
    public int detailMaxRadiusChunks = FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS;
    /** Read chunks saved on disk for detailed tiles. Without it only chunks loaded in memory show real blocks. */
    public boolean detailReadSavedChunks = true;
    /**
     * Chunks nobody has visited yet are generated in memory for detailed tiles, with trees and structures, and kept in the world
     * folder. Nothing is added to the world itself. Players can turn it off for themselves; this switch turns it off for everyone.
     */
    public boolean detailGenerateUnexplored = true;
    /** Threads for detailed tiles, separate from the generator threads. 0 picks a value based on the CPU core count. */
    public int detailThreads = 0;

    public void sanitize() {
        this.maxDistance = Mth.clamp(this.maxDistance, FarPlaneConstants.MIN_LOD_DISTANCE, FarPlaneConstants.MAX_LOD_DISTANCE);
        this.minLevel = Mth.clamp(this.minLevel, 0, FarPlaneConstants.MAX_MIN_LEVEL);
        this.generatorThreads = Mth.clamp(this.generatorThreads, 0, 16);
        this.detailThreads = Mth.clamp(this.detailThreads, 0, 8);
        this.maxQueuedRequestsPerPlayer = Mth.clamp(this.maxQueuedRequestsPerPlayer, 16, 4096);
        this.maxInFlightPerPlayer = Mth.clamp(this.maxInFlightPerPlayer, 1, 64);
        this.memoryCacheTiles = Mth.clamp(this.memoryCacheTiles, 0, 65536);
        this.detailMaxRadiusChunks = Mth.clamp(this.detailMaxRadiusChunks, 0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS);
    }

    public int resolvedThreads() {
        if (this.generatorThreads > 0) {
            return this.generatorThreads;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Mth.clamp((cores - 2) / 2, 1, 6);
    }

    public int resolvedDetailThreads() {
        if (this.detailThreads > 0) {
            return this.detailThreads;
        }
        return Mth.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 3);
    }

    public static ServerConfig load() {
        ServerConfig config = ConfigFiles.load(FILE, ServerConfig.class, ServerConfig::new);
        config.sanitize();
        return config;
    }
}
