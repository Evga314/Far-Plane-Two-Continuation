package com.Evga314.farplanetwocontinuation.client.lod.surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Makes the mesh of a rough tile for the new renderer from its {@link SurfaceFields}, in the mesh builder threads.
 * <p>
 * The ground is a height field over the tile's samples, the land at its height and the seabed at its depth, with skirts along the
 * edges; its triangles are those of every tile ({@link SurfaceIndices}), so only its vertices are the tile's own. The water is a
 * plane at its level over every triangle with water in it. Nothing cuts the coast: it lies where the ground goes under the plane,
 * which the depth test draws to the pixel, the way Distant Horizons and shader packs expect it. The heights of the ground by the water
 * come shaped so that it does so along the smoothed coast (see TileMeshBuilder#shapeCoasts).
 * <p>
 * The water is flat, so cells of it that are all alike are joined into larger pieces with no cracks to fear: each is a fan around
 * its middle over every sample of its rim, which the pieces beside it share. Open sea comes to a few dozen triangles a tile.
 * <p>
 * Islands over the void (the End) are cut at the island edge instead, where the signed distance to it crosses zero, and get an
 * underside; their triangles are the tile's own.
 */
public final class SurfaceMesher {
    private static final int SIDE = SurfaceFields.SIDE;
    private static final int CELLS = SurfaceFields.CELLS;
    private static final int SAMPLES = SurfaceFields.SAMPLES;

    /** Water is drawn this opaque at least, more the deeper it is, and opaque from {@link #OPAQUE_DEPTH} down. */
    private static final float WATER_MIN_OPACITY = 0.5F;
    /** The depth over which the water's opacity closes most of the way from the least to full, in blocks. */
    private static final float WATER_CLEAR_DEPTH = 6.0F;
    static final float OPAQUE_DEPTH = 16.0F;
    /** The underside of islands lies in shadow, like the bottom of a block. */
    private static final float UNDERSIDE_SHADE = 0.5F;
    /** How much deeper the underside lies per block from the island edge, and at most: a cone like vanilla's islands. */
    private static final float UNDERSIDE_SLOPE = 0.6F;
    private static final float UNDERSIDE_MAX_DEPTH = 48.0F;
    private static final float CANOPY_UNITS = 4.0F;

    private SurfaceMesher() {
    }

    /** What a builder thread works with, made once. */
    private static final class Work {
        private static final ThreadLocal<Work> CURRENT = ThreadLocal.withInitial(Work::new);

        ByteBuffer vertices = ByteBuffer.allocate(64 * 1024).order(ByteOrder.nativeOrder());
        int vertexCount;
        int[] indices = new int[8 * 1024];
        int indexCount;
        /** One vertex being made, to compare before it is kept. */
        final ByteBuffer one = ByteBuffer.allocate(LodVertex.SIZE).order(ByteOrder.nativeOrder());
        /** The water's vertex of every sample (-1 none yet), and what it holds past its position, to tell alike ones. */
        final int[] waterVertex = new int[SAMPLES];
        final long[] waterKey = new long[SAMPLES * 2];
        /** The piece of joined water every cell is in (-1 none), each piece's first cell, width and height, and the samples the rims need. */
        final int[] pieceOf = new int[CELLS * CELLS];
        final int[] pieces = new int[CELLS * CELLS * 4];
        final boolean[] needed = new boolean[SAMPLES];
        /** Islands: the vertices of the top and the underside at every sample and at every edge where the island edge crosses it. */
        final int[] top = new int[SAMPLES];
        final int[] under = new int[SAMPLES];
        final int[] crossingTop = new int[SAMPLES * 4];
        final int[] crossingUnder = new int[SAMPLES * 4];
        final int[] piece = new int[4];
        final int[] tops = new int[4];
        final int[] undersides = new int[4];
        final float[] distances = new float[3];
        final int[] corners = new int[3];
    }

    public static SurfaceMesh build(SurfaceFields fields) {
        Work work = Work.CURRENT.get();
        work.vertices.clear();
        work.vertexCount = 0;
        work.indexCount = 0;
        int groups = SurfaceIndices.groups(fields.spacing);
        int[] groundOffsets = new int[groups * groups + 1];
        int[] waterOffsets = new int[groups * groups + 1];
        float baseY = baseY(fields);
        if (fields.floating) {
            islands(work, fields, baseY, groups, groundOffsets);
            java.util.Arrays.fill(waterOffsets, work.indexCount);
        } else {
            for (int sample = 0; sample < SAMPLES; sample++) {
                ground(work, fields, sample, sample, 0.0F, baseY, false, 0);
            }
            for (int edge = 0; edge < 4; edge++) {
                for (int i = 0; i < SIDE; i++) {
                    int sample = SurfaceIndices.edgeSample(edge, i);
                    ground(work, fields, sample, sample, 0.0F, baseY, false, fields.skirtDepth[sample]);
                }
            }
            SurfaceIndices.Layout shared = SurfaceIndices.ground(groups);
            System.arraycopy(shared.offsets(), 0, groundOffsets, 0, groundOffsets.length);
            water(work, fields, baseY, groups, waterOffsets);
        }

        ByteBuffer vertices = ByteBuffer.allocateDirect(work.vertexCount * LodVertex.SIZE).order(ByteOrder.nativeOrder());
        work.vertices.flip();
        vertices.put(work.vertices).flip();
        ByteBuffer indices = ByteBuffer.allocateDirect(work.indexCount * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < work.indexCount; i++) {
            indices.putShort((short) work.indices[i]);
        }
        indices.flip();
        return new SurfaceMesh(vertices, work.vertexCount, baseY, groups, !fields.floating, indices, groundOffsets, waterOffsets, false);
    }

    /** A block under the lowest point of the mesh, the bottoms of the skirts and the undersides with it. */
    private static float baseY(SurfaceFields fields) {
        float lowest = Float.MAX_VALUE;
        for (int sample = 0; sample < SAMPLES; sample++) {
            float bottom = fields.ground[sample] - (fields.floating ? underDepth(fields.edgeDistance[sample], fields.spacing) : fields.skirtDepth[sample]);
            lowest = Math.min(lowest, bottom);
            if (!Float.isNaN(fields.waterLevel[sample])) {
                lowest = Math.min(lowest, fields.waterLevel[sample]);
            }
        }
        return (float) Math.floor(lowest) - 1.0F;
    }

    /**
     * A vertex of the ground at sample {@code from}, or {@code share} of the way to sample {@code to}; {@code drop} blocks lower for
     * the bottom of a skirt, and as the underside of an island with {@code underside}.
     */
    private static int ground(Work work, SurfaceFields f, int from, int to, float share, float baseY, boolean underside, float drop) {
        int near = share < 0.5F ? from : to;
        float x = lerp(share, f.columns[from % SIDE], f.columns[to % SIDE]);
        float z = lerp(share, f.rows[from / SIDE], f.rows[to / SIDE]);
        float ground = lerp(share, f.ground[from], f.ground[to]);
        // The underside lies deeper the farther in from the island edge, like vanilla's islands: from the distance at this very
        // point, so it meets the top at the edge.
        float y = ground - drop - (underside ? underDepth(lerp(share, f.edgeDistance[from], f.edgeDistance[to]), f.spacing) : 0.0F);
        float levelFrom = f.waterLevel[from];
        float levelTo = f.waterLevel[to];
        float level = Float.isNaN(levelFrom) ? levelTo : Float.isNaN(levelTo) ? levelFrom : lerp(share, levelFrom, levelTo);
        int height = LodVertex.height(y, baseY);
        int waterLevel = Float.isNaN(level) ? height : LodVertex.height(level, baseY);
        int normal = underside ? LodVertex.normal(0.0F, -1.0F, 0.0F)
            : LodVertex.normal(-lerp(share, f.slopeX[from], f.slopeX[to]), 1.0F, -lerp(share, f.slopeZ[from], f.slopeZ[to]));
        int canopy = underside ? 0 : LodVertex.clamp(Math.round(lerp(share, f.canopy[from], f.canopy[to]) * CANOPY_UNITS), 0, 255);
        int bits = (underside ? LodVertex.MATERIAL_STONE | LodVertex.BIT_UNDERSIDE : f.material[near] & LodVertex.MATERIAL_MASK)
            | (drop > 0.0F ? LodVertex.BIT_SKIRT : 0);
        int color = LodVertex.withAlpha(mix(share, f.color[from], f.color[to]), f.softness[near]);
        int second = LodVertex.withAlpha(mix(share, f.beyond[from], f.beyond[to]), LodVertex.split(lerp(share, f.split[from], f.split[to])));
        if (underside) {
            color = LodVertex.scaled(color, UNDERSIDE_SHADE);
            second = LodVertex.scaled(second, UNDERSIDE_SHADE);
        }
        ByteBuffer out = room(work);
        LodVertex.put(out, LodVertex.position(x), LodVertex.position(z), height, waterLevel, normal, canopy, bits, color, second);
        return work.vertexCount++;
    }

    // The water.

    private static void water(Work work, SurfaceFields f, float baseY, int groups, int[] offsets) {
        java.util.Arrays.fill(work.waterVertex, -1);
        java.util.Arrays.fill(work.pieceOf, -1);
        java.util.Arrays.fill(work.needed, false);
        for (int sample = 0; sample < SAMPLES; sample++) {
            if (!Float.isNaN(f.waterLevel[sample])) {
                waterBytes(work, f, sample, f.waterLevel[sample], baseY);
                work.waterKey[sample * 2] = work.one.getLong(4);
                work.waterKey[sample * 2 + 1] = work.one.getLong(12);
            }
        }
        // The pieces first, all of them, so the rims know which of their points the cells beside them need.
        int cellsPerGroup = CELLS / groups;
        int pieces = 0;
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                int x1 = (gx + 1) * cellsPerGroup;
                int z1 = (gz + 1) * cellsPerGroup;
                for (int z = gz * cellsPerGroup; z < z1; z++) {
                    for (int x = gx * cellsPerGroup; x < x1; x++) {
                        int key = SurfaceFields.at(x, z);
                        if (alike(work, f, x, z, key)) {
                            joinPiece(work, f, pieces++, x, z, x1, z1, key);
                        }
                    }
                }
            }
        }
        // The points the rims need: the corners of every piece and of every other cell with water. Each piece takes all of them on
        // its rim, the same ones its neighbours take, so no point lies on an edge it is not a corner of.
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int s00 = SurfaceFields.at(x, z);
                int piece = work.pieceOf[x + z * CELLS];
                boolean corner = piece >= 0
                    ? x == work.pieces[piece * 4] && z == work.pieces[piece * 4 + 1]
                    : f.water[s00] || f.water[s00 + 1] || f.water[s00 + SIDE] || f.water[s00 + SIDE + 1];
                if (piece >= 0 && corner) {
                    int width = work.pieces[piece * 4 + 2];
                    int height = work.pieces[piece * 4 + 3];
                    work.needed[s00] = true;
                    work.needed[s00 + width] = true;
                    work.needed[s00 + height * SIDE] = true;
                    work.needed[s00 + width + height * SIDE] = true;
                } else if (piece < 0 && corner) {
                    work.needed[s00] = true;
                    work.needed[s00 + 1] = true;
                    work.needed[s00 + SIDE] = true;
                    work.needed[s00 + SIDE + 1] = true;
                }
            }
        }
        // Then the triangles, chunk column by chunk column.
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                offsets[gx + gz * groups] = work.indexCount;
                for (int z = gz * cellsPerGroup; z < (gz + 1) * cellsPerGroup; z++) {
                    for (int x = gx * cellsPerGroup; x < (gx + 1) * cellsPerGroup; x++) {
                        int piece = work.pieceOf[x + z * CELLS];
                        if (piece < 0) {
                            waterCell(work, f, baseY, x, z);
                        } else if (x == work.pieces[piece * 4] && z == work.pieces[piece * 4 + 1]) {
                            piece(work, f, baseY, piece);
                        }
                    }
                }
            }
        }
        offsets[groups * groups] = work.indexCount;
    }

    /** Whether all four corners of a cell are water just like sample {@code key}: then it can be joined with others like it. */
    private static boolean alike(Work work, SurfaceFields f, int x, int z, int key) {
        if (work.pieceOf[x + z * CELLS] >= 0) {
            return false;
        }
        int s00 = SurfaceFields.at(x, z);
        return sameWater(work, f, s00, key) && sameWater(work, f, s00 + 1, key) && sameWater(work, f, s00 + SIDE, key)
            && sameWater(work, f, s00 + SIDE + 1, key);
    }

    private static boolean sameWater(Work work, SurfaceFields f, int sample, int key) {
        return f.water[sample] && !Float.isNaN(f.waterLevel[sample]) && work.waterKey[sample * 2] == work.waterKey[key * 2]
            && work.waterKey[sample * 2 + 1] == work.waterKey[key * 2 + 1];
    }

    /**
     * The cells like the one at {@code x, z} as one piece, within its chunk column ({@code x1, z1} its end): as wide along the row
     * as they go, then as many rows down as are all like it.
     */
    private static void joinPiece(Work work, SurfaceFields f, int piece, int x0, int z0, int x1, int z1, int key) {
        int width = 1;
        while (x0 + width < x1 && alike(work, f, x0 + width, z0, key)) {
            width++;
        }
        int height = 1;
        rows:
        while (z0 + height < z1) {
            for (int x = x0; x < x0 + width; x++) {
                if (!alike(work, f, x, z0 + height, key)) {
                    break rows;
                }
            }
            height++;
        }
        for (int z = z0; z < z0 + height; z++) {
            for (int x = x0; x < x0 + width; x++) {
                work.pieceOf[x + z * CELLS] = piece;
            }
        }
        work.pieces[piece * 4] = x0;
        work.pieces[piece * 4 + 1] = z0;
        work.pieces[piece * 4 + 2] = width;
        work.pieces[piece * 4 + 3] = height;
    }

    /**
     * A piece of water: two triangles when only its corners are needed, else a fan round its middle over the points of its rim the
     * cells beside it need.
     */
    private static void piece(Work work, SurfaceFields f, float baseY, int piece) {
        int x0 = work.pieces[piece * 4];
        int z0 = work.pieces[piece * 4 + 1];
        int width = work.pieces[piece * 4 + 2];
        int height = work.pieces[piece * 4 + 3];
        int key = SurfaceFields.at(x0, z0);
        int rim = 0;
        for (int step = 0; step < 2 * (width + height); step++) {
            if (work.needed[rimSample(step, x0, z0, width, height)]) {
                rim++;
            }
        }
        if (rim == 4) {
            int a = waterVertex(work, f, key, baseY, Float.NaN);
            int b = waterVertex(work, f, key + width, baseY, Float.NaN);
            int c = waterVertex(work, f, key + height * SIDE, baseY, Float.NaN);
            int d = waterVertex(work, f, key + width + height * SIDE, baseY, Float.NaN);
            triangle(work, a, c, b);
            triangle(work, b, c, d);
            return;
        }
        // The middle: the same water, half way across.
        waterBytes(work, f, key, f.waterLevel[key], baseY);
        work.one.putShort(LodVertex.X, (short) LodVertex.position((f.columns[x0] + f.columns[x0 + width]) * 0.5F));
        work.one.putShort(LodVertex.Z, (short) LodVertex.position((f.rows[z0] + f.rows[z0 + height]) * 0.5F));
        int middle = keep(work);
        int first = -1;
        int previous = -1;
        for (int step = 0; step < 2 * (width + height); step++) {
            int sample = rimSample(step, x0, z0, width, height);
            if (!work.needed[sample]) {
                continue;
            }
            int vertex = waterVertex(work, f, sample, baseY, Float.NaN);
            if (previous >= 0) {
                triangle(work, middle, previous, vertex);
            } else {
                first = vertex;
            }
            previous = vertex;
        }
        triangle(work, middle, previous, first);
    }

    /** The samples round a piece the way its triangles face up: down the west side, east along the south, up the east, back along the north. */
    private static int rimSample(int step, int x0, int z0, int width, int height) {
        if (step < height) {
            return SurfaceFields.at(x0, z0 + step);
        } else if (step < height + width) {
            return SurfaceFields.at(x0 + step - height, z0 + height);
        } else if (step < 2 * height + width) {
            return SurfaceFields.at(x0 + width, z0 + height - (step - height - width));
        }
        return SurfaceFields.at(x0 + width - (step - 2 * height - width), z0);
    }

    /** The water over one cell: each of its triangles with water at a corner, split as the ground is. */
    private static void waterCell(Work work, SurfaceFields f, float baseY, int x, int z) {
        int a = SurfaceFields.at(x, z);
        int b = a + 1;
        int c = a + SIDE;
        int d = c + 1;
        if (((x + z) & 1) == 0) {
            waterTriangle(work, f, baseY, a, c, b);
            waterTriangle(work, f, baseY, b, c, d);
        } else {
            waterTriangle(work, f, baseY, a, c, d);
            waterTriangle(work, f, baseY, a, d, b);
        }
    }

    private static void waterTriangle(Work work, SurfaceFields f, float baseY, int a, int b, int c) {
        if (!f.water[a] && !f.water[b] && !f.water[c]) {
            return;
        }
        // A corner without a level of its own (none of the water is next to it, which the shaping does not leave) takes the highest.
        float highest = Math.max(levelOrLowest(f, a), Math.max(levelOrLowest(f, b), levelOrLowest(f, c)));
        if (highest == -Float.MAX_VALUE) {
            return;
        }
        triangle(work, waterVertex(work, f, a, baseY, highest), waterVertex(work, f, b, baseY, highest), waterVertex(work, f, c, baseY, highest));
    }

    private static float levelOrLowest(SurfaceFields f, int sample) {
        float level = f.waterLevel[sample];
        return Float.isNaN(level) ? -Float.MAX_VALUE : level;
    }

    /** The water's vertex at a sample, made once; with {@code fallback} as its level where the sample has none, made each time. */
    private static int waterVertex(Work work, SurfaceFields f, int sample, float baseY, float fallback) {
        float level = f.waterLevel[sample];
        if (Float.isNaN(level)) {
            waterBytes(work, f, sample, fallback, baseY);
            return keep(work);
        }
        int vertex = work.waterVertex[sample];
        if (vertex < 0) {
            waterBytes(work, f, sample, level, baseY);
            vertex = keep(work);
            work.waterVertex[sample] = vertex;
        }
        return vertex;
    }

    /** The water's vertex at a sample into {@link Work#one}, not kept yet. */
    private static void waterBytes(Work work, SurfaceFields f, int sample, float level, float baseY) {
        float depth = Math.max(level - f.ground[sample], 0.0F);
        float opacity = f.opaque[sample] || depth >= OPAQUE_DEPTH
            ? 1.0F : lerp(1.0F - (float) Math.exp(-depth / WATER_CLEAR_DEPTH), WATER_MIN_OPACITY, 1.0F);
        int height = LodVertex.heightBelow(level, baseY);
        ByteBuffer one = work.one;
        one.clear();
        LodVertex.put(one, LodVertex.position(f.columns[sample % SIDE]), LodVertex.position(f.rows[sample / SIDE]), height, height,
            LodVertex.UP, 0, LodVertex.MATERIAL_WATER | LodVertex.BIT_WATER, LodVertex.withAlpha(f.waterColor[sample], Math.round(opacity * 255.0F)),
            LodVertex.withAlpha(f.waterBeyond[sample], LodVertex.split(f.waterSplit[sample])));
    }

    /** Keeps the vertex in {@link Work#one}. */
    private static int keep(Work work) {
        ByteBuffer out = room(work);
        work.one.flip();
        out.put(work.one);
        return work.vertexCount++;
    }

    // Islands.

    private static void islands(Work work, SurfaceFields f, float baseY, int groups, int[] offsets) {
        java.util.Arrays.fill(work.top, -1);
        java.util.Arrays.fill(work.under, -1);
        java.util.Arrays.fill(work.crossingTop, -1);
        java.util.Arrays.fill(work.crossingUnder, -1);
        int cellsPerGroup = CELLS / groups;
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                offsets[gx + gz * groups] = work.indexCount;
                for (int z = gz * cellsPerGroup; z < (gz + 1) * cellsPerGroup; z++) {
                    for (int x = gx * cellsPerGroup; x < (gx + 1) * cellsPerGroup; x++) {
                        int a = SurfaceFields.at(x, z);
                        int b = a + 1;
                        int c = a + SIDE;
                        int d = c + 1;
                        if (((x + z) & 1) == 0) {
                            islandTriangle(work, f, baseY, a, c, b);
                            islandTriangle(work, f, baseY, b, c, d);
                        } else {
                            islandTriangle(work, f, baseY, a, c, d);
                            islandTriangle(work, f, baseY, a, d, b);
                        }
                    }
                }
            }
        }
        offsets[groups * groups] = work.indexCount;
    }

    /** The part of a triangle over the island, from above and from below. */
    private static void islandTriangle(Work work, SurfaceFields f, float baseY, int a, int b, int c) {
        int[] corners = work.corners;
        float[] distances = work.distances;
        corners[0] = a;
        corners[1] = b;
        corners[2] = c;
        boolean any = false;
        for (int k = 0; k < 3; k++) {
            distances[k] = f.edgeDistance[corners[k]];
            any |= distances[k] > 0.0F;
        }
        if (!any) {
            return;
        }
        // The corners on the island and the points where the edge crosses the triangle's sides, in order round it.
        int[] piece = work.piece;
        int size = 0;
        for (int k = 0; k < 3; k++) {
            int next = (k + 1) % 3;
            boolean inside = distances[k] > 0.0F;
            if (inside) {
                piece[size++] = corners[k];
            }
            if (inside != distances[next] > 0.0F) {
                piece[size++] = -1 - crossingSlot(corners[k], corners[next]);
            }
        }
        int[] tops = work.tops;
        int[] undersides = work.undersides;
        for (int i = 0; i < size; i++) {
            tops[i] = islandVertex(work, f, baseY, piece[i], false);
            undersides[i] = islandVertex(work, f, baseY, piece[i], true);
        }
        for (int i = 1; i + 1 < size; i++) {
            triangle(work, tops[0], tops[i], tops[i + 1]);
            triangle(work, undersides[0], undersides[i + 1], undersides[i]);
        }
    }

    /** Where the crossings of an edge between two samples are kept: the first sample of the two, and which way the edge runs from it. */
    private static int crossingSlot(int one, int other) {
        int low = Math.min(one, other);
        int step = Math.max(one, other) - low;
        int way = step == 1 ? 0 : step == SIDE ? 1 : step == SIDE + 1 ? 2 : 3;
        return low * 4 + way;
    }

    /** The vertex of a sample (0 and up) or of a crossing (-1 - its slot), made once each for the top and the underside. */
    private static int islandVertex(Work work, SurfaceFields f, float baseY, int point, boolean underside) {
        if (point >= 0) {
            int[] made = underside ? work.under : work.top;
            if (made[point] < 0) {
                made[point] = ground(work, f, point, point, 0.0F, baseY, underside, 0.0F);
            }
            return made[point];
        }
        int slot = -1 - point;
        int[] made = underside ? work.crossingUnder : work.crossingTop;
        if (made[slot] < 0) {
            // From the first sample of the edge, so both triangles on it put the point at the same place.
            int from = slot / 4;
            int way = slot % 4;
            int to = from + (way == 0 ? 1 : way == 1 ? SIDE : way == 2 ? SIDE + 1 : SIDE - 1);
            float here = f.edgeDistance[from];
            float there = f.edgeDistance[to];
            made[slot] = ground(work, f, from, to, here / (here - there), baseY, underside, 0.0F);
        }
        return made[slot];
    }

    // Plumbing.

    private static void triangle(Work work, int a, int b, int c) {
        if (work.indexCount + 3 > work.indices.length) {
            work.indices = java.util.Arrays.copyOf(work.indices, work.indices.length * 2);
        }
        work.indices[work.indexCount++] = a;
        work.indices[work.indexCount++] = b;
        work.indices[work.indexCount++] = c;
    }

    /** The vertex buffer with room for one more. */
    private static ByteBuffer room(Work work) {
        ByteBuffer out = work.vertices;
        if (out.remaining() < LodVertex.SIZE) {
            ByteBuffer bigger = ByteBuffer.allocate(out.capacity() * 2).order(ByteOrder.nativeOrder());
            out.flip();
            bigger.put(out);
            work.vertices = out = bigger;
        }
        return out;
    }

    /** How deep the underside of an island lies under a point this many cells in from its edge. */
    static float underDepth(float distance, int spacing) {
        return Math.min(Math.max(0.0F, distance) * spacing * UNDERSIDE_SLOPE, UNDERSIDE_MAX_DEPTH);
    }

    private static float lerp(float share, float from, float to) {
        return from + share * (to - from);
    }

    /** Two ARGB colors mixed, alpha too. */
    private static int mix(float share, int from, int to) {
        if (share == 0.0F || from == to) {
            return from;
        }
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int channel = Math.round(lerp(share, from >>> shift & 255, to >>> shift & 255));
            result |= LodVertex.clamp(channel, 0, 255) << shift;
        }
        return result;
    }
}
