package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.GeneralMeshFactory;
import com.Evga314.farplanetwocontinuation.config.ConfigFiles;
import com.Evga314.farplanetwocontinuation.config.ServerConfig;
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
    /**
     * Shader packs draw the frame their own way, so the LOD terrain turns off while one is on; this forces it on anyway. Only
     * read when Iris is installed.
     */
    public boolean forceLodWithShaders = false;
    /**
     * With a shader pack the borders of the distant terrain (the snow line, forest edges, biomes) are cut into it sharp; this cuts the
     * soft ones twice, with the colors blended between the cuts, for a little more video memory. Off by default.
     */
    public boolean shaderSoftBorders = false;
    /**
     * Vanilla's far plane is a horizontal distance, so from high up the edge of the cloud layer falls past it and the clouds are cut
     * off; this extends the far plane to the slant distance to that edge. Off by default.
     */
    public boolean extendCloudFarPlane = false;
    /**
     * LOD terrain drawn inside vanilla's main pass, see LodRenderer.mainPassPrototype. On by default; with Iris shaders the LOD pass of
     * its own is used instead. Named apart from the experimental setting before it, which was off by default.
     */
    public boolean newRenderer = true;
    /** Entities above the top of the world get the full light of the sky, instead of vanilla's black past 2047. See EntityRendererMixin. */
    public boolean fixHighEntityLight = true;
    public int maxUploadsPerFrame = 12;
    /** Mesh builder threads: 0 lets the mod choose by core count, see {@link #resolvedMeshThreads()}. */
    public int meshThreads;
    /** Set once a stored {@link #meshThreads} has been read as an explicit choice, see {@link #sanitize()}. */
    public int meshThreadsVersion;
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
        /** Upper bound for tiles kept on the GPU. Each one takes about 34 KB of video memory. */
        public int maxGpuTiles = 2048;
        /** Where this mod's fog starts, as a fraction of {@link #fogEnd}; see {@link #fogStart(float)}. */
        public float fogStart = 0.25F;
        /** The radius of this mod's fog, where it becomes solid, in blocks from the player; 0 for the LOD distance. */
        public int fogEnd = 16000;
        /** How the distance of the fog of the LOD terrain is measured: 0 cylindrical, like vanilla's fog at its render distance; 1 round. */
        public int fogShape = 0;
        /** Whose fog is out of water, one of the FOG_ constants; see FogRendererMixin. */
        public int fogMode = FOG_OWN;
        /** Vanilla's fog under water. Without it the view under water ends at a wall around the detailed terrain, see LodRenderer.WALL_PIPELINE. */
        public boolean waterFog = true;
        /** How much the borders of the distant terrain are smoothed, see {@link Smoothing}. */
        public Smoothing smoothing = new Smoothing();

        void sanitize() {
            this.lodDistance = Mth.clamp(this.lodDistance, FarPlaneConstants.MIN_LOD_DISTANCE, FarPlaneConstants.MAX_LOD_DISTANCE);
            this.startLevel = Mth.clamp(this.startLevel, 0, MAX_START_LEVEL);
            this.sharpness = Mth.clamp(this.sharpness, 0, SHARPNESS.length - 1);
            this.maxGpuTiles = Mth.clamp(this.maxGpuTiles, 256, 1 << 19);
            this.fogStart = Float.isFinite(this.fogStart) ? Mth.clamp(this.fogStart, 0.0F, 1.0F) : 0.25F;
            this.fogEnd = Mth.clamp(this.fogEnd, 0, FarPlaneConstants.MAX_LOD_DISTANCE);
            this.fogShape = Mth.clamp(this.fogShape, 0, 1);
            this.fogMode = Mth.clamp(this.fogMode, FOG_AUTO, FOG_OFF);
            if (this.smoothing == null) {
                this.smoothing = new Smoothing();
            }
            this.smoothing.sanitize();
        }

        /**
         * Where this mod's fog starts for a radius of {@code end}: like Sodium Extra's fog start, even at 100% the fog still thickens over
         * the last chunk.
         */
        public float fogStart(float end) {
            return Math.max(end - FOG_MIN_WIDTH, 0.0F) * this.fogStart;
        }

        /** Fog settings are left out, they apply right away. */
        String signature() {
            return this.lodDistance + "|" + this.startLevel + "|" + this.sharpness + "|" + this.maxGpuTiles + "|" + this.smoothing.signature();
        }

        /** Split factor of the distant terrain at each sharpness step, for a 1080p screen; the middle one is the old default. */
        public double sharpnessFactor() {
            return SHARPNESS[this.sharpness];
        }
    }

    /**
     * How many times the borders of each kind are smoothed on the distant terrain, from 1 to {@link #MAX}: more rounds them off further
     * and lets wider lone strips fade.
     */
    public static class Smoothing {
        public static final int MAX = 8;
        private static final GeneralMeshFactory.Smoothing DEFAULT = GeneralMeshFactory.Smoothing.DEFAULT;

        public int coasts = DEFAULT.coasts();
        public int snow = DEFAULT.snow();
        public int forests = DEFAULT.forests();
        public int biomes = DEFAULT.biomes();
        public int water = DEFAULT.water();
        /** How wide the change from one color to the other is drawn at each kind of border, from 0 (sharp) to {@link #MAX_BLUR}. */
        public int coastsBlur = DEFAULT.coastsBlur();
        public int snowBlur = DEFAULT.snowBlur();
        public int forestsBlur = DEFAULT.forestsBlur();
        public int biomesBlur = DEFAULT.biomesBlur();
        public int waterBlur = DEFAULT.waterBlur();
        /** Baked sun and hollow shade over the distant terrain: 0 off, 1 on. */
        public int shading = DEFAULT.shading();
        public static final int MAX_BLUR = GeneralMeshFactory.MAX_SOFTNESS;
        /** Version of these settings: 1.2.42 set the blur of water borders higher on its own, which is taken back. */
        public int version;
        private static final int VERSION = 3;

        void sanitize() {
            if (this.version < VERSION) {
                this.waterBlur = DEFAULT.waterBlur();
                this.version = VERSION;
            }
            this.coasts = Mth.clamp(this.coasts, 1, MAX);
            this.snow = Mth.clamp(this.snow, 1, MAX);
            this.forests = Mth.clamp(this.forests, 1, MAX);
            this.biomes = Mth.clamp(this.biomes, 1, MAX);
            this.water = Mth.clamp(this.water, 1, MAX);
            this.coastsBlur = Mth.clamp(this.coastsBlur, 0, MAX_BLUR);
            this.snowBlur = Mth.clamp(this.snowBlur, 0, MAX_BLUR);
            this.forestsBlur = Mth.clamp(this.forestsBlur, 0, MAX_BLUR);
            this.biomesBlur = Mth.clamp(this.biomesBlur, 0, MAX_BLUR);
            this.waterBlur = Mth.clamp(this.waterBlur, 0, MAX_BLUR);
            this.shading = Mth.clamp(this.shading, 0, 1);
        }

        String signature() {
            return this.coasts + "," + this.snow + "," + this.forests + "," + this.biomes + "," + this.water + "," + this.coastsBlur + ","
                + this.snowBlur + "," + this.forestsBlur + "," + this.biomesBlur + "," + this.waterBlur + "," + this.shading;
        }

        public GeneralMeshFactory.Smoothing forBuilder() {
            return new GeneralMeshFactory.Smoothing(
                this.coasts, this.snow, this.forests, this.biomes, this.water, this.coastsBlur, this.snowBlur, this.forestsBlur,
                this.biomesBlur, this.waterBlur, this.shading
            );
        }
    }

    /**
     * Sharpness steps of the distant terrain: economical, normal, sharp, and maximum, where a cell takes two to three pixels, about as
     * fine as the screen can show; it is meant for looking at the world from high above.
     */
    public static final double[] SHARPNESS = {1.0, 1.5, 2.25, 8.0};
    /** Coarsest cells allowed at the start of the distant terrain: 16 blocks. */
    public static final int MAX_START_LEVEL = 4;
    /** Width of this mod's fog at a start of 100%, in blocks. */
    public static final float FOG_MIN_WIDTH = 16.0F;
    /** The fog out of water: another mod's when one has set it, otherwise this mod's. */
    public static final int FOG_AUTO = 0;
    /** The fog out of water: this mod's, over whatever other mods have set. */
    public static final int FOG_OWN = 1;
    /** The fog out of water: vanilla's as other mods leave it, going on over the LOD terrain. */
    public static final int FOG_OTHER = 2;
    /** No fog out of water. */
    public static final int FOG_OFF = 3;

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
        /**
         * How many blocks down from the top of a column the server looks; 0 until set, then the default of the dimension. Only the
         * owner of a singleplayer world decides it, on a server its config file does.
         */
        public int scanDepth = 0;
        /**
         * Ore veins in the chunks generated in memory (the overworld only). Off by default: those chunks are made faster and with
         * much less garbage. Decided like {@link #scanDepth}.
         */
        public boolean oreVeins = false;

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
                + "|" + this.blockDetail + "|" + this.scanDepth + "|" + this.oreVeins;
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
        if (this.meshThreadsVersion < 1) {
            // Up to 1.2.106 the default of 1 was written into every config file, even when the owner never chose it, so a stored
            // 1 from then means "not chosen" and becomes the automatic count. A 1 stored from now on is a real choice.
            if (this.meshThreads == 1) {
                this.meshThreads = 0;
            }
            this.meshThreadsVersion = 1;
        }
        this.meshThreads = Mth.clamp(this.meshThreads, 0, 4);
        this.loadSpeed = LoadSpeed.of(this.loadSpeed).ordinal();
        this.distant.values().forEach(DistantSettings::sanitize);
        this.detailed.values().forEach(DetailSettings::sanitize);
        for (DimensionKind kind : DimensionKind.values()) {
            if (kind.hasLod()) {
                DetailSettings detail = this.detail(kind);
                detail.scanDepth = detail.scanDepth <= 0
                    ? kind == DimensionKind.END ? FarPlaneConstants.DEFAULT_END_SCAN_DEPTH : FarPlaneConstants.DEFAULT_SCAN_DEPTH
                    : ServerConfig.scanDepth(detail.scanDepth);
            }
        }
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
            distant.fogMode = this.disableFog ? FOG_OFF : FOG_OWN;
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

    /** Mesh builder threads in use: the setting when it is set, otherwise by core count, like the server's detail threads. */
    public int resolvedMeshThreads() {
        if (this.meshThreads > 0) {
            return this.meshThreads;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return FarPlaneConstants.ANDROID ? Mth.clamp(cores / 2, 1, 4) : Mth.clamp(cores * 3 / 8, 1, 4);
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
