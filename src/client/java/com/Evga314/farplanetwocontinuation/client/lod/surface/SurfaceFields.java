package com.Evga314.farplanetwocontinuation.client.lod.surface;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;

/**
 * Everything {@link SurfaceMesher} needs of a rough tile, per sample of the tile ({@link #SIDE} by {@link #SIDE}, row by row from the
 * north-west): heights, the water, colors and borders as the tile's samples were shaped (see TileMeshBuilder). Plain values only, so
 * the mesher can be tested without the game. One is kept per builder thread and filled anew for every tile, see {@link #forThread}.
 */
public final class SurfaceFields {
    public static final int SIDE = FarPlaneConstants.TILE_SAMPLES;
    public static final int CELLS = FarPlaneConstants.TILE_CELLS;
    public static final int SAMPLES = SIDE * SIDE;

    private static final ThreadLocal<SurfaceFields> CURRENT = ThreadLocal.withInitial(SurfaceFields::new);

    /** Blocks between samples. */
    public int spacing;
    /** Islands over the void (the End): no water, the ground cut at the island edge, an underside under it. */
    public boolean floating;
    /** Where every column and row of samples lies, in {@link LodVertex#XZ_UNITS}: the tiles at the world border end short. */
    public final float[] columns = new float[SIDE];
    public final float[] rows = new float[SIDE];

    /** The ground: land with the tree tops on it, and under the water the seabed. */
    public final float[] ground = new float[SAMPLES];
    /** The level of the water at or next to a sample, NaN where there is none. */
    public final float[] waterLevel = new float[SAMPLES];
    /** Samples of water: the water is drawn over every triangle with one of them. */
    public final boolean[] water = new boolean[SAMPLES];
    /** Water drawn opaque whatever its depth: ice. */
    public final boolean[] opaque = new boolean[SAMPLES];
    /** The slopes of the ground along x and z, blocks per block, for the normals. */
    public final float[] slopeX = new float[SAMPLES];
    public final float[] slopeZ = new float[SAMPLES];
    /** How high the tree tops stand over the ground, in blocks. */
    public final float[] canopy = new float[SAMPLES];
    /** The material, see {@link LodVertex#BITS}. */
    public final byte[] material = new byte[SAMPLES];

    /** The ground's color, the one beyond its border, the value of that border and how soft it is (see {@link LodVertex}). */
    public final int[] color = new int[SAMPLES];
    public final int[] beyond = new int[SAMPLES];
    public final float[] split = new float[SAMPLES];
    public final byte[] softness = new byte[SAMPLES];
    /** The same for the water; how soft its borders are comes with the tile. */
    public final int[] waterColor = new int[SAMPLES];
    public final int[] waterBeyond = new int[SAMPLES];
    public final float[] waterSplit = new float[SAMPLES];

    /** How far the skirt under each sample on the tile's edge reaches down, in blocks. */
    public final float[] skirtDepth = new float[SAMPLES];
    /** Floating tiles: the signed distance to the island edge in cells, over the island above zero. */
    public final float[] edgeDistance = new float[SAMPLES];

    public static SurfaceFields forThread() {
        return CURRENT.get();
    }

    /** The sample at a column and row. */
    public static int at(int x, int z) {
        return x + z * SIDE;
    }

    /** Positions of a full tile, as in tests: every sample one spacing from the next. */
    public void evenPositions() {
        for (int i = 0; i < SIDE; i++) {
            this.columns[i] = i * LodVertex.XZ_UNITS;
            this.rows[i] = i * LodVertex.XZ_UNITS;
        }
    }
}
