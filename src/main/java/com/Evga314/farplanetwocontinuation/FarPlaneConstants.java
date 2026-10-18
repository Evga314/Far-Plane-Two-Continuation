package com.Evga314.farplanetwocontinuation;

public final class FarPlaneConstants {
    /** Running on Android (MojoLauncher and similar launchers run the game on a regular Java there). */
    public static final boolean ANDROID = java.nio.file.Files.exists(java.nio.file.Path.of("/system/build.prop"))
        || System.getProperty("os.version", "").toLowerCase(java.util.Locale.ROOT).contains("android");

    public static final String MOD_ID = "farplanetwocontinuation";
    public static final int PROTOCOL_VERSION = 11;

    /** Cells along one tile edge. A tile holds TILE_CELLS + 1 samples per edge so neighbours share border samples. */
    public static final int TILE_CELLS = 32;
    public static final int TILE_SAMPLES = TILE_CELLS + 1;
    public static final int TILE_SAMPLE_COUNT = TILE_SAMPLES * TILE_SAMPLES;

    /** Level L has a sample spacing of 2^L blocks. Level 19 tiles are ~16.7M blocks wide, enough to cover the world with 4x4 roots. */
    public static final int MAX_LEVEL = 19;
    public static final int MAX_MIN_LEVEL = 5;

    /** Same limit vanilla uses for the world border. */
    public static final int WORLD_LIMIT = 29_999_984;
    public static final int MAX_LOD_DISTANCE = 30_000_000;
    public static final int MIN_LOD_DISTANCE = 256;

    /**
     * Detailed tiles use the same squares as {@link com.Evga314.farplanetwocontinuation.tile.TilePos}, but with 64 cells per edge built
     * from real chunk data. Level L has cells of 2^(L-1) blocks: level 1 is one cell per block, level 3 one cell per 4 blocks.
     * Coarser cells would look blockier than the smooth rough terrain, which takes over from there.
     */
    public static final int DETAIL_CELLS = 64;
    public static final int DETAIL_MIN_LEVEL = 1;
    public static final int DETAIL_MAX_LEVEL = 3;
    public static final int MAX_DETAIL_RADIUS_CHUNKS = 256;

    private FarPlaneConstants() {
    }

    public static int tileSize(int level) {
        return TILE_CELLS << level;
    }
}
