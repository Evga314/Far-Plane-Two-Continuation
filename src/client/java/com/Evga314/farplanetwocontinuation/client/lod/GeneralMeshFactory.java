package com.Evga314.farplanetwocontinuation.client.lod;

public class GeneralMeshFactory {
    /** Flat colored detailed tiles: position and color. */
    public static final int VERTEX_SIZE = 16;
    /** Rough tiles: position, land color, second color and the colors beyond the borders on land and between waters. */
    public static final int ROUGH_VERTEX_SIZE = 28;
    /** Steps of softness a sample can have: 0 sharp, then wider and wider. */
    public static final int MAX_SOFTNESS = 5;
    /**
     * Tree tops are stored in the alpha of a vertex as 255 minus their height in quarter blocks, so the shader can lay them flat inside
     * the detailed terrain and vanilla's chunks. Alpha 0 stays the mark of the void.
     */
    static final float WATER_SHADE = 0.92F;

    /** smoothing это сглаживание. coasts - берега. */
    public record Smoothing(
        int coasts, int snow, int forests, int biomes, int water, int coastsBlur, int snowBlur, int forestsBlur, int biomesBlur,
        int waterBlur, int shading
    ) {
        public static final Smoothing DEFAULT = new Smoothing(4, 3, 3, 3, 3, 0, 0, 2, 3, 3, 1);
    }
}
