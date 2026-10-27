package com.Evga314.farplanetwocontinuation.client.lod.surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Makes a rough tile's mesh for shader packs from its {@link SurfaceMesh}, in the mesh builder threads: the same ground and water, in
 * the vertex format of Distant Horizons, which the packs' DH programs read (see IrisOutput), and with the borders cut into it.
 * <p>
 * A pack's program has one color per vertex and blends it over the triangle, so the borders the mod's own shader draws per pixel (the
 * snow line, forest edges, biomes, waters of other temperatures, see {@link LodVertex#SECOND}) would smear over whole cells. Each
 * triangle is cut where the value of its border crosses zero instead, one side taking the color, the other the color beyond: sharp, as
 * the pack draws its own light and fog over it. With soft borders it is cut twice, where the value crosses minus and plus the
 * softness, and between the cuts the color goes from one to the other. The cuts lie where the values along the triangle's edges say, so
 * triangles that share an edge cut it at the same points, and those points are shared: no cracks.
 * <p>
 * {@value #SIZE} bytes a vertex, as DH's:
 * <ul>
 * <li>x, y, z: unsigned shorts, whole blocks from the tile's north-west corner at {@link SurfaceMesh#baseY} (DH's positions are whole
 * blocks; heights are rounded at the vertices only, the slopes between them stay);</li>
 * <li>the light: an unsigned short, sky light in the low four bits, block light in the next four;</li>
 * <li>the color, RGBA, alpha the opacity of the water;</li>
 * <li>the material (as DH numbers them), the direction of the face (DH's table: down, up, north, south, west, east), and two bytes
 * of a texture packs could take from a block atlas, none.</li>
 * </ul>
 */
public final class PackMesher {
    public static final int SIZE = 16;
    public static final int POSITION = 0;
    public static final int COLOR = 8;
    public static final int EXTRA = 12;

    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;
    /** The beyond side of a border this bright in every channel is snow, for the pack's materials. */
    private static final int SNOW_BRIGHTNESS = 200;
    private static final int MAX_VERTICES = 0xFFFF;

    /** Sides of a cut: the color, between the two cuts of a soft border, the color beyond. */
    private static final int BELOW = 0;
    private static final int BAND = 1;
    private static final int ABOVE = 2;
    /** The level of a point's key that marks a corner, see {@link #corner}. */
    private static final int CORNER = 3;

    private PackMesher() {
    }

    /** What a builder thread works with, made once. */
    private static final class Work {
        private static final ThreadLocal<Work> CURRENT = ThreadLocal.withInitial(Work::new);

        // The source vertices, read once: position in blocks, the depth under the water, colors, border value and softness, normal.
        float[] x = new float[2048];
        float[] y = new float[2048];
        float[] z = new float[2048];
        float[] depth = new float[2048];
        int[] color = new int[2048];
        int[] second = new int[2048];
        float[] value = new float[2048];
        float[] soft = new float[2048];
        float[] nx = new float[2048];
        float[] ny = new float[2048];
        float[] nz = new float[2048];
        int[] bits = new int[2048];

        ByteBuffer vertices = ByteBuffer.allocate(64 * 1024).order(ByteOrder.nativeOrder());
        int vertexCount;
        int[] indices = new int[16 * 1024];
        int indexCount;
        /** The vertices made, by where they lie (see {@link #corner}): open addressing, a slot taken when its stamp is this tile's. */
        long[] keys = new long[1 << 14];
        int[] ids = new int[1 << 14];
        int[] stamps = new int[1 << 14];
        int stamp;

        // Polygons while a triangle is cut: per point the weights of the triangle's corners and what it lies on, see key.
        final float[][] weights = {new float[24], new float[24], new float[24]};
        final long[] pointKeys = new long[8];
        final float[][] cutWeights = {new float[24], new float[24], new float[24]};
        final long[] cutKeys = new long[8];
        final int[] corners = new int[3];
        /** The face of the triangle being cut when all its vertices take it (a skirt), else -1. */
        int face = -1;

        void grow(int count) {
            if (this.x.length >= count) {
                return;
            }
            int size = Math.max(count, this.x.length * 2);
            this.x = Arrays.copyOf(this.x, size);
            this.y = Arrays.copyOf(this.y, size);
            this.z = Arrays.copyOf(this.z, size);
            this.depth = Arrays.copyOf(this.depth, size);
            this.color = Arrays.copyOf(this.color, size);
            this.second = Arrays.copyOf(this.second, size);
            this.value = Arrays.copyOf(this.value, size);
            this.soft = Arrays.copyOf(this.soft, size);
            this.nx = Arrays.copyOf(this.nx, size);
            this.ny = Arrays.copyOf(this.ny, size);
            this.nz = Arrays.copyOf(this.nz, size);
            this.bits = Arrays.copyOf(this.bits, size);
        }
    }

    /**
     * The mesh for packs, or null when it would have more vertices than its indices can count (never seen; the tile is then not drawn
     * with the pack).
     *
     * @param ground the ground's triangles, {@link SurfaceIndices#ground} where the mesh shares them, else its own indices
     * @param spacing blocks between the tile's samples
     * @param softBorders cut soft borders twice, with the colors blended between the cuts; else every border is sharp
     */
    public static SurfaceMesh build(SurfaceMesh mesh, ByteBuffer ground, int spacing, boolean softBorders) {
        Work work = Work.CURRENT.get();
        work.vertices.clear();
        work.vertexCount = 0;
        work.indexCount = 0;
        if (++work.stamp == 0) {
            Arrays.fill(work.stamps, 0);
            work.stamp = 1;
        }
        read(work, mesh, spacing);

        int groups = mesh.groups() * mesh.groups();
        int[] groundOffsets = new int[groups + 1];
        int[] waterOffsets = new int[groups + 1];
        int[] sourceGround = mesh.groundOffsets();
        for (int g = 0; g < groups; g++) {
            groundOffsets[g] = work.indexCount;
            for (int i = sourceGround[g]; i < sourceGround[g + 1]; i += 3) {
                triangle(work, ground.getShort(i * 2) & 0xFFFF, ground.getShort(i * 2 + 2) & 0xFFFF, ground.getShort(i * 2 + 4) & 0xFFFF, softBorders);
            }
        }
        groundOffsets[groups] = work.indexCount;
        ByteBuffer own = mesh.indices();
        int[] sourceWater = mesh.waterOffsets();
        for (int g = 0; g < groups; g++) {
            waterOffsets[g] = work.indexCount;
            for (int i = sourceWater[g]; i < sourceWater[g + 1]; i += 3) {
                // The borders between waters stay sharp: they are faint, and the pack draws the water its own way.
                triangle(work, own.getShort(i * 2) & 0xFFFF, own.getShort(i * 2 + 2) & 0xFFFF, own.getShort(i * 2 + 4) & 0xFFFF, false);
            }
        }
        waterOffsets[groups] = work.indexCount;
        if (work.vertexCount > MAX_VERTICES) {
            return null;
        }

        ByteBuffer vertices = ByteBuffer.allocateDirect(work.vertexCount * SIZE).order(ByteOrder.nativeOrder());
        work.vertices.flip();
        vertices.put(work.vertices).flip();
        ByteBuffer indices = ByteBuffer.allocateDirect(work.indexCount * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < work.indexCount; i++) {
            indices.putShort((short) work.indices[i]);
        }
        indices.flip();
        return new SurfaceMesh(vertices, work.vertexCount, mesh.baseY(), mesh.groups(), false, indices, groundOffsets, waterOffsets, true);
    }

    /** Reads the source vertices into {@link Work}'s arrays. */
    private static void read(Work work, SurfaceMesh mesh, int spacing) {
        int count = mesh.vertexCount();
        work.grow(count);
        ByteBuffer source = mesh.vertices();
        float blocks = spacing / LodVertex.XZ_UNITS;
        for (int v = 0; v < count; v++) {
            int at = v * LodVertex.SIZE;
            float height = (source.getShort(at + LodVertex.Y) & 0xFFFF) / LodVertex.Y_UNITS;
            work.x[v] = (source.getShort(at + LodVertex.X) & 0xFFFF) * blocks;
            work.z[v] = (source.getShort(at + LodVertex.Z) & 0xFFFF) * blocks;
            work.y[v] = height;
            work.depth[v] = (source.getShort(at + LodVertex.WATER_LEVEL) & 0xFFFF) / LodVertex.Y_UNITS - height;
            int bits = source.get(at + LodVertex.BITS) & 0xFF;
            work.bits[v] = bits;
            int color = rgba(source, at + LodVertex.COLOR);
            int second = rgba(source, at + LodVertex.SECOND);
            work.color[v] = color;
            work.second[v] = second;
            work.value[v] = (second >>> 24) / 127.5F - 1.0F;
            // The ground keeps the softness of its border in the color's alpha (0 sharp, each step twice as wide), as surface.vsh reads it.
            int steps = color >>> 24;
            work.soft[v] = (bits & LodVertex.BIT_WATER) == 0 && steps > 0 ? 0.02F * (1 << steps) : 0.0F;
            float u = (source.get(at + LodVertex.NORMAL) & 0xFF) / 255.0F * 2.0F - 1.0F;
            float w = (source.get(at + LodVertex.NORMAL + 1) & 0xFF) / 255.0F * 2.0F - 1.0F;
            float up = 1.0F - Math.abs(u) - Math.abs(w);
            if (up < 0.0F) {
                float foldedU = (1.0F - Math.abs(w)) * Math.signum(u);
                float foldedW = (1.0F - Math.abs(u)) * Math.signum(w);
                u = foldedU;
                w = foldedW;
            }
            work.nx[v] = u;
            work.ny[v] = up;
            work.nz[v] = w;
        }
    }

    /** RGBA bytes as ARGB. */
    private static int rgba(ByteBuffer source, int at) {
        return (source.get(at + 3) & 0xFF) << 24 | (source.get(at) & 0xFF) << 16 | (source.get(at + 1) & 0xFF) << 8 | source.get(at + 2) & 0xFF;
    }

    /** One source triangle, cut along its border into pieces of one side each, every piece a fan. */
    private static void triangle(Work work, int a, int b, int c, boolean softBorders) {
        int[] corners = work.corners;
        corners[0] = a;
        corners[1] = b;
        corners[2] = c;
        work.face = skirtFace(work, a, b, c);
        float soft = softBorders ? Math.max(work.soft[a], Math.max(work.soft[b], work.soft[c])) : 0.0F;
        boolean sameColors = work.second[a] == work.second[b] && work.second[a] == work.second[c];
        float lowest = Math.min(work.value[a], Math.min(work.value[b], work.value[c]));
        float highest = Math.max(work.value[a], Math.max(work.value[b], work.value[c]));
        // Most triangles lie all on one side: no cut.
        if (soft <= 0.0F) {
            if (highest <= 0.0F || lowest > 0.0F) {
                whole(work, a, b, c, highest <= 0.0F ? BELOW : ABOVE);
                return;
            }
        } else if (highest + soft <= 0.0F || lowest - soft > 0.0F) {
            whole(work, a, b, c, highest + soft <= 0.0F ? BELOW : ABOVE);
            return;
        }
        if (sameColors && work.color[a] == work.color[b] && work.color[a] == work.color[c] && (work.color[a] & 0xFFFFFF) == (work.second[a] & 0xFFFFFF)) {
            // Both sides alike: nothing to cut.
            whole(work, a, b, c, BELOW);
            return;
        }
        // The triangle as a polygon of three points, each its corner's weights.
        float[][] weights = work.weights;
        long[] keys = work.pointKeys;
        for (int i = 0; i < 3; i++) {
            weights[0][i] = i == 0 ? 1.0F : 0.0F;
            weights[1][i] = i == 1 ? 1.0F : 0.0F;
            weights[2][i] = i == 2 ? 1.0F : 0.0F;
            keys[i] = corner(corners[i]);
        }
        if (!softBorders || soft <= 0.0F) {
            // Below zero, then above.
            emitPiece(work, 3, 0, -1, BELOW);
            emitPiece(work, 3, 0, 1, ABOVE);
        } else {
            // Below the lower cut (value + softness at or under zero), between the cuts, above the upper one (value - softness over zero).
            emitPiece(work, 3, 1, -1, BELOW);
            int band = clip(work, weights, keys, 3, 1, 1, work.cutWeights, work.cutKeys);
            if (band >= 3) {
                // Of the part over the lower cut, what is under the upper one; the polygon is copied back as it is cut again.
                copy(work.cutWeights, work.cutKeys, band, weights, keys);
                emitPiece(work, band, 2, -1, BAND);
                emitPiece(work, band, 2, 1, ABOVE);
            }
        }
    }

    private static void whole(Work work, int a, int b, int c, int side) {
        int first = emitCorner(work, a, side);
        int second = emitCorner(work, b, side);
        int third = emitCorner(work, c, side);
        work.indices = ensure(work.indices, work.indexCount + 3);
        work.indices[work.indexCount++] = first;
        work.indices[work.indexCount++] = second;
        work.indices[work.indexCount++] = third;
    }

    private static void copy(float[][] fromWeights, long[] fromKeys, int count, float[][] toWeights, long[] toKeys) {
        for (int k = 0; k < 3; k++) {
            System.arraycopy(fromWeights[k], 0, toWeights[k], 0, count);
        }
        System.arraycopy(fromKeys, 0, toKeys, 0, count);
    }

    /** Clips the polygon in {@link Work#weights} to one side of a cut and emits what is left, as a fan, on {@code side}. */
    private static void emitPiece(Work work, int count, int level, int sign, int side) {
        int kept = clip(work, work.weights, work.pointKeys, count, level, sign, work.cutWeights, work.cutKeys);
        if (kept < 3) {
            return;
        }
        int first = emitPoint(work, work.cutWeights, work.cutKeys, 0, side);
        int previous = emitPoint(work, work.cutWeights, work.cutKeys, 1, side);
        for (int i = 2; i < kept; i++) {
            int next = emitPoint(work, work.cutWeights, work.cutKeys, i, side);
            work.indices = ensure(work.indices, work.indexCount + 3);
            work.indices[work.indexCount++] = first;
            work.indices[work.indexCount++] = previous;
            work.indices[work.indexCount++] = next;
            previous = next;
        }
    }

    /**
     * The value of a cut at a point: level 0 the border itself, 1 the lower cut of a soft border (value plus softness), 2 the upper one
     * (value minus softness).
     */
    private static float cutValue(Work work, float[][] weights, int point, int level) {
        int[] corners = work.corners;
        float value = 0.0F;
        for (int k = 0; k < 3; k++) {
            int v = corners[k];
            float atCorner = level == 0 ? work.value[v] : level == 1 ? work.value[v] + work.soft[v] : work.value[v] - work.soft[v];
            value += weights[k][point] * atCorner;
        }
        return value;
    }

    /**
     * Sutherland-Hodgman: of the polygon, the part where the cut's value has the sign {@code sign} (-1: at or under zero, 1: over), into
     * {@code outWeights} and {@code outKeys}; returns its points.
     */
    private static int clip(Work work, float[][] weights, long[] keys, int count, int level, int sign, float[][] outWeights, long[] outKeys) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float vi = cutValue(work, weights, i, level);
            float vj = cutValue(work, weights, j, level);
            boolean inI = sign < 0 ? vi <= 0.0F : vi > 0.0F;
            boolean inJ = sign < 0 ? vj <= 0.0F : vj > 0.0F;
            if (inI) {
                for (int k = 0; k < 3; k++) {
                    outWeights[k][kept] = weights[k][i];
                }
                outKeys[kept++] = keys[i];
            }
            if (inI != inJ) {
                float t = vi / (vi - vj);
                for (int k = 0; k < 3; k++) {
                    outWeights[k][kept] = weights[k][i] + (weights[k][j] - weights[k][i]) * t;
                }
                outKeys[kept++] = cutKey(keys[i], keys[j], level);
            }
        }
        return kept;
    }

    /**
     * What a point of a polygon lies on, so the triangles beside make it once: a corner (a source vertex), or a cut (its level) across
     * the edge of the source mesh between two vertices, the lower first; -1 for a point inside a triangle, not shared, which only a
     * degenerate softness makes. The level of a corner is {@link #CORNER}.
     */
    private static long corner(int vertex) {
        return (long) CORNER << 34 | (long) vertex << 16 | vertex;
    }

    /** What the point where a cut crosses the polygon's side from {@code from} to {@code to} lies on, see {@link #corner}. */
    private static long cutKey(long from, long to, int level) {
        if (from < 0 || to < 0) {
            return -1L;
        }
        int fromLow = (int) (from >>> 16 & 0xFFFF);
        int fromHigh = (int) (from & 0xFFFF);
        int toLow = (int) (to >>> 16 & 0xFFFF);
        int toHigh = (int) (to & 0xFFFF);
        boolean fromCorner = (from >>> 34 & 3) == CORNER;
        boolean toCorner = (to >>> 34 & 3) == CORNER;
        int low;
        int high;
        if (fromCorner && toCorner) {
            low = Math.min(fromLow, toLow);
            high = Math.max(fromLow, toLow);
        } else if (fromCorner) {
            if (fromLow != toLow && fromLow != toHigh) {
                return -1L;
            }
            low = toLow;
            high = toHigh;
        } else if (toCorner) {
            if (toLow != fromLow && toLow != fromHigh) {
                return -1L;
            }
            low = fromLow;
            high = fromHigh;
        } else if (fromLow == toLow && fromHigh == toHigh) {
            low = fromLow;
            high = fromHigh;
        } else {
            return -1L;
        }
        return (long) level << 34 | (long) low << 16 | high;
    }

    /** The output vertex of a point of a polygon, made once per place and side, see {@link #corner}. */
    private static int emitPoint(Work work, float[][] weights, long[] keys, int point, int side) {
        long place = keys[point];
        int[] corners = work.corners;
        if (place < 0) {
            return put(work, weights[0][point], weights[1][point], weights[2][point], corners[0], corners[1], corners[2], side);
        }
        int level = (int) (place >>> 34 & 3);
        if (level == CORNER) {
            return emitCorner(work, (int) (place & 0xFFFF), side);
        }
        // Points of a soft border's cuts are the same on both sides (the blend starts and ends there); those of a sharp one are not.
        long key = 1L << 40 | (long) (work.face + 1) << 44 | (level == 0 ? place | (long) side << 36 : place);
        int slot = find(work, key);
        if (work.stamps[slot] == work.stamp) {
            return work.ids[slot];
        }
        // Along the source edge from its lower vertex to its higher one, so both triangles make the same point.
        int low = (int) ((place >>> 16) & 0xFFFF);
        int high = (int) (place & 0xFFFF);
        float wLow = 0.0F;
        float wHigh = 0.0F;
        for (int k = 0; k < 3; k++) {
            if (corners[k] == low) {
                wLow = weights[k][point];
            } else if (corners[k] == high) {
                wHigh = weights[k][point];
            }
        }
        float t = wHigh / (wLow + wHigh);
        int id = put(work, 1.0F - t, t, 0.0F, low, high, low, side);
        work.keys[slot] = key;
        work.ids[slot] = id;
        work.stamps[slot] = work.stamp;
        return id;
    }

    /** A source vertex on one side of the border, made once per side. */
    private static int emitCorner(Work work, int vertex, int side) {
        long key = 2L << 40 | (long) (work.face + 1) << 44 | (long) side << 16 | vertex;
        int slot = find(work, key);
        if (work.stamps[slot] == work.stamp) {
            return work.ids[slot];
        }
        int id = put(work, 1.0F, 0.0F, 0.0F, vertex, vertex, vertex, side);
        work.keys[slot] = key;
        work.ids[slot] = id;
        work.stamps[slot] = work.stamp;
        return id;
    }

    private static int find(Work work, long key) {
        int mask = work.keys.length - 1;
        int slot = slot(key, mask);
        while (work.stamps[slot] == work.stamp && work.keys[slot] != key) {
            slot = slot + 1 & mask;
        }
        if (work.stamps[slot] != work.stamp && work.vertexCount * 2 > mask) {
            growTable(work);
            return find(work, key);
        }
        return slot;
    }

    private static int slot(long key, int mask) {
        return (int) (key * 0x9E3779B97F4A7C15L >>> 32) & mask;
    }

    private static void growTable(Work work) {
        long[] keys = work.keys;
        int[] ids = work.ids;
        int[] stamps = work.stamps;
        int size = keys.length * 2;
        work.keys = new long[size];
        work.ids = new int[size];
        work.stamps = new int[size];
        int mask = size - 1;
        for (int i = 0; i < keys.length; i++) {
            if (stamps[i] == work.stamp) {
                int slot = slot(keys[i], mask);
                while (work.stamps[slot] == work.stamp) {
                    slot = slot + 1 & mask;
                }
                work.keys[slot] = keys[i];
                work.ids[slot] = ids[i];
                work.stamps[slot] = work.stamp;
            }
        }
    }

    /** Writes a vertex at the weighted sum of three source vertices, colored for its side; returns its index. */
    private static int put(Work work, float wa, float wb, float wc, int a, int b, int c, int side) {
        float x = wa * work.x[a] + wb * work.x[b] + wc * work.x[c];
        float y = wa * work.y[a] + wb * work.y[b] + wc * work.y[c];
        float z = wa * work.z[a] + wb * work.z[b] + wc * work.z[c];
        float depth = wa * work.depth[a] + wb * work.depth[b] + wc * work.depth[c];
        float value = wa * work.value[a] + wb * work.value[b] + wc * work.value[c];
        float soft = wa * work.soft[a] + wb * work.soft[b] + wc * work.soft[c];
        int color = mix3(work.color, a, b, c, wa, wb, wc);
        int second = mix3(work.second, a, b, c, wa, wb, wc);
        int bits = work.bits[a];
        boolean water = (bits & LodVertex.BIT_WATER) != 0;
        int shown;
        if (side == BELOW) {
            shown = color;
        } else if (side == ABOVE) {
            shown = second;
        } else {
            float t = soft > 0.0F ? Math.clamp((value + soft) / (2.0F * soft), 0.0F, 1.0F) : value > 0.0F ? 1.0F : 0.0F;
            shown = mix(color, second, t);
        }
        // The ground is opaque (its alpha is the softness); the water keeps its opacity.
        int alpha = water ? color >>> 24 : 255;
        int material = bits & LodVertex.MATERIAL_MASK;
        if (!water && side != BELOW && (second >>> 16 & 255) > SNOW_BRIGHTNESS && (second >>> 8 & 255) > SNOW_BRIGHTNESS
            && (second & 255) > SNOW_BRIGHTNESS) {
            material = LodVertex.MATERIAL_SNOW;
        }
        if (water) {
            material = LodVertex.MATERIAL_WATER;
        }
        // Sky light falls by one a block of water, as vanilla's; the land and the water itself lie in the open.
        int sky = water || depth <= 0.0F ? 15 : Math.clamp(15 - (int) Math.ceil(depth), 0, 15);
        int block = material == LodVertex.MATERIAL_LAVA ? 15 : 0;
        float nx = wa * work.nx[a] + wb * work.nx[b] + wc * work.nx[c];
        float ny = wa * work.ny[a] + wb * work.ny[b] + wc * work.ny[c];
        float nz = wa * work.nz[a] + wb * work.nz[b] + wc * work.nz[c];
        int face = work.face >= 0 ? work.face : (bits & LodVertex.BIT_UNDERSIDE) != 0 ? DOWN : face(nx, ny, nz);

        work.vertices = ensure(work.vertices, SIZE);
        ByteBuffer out = work.vertices;
        out.putShort((short) clamp(Math.round(x), 0, 0xFFFF));
        out.putShort((short) clamp(Math.round(y), 0, 0xFFFF));
        out.putShort((short) clamp(Math.round(z), 0, 0xFFFF));
        out.putShort((short) (sky | block << 4));
        out.put((byte) (shown >>> 16)).put((byte) (shown >>> 8)).put((byte) shown).put((byte) alpha);
        out.put((byte) material).put((byte) face).put((byte) 0).put((byte) 0);
        return work.vertexCount++;
    }

    /**
     * The face of a skirt's triangle (one with the bottom of a skirt among its corners), from its own slant: a skirt hangs straight
     * down, and with the normal of the ground it hangs from the pack would light it as ground facing the sky. -1 for others, whose
     * vertices take the normals of the ground.
     */
    private static int skirtFace(Work work, int a, int b, int c) {
        if (((work.bits[a] | work.bits[b] | work.bits[c]) & LodVertex.BIT_SKIRT) == 0) {
            return -1;
        }
        float ux = work.x[b] - work.x[a];
        float uy = work.y[b] - work.y[a];
        float uz = work.z[b] - work.z[a];
        float vx = work.x[c] - work.x[a];
        float vy = work.y[c] - work.y[a];
        float vz = work.z[c] - work.z[a];
        // The front faces turn counterclockwise, as OpenGL's.
        return face(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
    }

    /** The direction in DH's table nearest to a normal. */
    static int face(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ay >= ax && ay >= az) {
            return y >= 0.0F ? UP : DOWN;
        }
        if (az >= ax) {
            return z < 0.0F ? NORTH : SOUTH;
        }
        return x < 0.0F ? WEST : EAST;
    }

    private static int mix3(int[] colors, int a, int b, int c, float wa, float wb, float wc) {
        int ca = colors[a];
        int cb = colors[b];
        int cc = colors[c];
        int alpha = clamp(Math.round((ca >>> 24) * wa + (cb >>> 24) * wb + (cc >>> 24) * wc), 0, 255);
        int red = clamp(Math.round((ca >>> 16 & 255) * wa + (cb >>> 16 & 255) * wb + (cc >>> 16 & 255) * wc), 0, 255);
        int green = clamp(Math.round((ca >>> 8 & 255) * wa + (cb >>> 8 & 255) * wb + (cc >>> 8 & 255) * wc), 0, 255);
        int blue = clamp(Math.round((ca & 255) * wa + (cb & 255) * wb + (cc & 255) * wc), 0, 255);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int mix(int from, int to, float t) {
        int red = Math.round((from >>> 16 & 255) + ((to >>> 16 & 255) - (from >>> 16 & 255)) * t);
        int green = Math.round((from >>> 8 & 255) + ((to >>> 8 & 255) - (from >>> 8 & 255)) * t);
        int blue = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return from & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static ByteBuffer ensure(ByteBuffer buffer, int more) {
        if (buffer.remaining() >= more) {
            return buffer;
        }
        ByteBuffer grown = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.position() + more)).order(ByteOrder.nativeOrder());
        buffer.flip();
        grown.put(buffer);
        return grown;
    }

    private static int[] ensure(int[] array, int size) {
        return array.length >= size ? array : Arrays.copyOf(array, Math.max(size, array.length * 2));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }
}
