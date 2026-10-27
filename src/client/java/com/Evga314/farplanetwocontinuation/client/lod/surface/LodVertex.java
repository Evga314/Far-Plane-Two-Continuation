package com.Evga314.farplanetwocontinuation.client.lod.surface;

import java.nio.ByteBuffer;

/**
 * The one vertex of the new renderer's rough tiles: its layout, written only here, and read by the outputs.
 * <p>
 * {@value #SIZE} bytes:
 * <ul>
 * <li>{@link #X}, {@link #Z}: unsigned shorts, {@link #XZ_UNITS} per sample spacing from the tile's north-west corner;</li>
 * <li>{@link #Y}: unsigned short, {@link #Y_UNITS} per block over the mesh's {@link SurfaceMesh#baseY};</li>
 * <li>{@link #WATER_LEVEL}: the same, the level of the water over this point, or the point's own height where there is none;</li>
 * <li>{@link #NORMAL}: two bytes, the normal folded onto an octahedron around up;</li>
 * <li>{@link #CANOPY}: how high the tree tops stand over the ground, in quarter blocks;</li>
 * <li>{@link #BITS}: the material in the low four bits (numbered as Distant Horizons numbers them, which shader packs know), then
 * {@link #BIT_WATER}, {@link #BIT_SKIRT}, {@link #BIT_UNDERSIDE};</li>
 * <li>{@link #COLOR}: RGBA, alpha the softness of the border (0 sharp to 5) on the ground, the opacity on the water;</li>
 * <li>{@link #SECOND}: RGBA, the color beyond the border (the snow line, forest edges, biomes on the ground, waters of other
 * temperatures on the water), alpha the value of the border, -1 to 1 as 0 to 255: beyond it where above zero.</li>
 * </ul>
 */
public final class LodVertex {
    public static final int SIZE = 20;
    public static final int X = 0;
    public static final int Z = 2;
    public static final int Y = 4;
    public static final int WATER_LEVEL = 6;
    public static final int NORMAL = 8;
    public static final int CANOPY = 10;
    public static final int BITS = 11;
    public static final int COLOR = 12;
    public static final int SECOND = 16;

    public static final float XZ_UNITS = 256.0F;
    public static final float Y_UNITS = 16.0F;
    public static final int MATERIAL_MASK = 15;
    public static final int BIT_WATER = 16;
    public static final int BIT_SKIRT = 32;
    public static final int BIT_UNDERSIDE = 64;

    /** Materials, numbered as Distant Horizons numbers them. */
    public static final int MATERIAL_LEAVES = 1;
    public static final int MATERIAL_STONE = 2;
    public static final int MATERIAL_DIRT = 5;
    public static final int MATERIAL_LAVA = 6;
    public static final int MATERIAL_SNOW = 8;
    public static final int MATERIAL_SAND = 9;
    public static final int MATERIAL_WATER = 12;
    public static final int MATERIAL_GRASS = 13;

    /** The normal straight up, see {@link #normal}. */
    public static final int UP = normal(0.0F, 1.0F, 0.0F);

    private LodVertex() {
    }

    public static void put(ByteBuffer out, int x, int z, int y, int waterLevel, int normal, int canopy, int bits, int color, int second) {
        out.putShort((short) x);
        out.putShort((short) z);
        out.putShort((short) y);
        out.putShort((short) waterLevel);
        out.put((byte) normal);
        out.put((byte) (normal >>> 8));
        out.put((byte) canopy);
        out.put((byte) bits);
        putColor(out, color);
        putColor(out, second);
    }

    /** ARGB as RGBA bytes. */
    private static void putColor(ByteBuffer out, int argb) {
        out.put((byte) (argb >>> 16));
        out.put((byte) (argb >>> 8));
        out.put((byte) argb);
        out.put((byte) (argb >>> 24));
    }

    /** A height as the vertex keeps it, see {@link #Y}. */
    public static int height(float y, float baseY) {
        return clamp(Math.round((y - baseY) * Y_UNITS), 0, 0xFFFF);
    }

    /** The same rounded down, for water: it never stands over vanilla's water where both are drawn. */
    public static int heightBelow(float y, float baseY) {
        return clamp((int) Math.floor((y - baseY) * Y_UNITS), 0, 0xFFFF);
    }

    public static int position(float units) {
        return clamp(Math.round(units), 0, 0xFFFF);
    }

    /** The value of a border, -1 to 1, as the alpha of {@link #SECOND} keeps it. */
    public static int split(float value) {
        return clamp(Math.round((Math.max(-1.0F, Math.min(1.0F, value)) + 1.0F) * 127.5F), 0, 255);
    }

    /** A normal in two bytes, the first in the low eight bits: x and z of where it meets an octahedron around up, folded below. */
    public static int normal(float x, float y, float z) {
        float sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
        float u = x / sum;
        float v = z / sum;
        if (y < 0.0F) {
            float foldedU = (1.0F - Math.abs(v)) * Math.signum(u);
            float foldedV = (1.0F - Math.abs(u)) * Math.signum(v);
            u = foldedU;
            v = foldedV;
        }
        return Math.round((u * 0.5F + 0.5F) * 255.0F) | Math.round((v * 0.5F + 0.5F) * 255.0F) << 8;
    }

    /** ARGB with another alpha. */
    public static int withAlpha(int argb, int alpha) {
        return argb & 0xFFFFFF | clamp(alpha, 0, 255) << 24;
    }

    /** ARGB with the color scaled, alpha kept. */
    public static int scaled(int argb, float factor) {
        int red = clamp(Math.round((argb >>> 16 & 255) * factor), 0, 255);
        int green = clamp(Math.round((argb >>> 8 & 255) * factor), 0, 255);
        int blue = clamp(Math.round((argb & 255) * factor), 0, 255);
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }
}
