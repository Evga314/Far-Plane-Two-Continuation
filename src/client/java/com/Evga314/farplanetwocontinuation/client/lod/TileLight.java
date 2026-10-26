package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.tile.DetailData;
import org.jspecify.annotations.Nullable;

/**
 * The light of blocks over the faces of one detailed tile. Where the world has lit the chunk (see DetailData#lit) it is the world's
 * own light, sent for the top of every segment: closed caves stay dark and light goes across the edges of tiles as in the world. A
 * corner takes the brightest of the cells around it, each at the corner's height: the light over the floor under it, a level less per
 * block up, or under the ceiling over it, a level less per block down; inside the ground a cell gives none. Corners touching cells the
 * world never lit (virtual chunks) also take the light worked out from the blocks that give it, see {@link BlockLights}.
 */
final class TileLight {
    private static final int CELLS = DetailData.CELLS;
    private static final float EPSILON = 1.0E-3F;

    private final DetailData data;
    /** Per block of the palette: stops light. */
    private final boolean[] opaque;
    private final int cell;
    /** Per cell and ring cell lit by the world: its brightest segment, 0 for the rest. */
    private final byte[] brightest;
    private final @Nullable BlockLights sources;

    private TileLight(DetailData data, boolean[] opaque, int cell, byte[] brightest, @Nullable BlockLights sources) {
        this.data = data;
        this.opaque = opaque;
        this.cell = cell;
        this.brightest = brightest;
        this.sources = sources;
    }

    /**
     * The light of a tile of cells {@code cell} blocks wide, from {@code opaque} and {@code emissions} (the light each block gives) per
     * block of the palette; null where no light of blocks lies on it at all.
     */
    static @Nullable TileLight of(DetailData data, boolean[] opaque, int[] emissions, int cell) {
        byte[] brightest = new byte[DetailData.TOTAL_CELLS];
        boolean bright = false;
        boolean unlit = false;
        for (int index = 0; index < DetailData.TOTAL_CELLS; index++) {
            int count = data.segCounts[index];
            if (count == 0) {
                continue;
            }
            if (!data.isLit(index)) {
                unlit = true;
                continue;
            }
            int offset = data.segOffsets[index];
            int max = 0;
            for (int s = offset; s < offset + count; s++) {
                max = Math.max(max, data.segLights[s]);
            }
            brightest[index] = (byte) max;
            bright |= max > 0;
        }
        BlockLights sources = unlit ? sources(data, opaque, emissions, cell) : null;
        if (!bright && sources == null) {
            return null;
        }
        return new TileLight(data, opaque, cell, brightest, sources);
    }

    /**
     * The blocks giving light in the tile, its ring and beyond it (see DetailData#edgeLights), each shining from the middle of the top
     * block of its segment, up to the first block over it that stops light; null for none.
     */
    private static @Nullable BlockLights sources(DetailData data, boolean[] opaque, int[] emissions, int cell) {
        BlockLights lights = null;
        float half = cell * 0.5F;
        for (int cz = -1; cz <= CELLS; cz++) {
            for (int cx = -1; cx <= CELLS; cx++) {
                int index = DetailData.cellIndex(cx, cz);
                if (index < 0) {
                    continue;
                }
                int count = data.segCounts[index];
                int offset = data.segOffsets[index];
                int top = data.stackTops[index];
                float ceiling = Float.MAX_VALUE;
                for (int s = offset; s < offset + count; s++) {
                    int block = Short.toUnsignedInt(data.segBlocks[s]);
                    int emission = emissions[block];
                    if (emission > 0) {
                        if (lights == null) {
                            lights = new BlockLights(0.0F, 0.0F, CELLS * cell);
                        }
                        lights.add(cx * cell + half, top - 0.5F, cz * cell + half, ceiling, Math.max(emission, data.segLights[s]));
                    }
                    if (opaque[block]) {
                        ceiling = top - data.segHeights[s];
                    }
                    top -= data.segHeights[s];
                }
            }
        }
        int[] edge = data.edgeLights;
        for (int i = 0; i < edge.length; i += DetailData.EDGE_LIGHT_FIELDS) {
            if (lights == null) {
                lights = new BlockLights(0.0F, 0.0F, CELLS * cell);
            }
            float ceiling = edge[i + 3] == DetailData.OPEN_CEILING ? Float.MAX_VALUE : edge[i + 3];
            lights.add(edge[i] * cell + half, edge[i + 2] - 0.5F, edge[i + 1] * cell + half, ceiling, edge[i + 4]);
        }
        return lights;
    }

    /** The light of blocks at a corner, 0 to 15, in the tile's own coordinates. */
    int at(float x, float y, float z) {
        int cx0 = (int) Math.floor(x / this.cell - 0.5F);
        int cz0 = (int) Math.floor(z / this.cell - 0.5F);
        int best = 0;
        boolean unlit = false;
        for (int i = 0; i < 4; i++) {
            int index = DetailData.cellIndex(cx0 + (i & 1), cz0 + (i >> 1));
            if (index < 0 || this.data.segCounts[index] == 0) {
                continue;
            }
            if (!this.data.isLit(index)) {
                unlit = true;
            } else if (this.brightest[index] > best) {
                best = Math.max(best, this.column(index, y));
            }
        }
        if (unlit && this.sources != null) {
            best = Math.max(best, this.sources.at(x, y, z));
        }
        return best;
    }

    /** The light of a cell lit by the world at a height, -1 inside the ground. */
    private int column(int index, float y) {
        DetailData data = this.data;
        int offset = data.segOffsets[index];
        int end = offset + data.segCounts[index];
        int top = data.stackTops[index];
        if (y >= top - EPSILON) {
            return data.segLights[offset] - (int) (y - top + EPSILON);
        }
        for (int s = offset; s < end; s++) {
            int bottom = top - data.segHeights[s];
            if (y > top - 1 + EPSILON) {
                // On the top of the segment or in its top block: a thin block, a plant.
                return data.segLights[s];
            }
            if (y > bottom + EPSILON) {
                if (this.opaque[Short.toUnsignedInt(data.segBlocks[s])]) {
                    return -1;
                }
                // In air or water: the light under the ceiling and over the floor, fading away from them.
                int light = data.segLights[s] - (int) (top - 0.5F - y);
                if (s + 1 < end) {
                    light = Math.max(light, data.segLights[s + 1] - (int) (y - bottom));
                }
                return light;
            }
            top = bottom;
        }
        return -1;
    }
}
