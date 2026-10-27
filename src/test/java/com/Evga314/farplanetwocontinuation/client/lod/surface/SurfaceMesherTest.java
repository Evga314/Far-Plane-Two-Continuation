package com.Evga314.farplanetwocontinuation.client.lod.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SurfaceMesherTest {
    private static final int SIDE = SurfaceFields.SIDE;
    private static final int CELLS = SurfaceFields.CELLS;
    private static final float SEA = 62.85F;
    private static final float UNIT = LodVertex.XZ_UNITS;

    /** A tile of land and water: water where {@code wet} says, at the sea level, as deep as {@code depth} says. */
    private static SurfaceFields tile(int spacing, boolean[] wet, float[] depth) {
        SurfaceFields f = new SurfaceFields();
        f.spacing = spacing;
        f.floating = false;
        f.evenPositions();
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                int s = SurfaceFields.at(x, z);
                f.water[s] = wet[s];
                f.ground[s] = wet[s] ? SEA - depth[s] : 70.0F;
                f.color[s] = 0xFF40A040;
                f.beyond[s] = 0xFF40A040;
                f.split[s] = -1.0F;
                f.waterColor[s] = 0xFF2040C0;
                f.waterBeyond[s] = 0xFF2040C0;
                f.waterSplit[s] = -1.0F;
                f.material[s] = (byte) LodVertex.MATERIAL_GRASS;
            }
        }
        // Land next to water knows its level, as the shaping leaves it.
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                boolean near = false;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        near |= nx >= 0 && nz >= 0 && nx < SIDE && nz < SIDE && wet[SurfaceFields.at(nx, nz)];
                    }
                }
                f.waterLevel[SurfaceFields.at(x, z)] = near ? SEA : Float.NaN;
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < SIDE; i++) {
                f.skirtDepth[SurfaceIndices.edgeSample(edge, i)] = 4.0F;
            }
        }
        return f;
    }

    private static boolean[] all(boolean value) {
        boolean[] array = new boolean[SurfaceFields.SAMPLES];
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static float[] depths(float value) {
        float[] array = new float[SurfaceFields.SAMPLES];
        java.util.Arrays.fill(array, value);
        return array;
    }

    /** Islands of land in water, the same every run. */
    private static boolean[] coast(long seed) {
        Random random = new Random(seed);
        boolean[] wet = all(true);
        for (int island = 0; island < 6; island++) {
            int cx = random.nextInt(SIDE);
            int cz = random.nextInt(SIDE);
            int r = 2 + random.nextInt(6);
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= r * r) {
                        wet[SurfaceFields.at(x, z)] = false;
                    }
                }
            }
        }
        return wet;
    }

    private static int[] triangles(SurfaceMesh mesh, boolean water) {
        ByteBuffer indices = water || !mesh.sharedGround() ? mesh.indices() : SurfaceIndices.ground(mesh.groups()).indices();
        int[] offsets = water ? mesh.waterOffsets() : mesh.groundOffsets();
        int from = offsets[0];
        int to = offsets[offsets.length - 1];
        int[] result = new int[to - from];
        for (int i = from; i < to; i++) {
            result[i - from] = indices.getShort(i * 2) & 0xFFFF;
        }
        return result;
    }

    private static float x(SurfaceMesh mesh, int v) {
        return (mesh.vertices().getShort(v * LodVertex.SIZE + LodVertex.X) & 0xFFFF) / UNIT;
    }

    private static float z(SurfaceMesh mesh, int v) {
        return (mesh.vertices().getShort(v * LodVertex.SIZE + LodVertex.Z) & 0xFFFF) / UNIT;
    }

    private static int y(SurfaceMesh mesh, int v) {
        return mesh.vertices().getShort(v * LodVertex.SIZE + LodVertex.Y) & 0xFFFF;
    }

    /** Area seen from above, in cells, and that every triangle faces up. */
    private static double area(SurfaceMesh mesh, int[] triangles) {
        double sum = 0.0;
        for (int i = 0; i < triangles.length; i += 3) {
            int a = triangles[i];
            int b = triangles[i + 1];
            int c = triangles[i + 2];
            // Facing up: from a, b then c turn the way +z then +x does, see SurfaceIndices.
            double cross = (z(mesh, b) - z(mesh, a)) * (x(mesh, c) - x(mesh, a)) - (x(mesh, b) - x(mesh, a)) * (z(mesh, c) - z(mesh, a));
            assertTrue(cross >= -1.0E-9, "triangle " + i / 3 + " faces down");
            sum += cross / 2.0;
        }
        return sum;
    }

    /** No vertex lies inside an edge of another triangle without being one of its corners: those leave cracks. */
    private static void noTJunctions(SurfaceMesh mesh, int[] triangles) {
        Map<Long, Integer> points = new HashMap<>();
        for (int v : triangles) {
            points.put(key(mesh, v), v);
        }
        for (int i = 0; i < triangles.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = triangles[i + k];
                int b = triangles[i + (k + 1) % 3];
                for (int p : points.values()) {
                    if (key(mesh, p) == key(mesh, a) || key(mesh, p) == key(mesh, b)) {
                        continue;
                    }
                    double ex = x(mesh, b) - x(mesh, a);
                    double ez = z(mesh, b) - z(mesh, a);
                    double px = x(mesh, p) - x(mesh, a);
                    double pz = z(mesh, p) - z(mesh, a);
                    double along = (px * ex + pz * ez) / (ex * ex + ez * ez);
                    double off = Math.abs(px * ez - pz * ex);
                    assertTrue(!(off < 1.0E-6 && along > 1.0E-6 && along < 1 - 1.0E-6 && y(mesh, p) == y(mesh, a) && y(mesh, p) == y(mesh, b)),
                        "vertex at " + x(mesh, p) + " " + z(mesh, p) + " lies inside an edge");
                }
            }
        }
    }

    private static long key(SurfaceMesh mesh, int v) {
        ByteBuffer vertices = mesh.vertices();
        int at = v * LodVertex.SIZE;
        return (vertices.getShort(at) & 0xFFFFL) | (vertices.getShort(at + 2) & 0xFFFFL) << 16 | (vertices.getShort(at + 4) & 0xFFFFL) << 32;
    }

    @Test
    void openSeaIsJoinedIntoFewTriangles() {
        SurfaceMesh mesh = SurfaceMesher.build(tile(8, all(true), depths(30.0F)));
        int[] water = triangles(mesh, true);
        assertEquals(CELLS * CELLS, area(mesh, water), 1.0E-6);
        // One piece of two triangles per chunk column: 16 by 16 of them at 8 blocks a cell.
        assertEquals(16 * 16 * 2, water.length / 3);
        // And one for a tile in a single chunk column, however large.
        assertEquals(2, triangles(SurfaceMesher.build(tile(64, all(true), depths(30.0F))), true).length / 3);
        noTJunctions(mesh, water);
    }

    @Test
    void waterCoversEveryTriangleWithWater() {
        for (long seed = 1; seed <= 5; seed++) {
            boolean[] wet = coast(seed);
            Random random = new Random(seed);
            float[] depth = new float[SurfaceFields.SAMPLES];
            for (int i = 0; i < depth.length; i++) {
                // Some deep enough to join, some shallow and each other.
                depth[i] = random.nextInt(3) == 0 ? 1.0F + random.nextInt(10) : 40.0F;
            }
            SurfaceFields fields = tile(2, wet, depth);
            SurfaceMesh mesh = SurfaceMesher.build(fields);
            int expected = 0;
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int a = SurfaceFields.at(x, z);
                    int b = a + 1;
                    int c = a + SIDE;
                    int d = c + 1;
                    boolean even = ((x + z) & 1) == 0;
                    expected += even ? (wet[a] || wet[c] || wet[b] ? 1 : 0) + (wet[b] || wet[c] || wet[d] ? 1 : 0)
                        : (wet[a] || wet[c] || wet[d] ? 1 : 0) + (wet[a] || wet[d] || wet[b] ? 1 : 0);
                }
            }
            int[] water = triangles(mesh, true);
            assertEquals(expected * 0.5, area(mesh, water), 1.0E-6, "seed " + seed);
            noTJunctions(mesh, water);
        }
    }

    @Test
    void groundCoversTheTileOnceAndEachChunkHasItsOwnRange() {
        for (int spacing : new int[] {1, 2, 4, 8, 16, 64}) {
            SurfaceMesh mesh = SurfaceMesher.build(tile(spacing, coast(spacing), depths(5.0F)));
            int groups = mesh.groups();
            assertEquals(SurfaceIndices.groups(spacing), groups);
            int[] offsets = mesh.groundOffsets();
            ByteBuffer indices = SurfaceIndices.ground(groups).indices();
            int cellsPerGroup = CELLS / groups;
            double surface = 0.0;
            for (int g = 0; g < groups * groups; g++) {
                int[] own = new int[offsets[g + 1] - offsets[g]];
                for (int i = 0; i < own.length; i++) {
                    own[i] = indices.getShort((offsets[g] + i) * 2) & 0xFFFF;
                }
                float x0 = g % groups * cellsPerGroup;
                float z0 = g / groups * cellsPerGroup;
                for (int v : own) {
                    assertTrue(x(mesh, v) >= x0 && x(mesh, v) <= x0 + cellsPerGroup && z(mesh, v) >= z0 && z(mesh, v) <= z0 + cellsPerGroup,
                        "a triangle of chunk column " + g + " reaches out of it");
                }
                for (int i = 0; i < own.length; i += 3) {
                    boolean skirt = false;
                    for (int k = 0; k < 3; k++) {
                        skirt |= (mesh.vertices().get(own[i + k] * LodVertex.SIZE + LodVertex.BITS) & LodVertex.BIT_SKIRT) != 0;
                    }
                    if (!skirt) {
                        surface += area(mesh, new int[] {own[i], own[i + 1], own[i + 2]});
                    }
                }
            }
            assertEquals(CELLS * CELLS, surface, 1.0E-6, "spacing " + spacing);
        }
    }

    @Test
    void islandTopAndUndersideMeetAtTheEdge() {
        SurfaceFields f = tile(4, all(false), depths(0.0F));
        f.floating = true;
        java.util.Arrays.fill(f.waterLevel, Float.NaN);
        java.util.Arrays.fill(f.water, false);
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                // A round island of radius 10 cells; the distance in cells to its edge, over it above zero.
                f.edgeDistance[SurfaceFields.at(x, z)] = 10.0F - (float) Math.hypot(x - 15.3, z - 16.7);
            }
        }
        SurfaceMesh mesh = SurfaceMesher.build(f);
        int[] all = triangles(mesh, false);
        // Every edge of the closed body has a triangle on both sides: the top and the underside share the rim.
        Map<Long, Integer> edges = new HashMap<>();
        for (int i = 0; i < all.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                long a = key(mesh, all[i + k]);
                long b = key(mesh, all[i + (k + 1) % 3]);
                edges.merge(a * 31 + b, 1, Integer::sum);
            }
        }
        int open = 0;
        for (int i = 0; i < all.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                long a = key(mesh, all[i + k]);
                long b = key(mesh, all[i + (k + 1) % 3]);
                if (!edges.containsKey(b * 31 + a)) {
                    open++;
                }
            }
        }
        assertEquals(0, open, "edges of the island open");
        // Its top is about the size of the circle.
        int[] top = new int[all.length];
        int count = 0;
        for (int i = 0; i < all.length; i += 3) {
            if ((mesh.vertices().get(all[i] * LodVertex.SIZE + LodVertex.BITS) & LodVertex.BIT_UNDERSIDE) == 0) {
                System.arraycopy(all, i, top, count, 3);
                count += 3;
            }
        }
        double area = area(mesh, java.util.Arrays.copyOf(top, count));
        assertEquals(Math.PI * 100.0, area, 6.0);
    }
}
