package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.config.ConfigFiles;
import net.minecraft.util.Mth;

public class ClientConfig {
    private static final String FILE = FarPlaneConstants.MOD_ID + "-client.json";

    public boolean enabled = true;
    /** How far LOD terrain is drawn, in blocks. Up to the edge of the world (30 000 000). */
    public int lodDistance = FarPlaneConstants.MAX_LOD_DISTANCE;
    /** Finest LOD level. 0 = one sample per block, every step halves the detail. */
    public int detailLevel = 1;
    /** A tile is split into four finer ones while the camera is closer than this many tile sizes. */
    public float quality = 1.5F;
    /** Upper bound for tiles kept on the GPU. Each one takes about 20 KB of video memory. */
    public int maxGpuTiles = 2048;
    public int maxUploadsPerFrame = 12;
    public int meshThreads = 1;
    /** Fraction of the LOD distance after which terrain fades into fog. */
    public float fogStart = 0.75F;
    /** Removes all fog except blindness and darkness. Off by default so it does not fight with dedicated no-fog mods. */
    public boolean disableFog = false;

    /** Detailed terrain from real chunks in a ring around the player. */
    public boolean detailEnabled = true;
    /** Radius of the detailed ring in chunks, counted from the player. */
    public int detailRadiusChunks = 32;
    /** Finest detailed level: 1 = one cell per block, every step doubles the cell size. */
    public int detailMinLevel = 1;
    public float detailQuality = 1.5F;
    /** Video memory for detailed tiles, in megabytes. */
    public int detailGpuMegabytes = 128;
    /** Places nobody has visited yet are generated in memory for the detailed area, with trees and buildings. */
    public boolean detailGenerate = true;

    public void sanitize() {
        this.lodDistance = Mth.clamp(this.lodDistance, FarPlaneConstants.MIN_LOD_DISTANCE, FarPlaneConstants.MAX_LOD_DISTANCE);
        this.detailLevel = Mth.clamp(this.detailLevel, 0, FarPlaneConstants.MAX_MIN_LEVEL);
        this.quality = Float.isFinite(this.quality) ? Mth.clamp(this.quality, 1.0F, 6.0F) : 1.5F;
        this.maxGpuTiles = Mth.clamp(this.maxGpuTiles, 256, 1 << 19);
        this.maxUploadsPerFrame = Mth.clamp(this.maxUploadsPerFrame, 1, 128);
        this.meshThreads = Mth.clamp(this.meshThreads, 1, 4);
        this.fogStart = Float.isFinite(this.fogStart) ? Mth.clamp(this.fogStart, 0.1F, 0.98F) : 0.75F;
        this.detailRadiusChunks = Mth.clamp(this.detailRadiusChunks, 0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS);
        this.detailMinLevel = Mth.clamp(this.detailMinLevel, FarPlaneConstants.DETAIL_MIN_LEVEL, FarPlaneConstants.DETAIL_MAX_LEVEL);
        this.detailQuality = Float.isFinite(this.detailQuality) ? Mth.clamp(this.detailQuality, 1.0F, 6.0F) : 1.5F;
        this.detailGpuMegabytes = Mth.clamp(this.detailGpuMegabytes, 16, GpuMemory.MAX_MB);
    }

    /** Everything that changes which tiles are loaded or how they are built. Fog settings are left out, they apply right away. */
    public String tileSignature() {
        return this.enabled + "|" + this.lodDistance + "|" + this.detailLevel + "|" + this.quality + "|" + this.maxGpuTiles
            + "|" + this.detailEnabled + "|" + this.detailRadiusChunks + "|" + this.detailMinLevel + "|" + this.detailQuality + "|" + this.detailGpuMegabytes + "|" + this.detailGenerate;
    }

    public void save() {
        this.sanitize();
        ConfigFiles.save(FILE, this);
    }

    public static ClientConfig load() {
        ClientConfig config = ConfigFiles.load(FILE, ClientConfig.class, ClientConfig::new);
        config.sanitize();
        return config;
    }
}
