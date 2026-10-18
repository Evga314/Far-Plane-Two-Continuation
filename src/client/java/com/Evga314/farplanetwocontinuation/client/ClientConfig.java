package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.config.ConfigFiles;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Client settings. Distant and detailed terrain have their own settings in every kind of dimension, stored by the dimension's
 * {@link DimensionKind#id() id}, so dimensions added later only need a new entry.
 */
public class ClientConfig {
    private static final String FILE = FarPlaneConstants.MOD_ID + "-client.json";

    /** Turns the whole mod on and off in every dimension. */
    public boolean enabled = true;
    public int maxUploadsPerFrame = 12;
    public int meshThreads = 1;
    /** How detailed terrain loads while moving, see {@link LoadSpeed}. */
    public int loadSpeed = LoadSpeed.AUTO.ordinal();

    public Map<String, DistantSettings> distant = new LinkedHashMap<>();
    public Map<String, DetailSettings> detailed = new LinkedHashMap<>();

    /** The kind of dimension the player is in, which picks the settings in use. */
    public transient DimensionKind dimension = DimensionKind.OVERWORLD;

    // Settings of older versions, moved to the overworld on load: before 1.0.15 there was one set of each, 1.0.15 had two fields.
    private @Nullable Integer lodDistance;
    private @Nullable Integer detailLevel;
    private @Nullable Float quality;
    private @Nullable Integer maxGpuTiles;
    private @Nullable Float fogStart;
    private @Nullable Boolean disableFog;
    private @Nullable Boolean detailEnabled;
    private @Nullable Integer detailRadiusChunks;
    private @Nullable Float detailQuality;
    private @Nullable Integer detailGpuMegabytes;
    private @Nullable Boolean detailGenerate;
    private @Nullable DetailSettings overworldDetail;
    private @Nullable DetailSettings endDetail;

    /** Rough terrain straight from the world generator, up to the edge of the world. */
    public static class DistantSettings {
        /** How far LOD terrain is drawn, in blocks. Up to the edge of the world (30 000 000). */
        public int lodDistance = FarPlaneConstants.MAX_LOD_DISTANCE;
        /**
         * Size of the cells right outside the detailed terrain (or vanilla chunks without it), as a power of two in blocks: 0 is one
         * block, 4 is 16 blocks. Farther out they grow on their own.
         */
        public int startLevel = 2;
        /** How sharp the distant terrain is, see {@link #SHARPNESS}. */
        public int sharpness = 1;
        /** Upper bound for tiles kept on the GPU. Each one takes about 20 KB of video memory. */
        public int maxGpuTiles = 2048;
        /** Fraction of the LOD distance after which terrain fades into fog. */
        public float fogStart = 0.75F;
        /** Removes all fog except blindness and darkness. Off by default so it does not fight with dedicated no-fog mods. */
        public boolean disableFog = false;

        void sanitize() {
            this.lodDistance = Mth.clamp(this.lodDistance, FarPlaneConstants.MIN_LOD_DISTANCE, FarPlaneConstants.MAX_LOD_DISTANCE);
            this.startLevel = Mth.clamp(this.startLevel, 0, MAX_START_LEVEL);
            this.sharpness = Mth.clamp(this.sharpness, 0, SHARPNESS.length - 1);
            this.maxGpuTiles = Mth.clamp(this.maxGpuTiles, 256, 1 << 19);
            this.fogStart = Float.isFinite(this.fogStart) ? Mth.clamp(this.fogStart, 0.1F, 0.98F) : 0.75F;
        }

        /** Fog settings are left out, they apply right away. */
        String signature() {
            return this.lodDistance + "|" + this.startLevel + "|" + this.sharpness + "|" + this.maxGpuTiles;
        }

        /** Split factor of the distant terrain at each sharpness step, for a 1080p screen; the middle one is the old default. */
        public double sharpnessFactor() {
            return SHARPNESS[this.sharpness];
        }
    }

    /** Sharpness steps of the distant terrain: economical, normal, sharp. */
    public static final double[] SHARPNESS = {1.0, 1.5, 2.25};
    /** Coarsest cells allowed at the start of the distant terrain: 16 blocks. */
    public static final int MAX_START_LEVEL = 4;

    /** Detailed terrain from real chunks in a ring around the player. */
    public static class DetailSettings {
        public boolean enabled = true;
        /** Radius of the detailed ring in chunks, counted from the player. */
        public int radiusChunks = 32;
        /** Video memory for detailed tiles, in megabytes. */
        public int gpuMegabytes = 128;
        /** Places nobody has visited yet are generated in memory for the detailed area, with trees and buildings. */
        public boolean generate = true;
        /** How detailed the blocks look, see {@link BlockDetail}. */
        public int blockDetail = BlockDetail.MEDIUM.ordinal();

        public BlockDetail blocks() {
            return BlockDetail.of(this.blockDetail);
        }

        void sanitize() {
            this.blockDetail = this.blocks().ordinal();
            this.radiusChunks = Mth.clamp(this.radiusChunks, 0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS);
            this.gpuMegabytes = Mth.clamp(this.gpuMegabytes, 16, GpuMemory.MAX_MB);
        }

        String signature() {
            return this.enabled + "|" + this.radiusChunks + "|" + this.gpuMegabytes + "|" + this.generate
                + "|" + this.blockDetail;
        }
    }

    /** Distant terrain settings of the dimension the player is in. */
    public DistantSettings distant() {
        return this.distant(this.dimension);
    }

    public DistantSettings distant(DimensionKind kind) {
        return this.distant.computeIfAbsent(kind.id(), id -> new DistantSettings());
    }

    /** Detailed terrain settings of the dimension the player is in. */
    public DetailSettings detail() {
        return this.detail(this.dimension);
    }

    public DetailSettings detail(DimensionKind kind) {
        return this.detailed.computeIfAbsent(kind.id(), id -> new DetailSettings());
    }

    public void sanitize() {
        if (this.distant == null) {
            this.distant = new LinkedHashMap<>();
        }
        if (this.detailed == null) {
            this.detailed = new LinkedHashMap<>();
        }
        this.distant.values().removeIf(settings -> settings == null);
        this.detailed.values().removeIf(settings -> settings == null);
        this.migrate();
        for (DimensionKind kind : DimensionKind.values()) {
            if (kind.hasLod()) {
                this.distant(kind);
                this.detail(kind);
            }
        }
        this.maxUploadsPerFrame = Mth.clamp(this.maxUploadsPerFrame, 1, 128);
        this.meshThreads = Mth.clamp(this.meshThreads, 1, 4);
        this.loadSpeed = LoadSpeed.of(this.loadSpeed).ordinal();
        this.distant.values().forEach(DistantSettings::sanitize);
        this.detailed.values().forEach(DetailSettings::sanitize);
    }

    private void migrate() {
        DistantSettings distant = this.distant(DimensionKind.OVERWORLD);
        if (this.lodDistance != null) {
            distant.lodDistance = this.lodDistance;
        }
        if (this.maxGpuTiles != null) {
            distant.maxGpuTiles = this.maxGpuTiles;
        }
        if (this.fogStart != null) {
            distant.fogStart = this.fogStart;
        }
        if (this.disableFog != null) {
            distant.disableFog = this.disableFog;
        }
        if (this.overworldDetail != null) {
            this.detailed.put(DimensionKind.OVERWORLD.id(), this.overworldDetail);
        }
        if (this.endDetail != null) {
            this.detailed.put(DimensionKind.END.id(), this.endDetail);
        }
        DetailSettings detail = this.detail(DimensionKind.OVERWORLD);
        if (this.detailEnabled != null) {
            detail.enabled = this.detailEnabled;
        }
        if (this.detailRadiusChunks != null) {
            detail.radiusChunks = this.detailRadiusChunks;
        }
        if (this.detailGpuMegabytes != null) {
            detail.gpuMegabytes = this.detailGpuMegabytes;
        }
        if (this.detailGenerate != null) {
            detail.generate = this.detailGenerate;
        }
        this.lodDistance = null;
        this.detailLevel = null;
        this.quality = null;
        this.maxGpuTiles = null;
        this.fogStart = null;
        this.disableFog = null;
        this.detailEnabled = null;
        this.detailRadiusChunks = null;
        this.detailQuality = null;
        this.detailGpuMegabytes = null;
        this.detailGenerate = null;
        this.overworldDetail = null;
        this.endDetail = null;
    }

    /** Everything that changes which tiles are loaded or how they are built. Fog settings are left out, they apply right away. */
    public String tileSignature() {
        StringBuilder signature = new StringBuilder().append(this.enabled);
        this.distant.forEach((id, settings) -> signature.append('|').append(id).append(':').append(settings.signature()));
        this.detailed.forEach((id, settings) -> signature.append('|').append(id).append(':').append(settings.signature()));
        return signature.toString();
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
