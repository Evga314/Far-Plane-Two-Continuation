package com.Evga314.farplanetwocontinuation.client.lod.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PackMesherTest {
    private static final int SIDE = SurfaceFields.SIDE;
    private static final int CELLS = SurfaceFields.CELLS;
    private static final int GRASS = 0xFF40A040;
    private static final int SNOW = 0xFFF0F0F0;
    private static final float GROUND = 70.0F;

    /** Hilly land with a snow line running slantwise over it, wavy, each sample a softness of its own. */
    private static SurfaceFields tile(int spacing, long seed) {
        Random random = new Random(seed);
        SurfaceFields f = new SurfaceFields();
        f.spacing = spacing;
        f.floating = false;
        f.evenPositions();
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                int s = SurfaceFields.at(x, z);
                f.water[s] = false;
                f.ground[s] = GROUND + random.nextFloat() * 6.0F;
                f.waterLevel[s] = Float.NaN;
                f.color[s] = GRASS;
                f.beyond[s] = SNOW;
                f.split[s] = Math.clamp((x + 0.6F * z - 18.3F + (float) Math.sin(z * 0.7) * 3.0F) / 6.0F, -1.0F, 1.0F);
                f.softness[s] = (byte) random.nextInt(4);
                f.waterColor[s] = 0xFF2040C0;
                f.waterBeyond[s] = 0xFF2040C0;
                f.waterSplit[s] = -1.0F;
                f.material[s] = (byte) LodVertex.MATERIAL_GRASS;
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < SIDE; i++) {
                f.skirtDepth[SurfaceIndices.edgeSample(edge, i)] = 4.0F;
            }
        }
        return f;
    }

    private static SurfaceMesh pack(SurfaceFields fields, boolean soft) {
        SurfaceMesh mesh = SurfaceMesher.build(fields);
        ByteBuffer ground = SurfaceIndices.ground(mesh.groups()).indices();
        SurfaceMesh pack = PackMesher.build(mesh, ground, fields.spacing, soft);
        assertNotNull(pack);
        return pack;
    }

    private static int index(SurfaceMesh mesh, int i) {
        return mesh.indices().getShort(i * 2) & 0xFFFF;
    }

    private static long position(SurfaceMesh mesh, int vertex) {
        ByteBuffer v = mesh.vertices();
        int at = vertex * PackMesher.SIZE;
        return (long) (v.getShort(at) & 0xFFFF) << 32 | (long) (v.getShort(at + 2) & 0xFFFF) << 16 | v.getShort(at + 4) & 0xFFFF;
    }

    private static String text(long p) {
        return "(" + (p >>> 32) + "," + (p >>> 16 & 0xFFFF) + "," + (p & 0xFFFF) + ")";
    }

    private static String v(SurfaceMesh mesh, int i) {
        return text(position(mesh, index(mesh, i)));
    }

    private static boolean onOneEdge(long a, long b, int last) {
        long ax = a >>> 32;
        long az = a & 0xFFFF;
        long bx = b >>> 32;
        long bz = b & 0xFFFF;
        return ax == bx && (ax == 0 || ax == last) || az == bz && (az == 0 || az == last);
    }

    private static int color(SurfaceMesh mesh, int vertex) {
        ByteBuffer v = mesh.vertices();
        int at = vertex * PackMesher.SIZE + PackMesher.COLOR;
        return 0xFF000000 | (v.get(at) & 0xFF) << 16 | (v.get(at + 1) & 0xFF) << 8 | v.get(at + 2) & 0xFF;
    }

    /**
     * Every side of a ground triangle is a side of another one the other way round, but along the tile's edges, where the skirts hang
     * (both ends on one edge of the tile).
     */
    private static void assertNoCracks(SurfaceMesh mesh, int spacing) {
        int last = CELLS * spacing;
        Map<Long, Integer> sides = new HashMap<>();
        int end = mesh.groundOffsets()[mesh.groundOffsets().length - 1];
        for (int i = 0; i < end; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = index(mesh, i + k);
                int b = index(mesh, i + (k + 1) % 3);
                long pa = position(mesh, a);
                long pb = position(mesh, b);
                if (pa == pb || onOneEdge(pa, pb, last)) {
                    continue;
                }
                sides.merge(pa * 31 + pb * 1_000_003L, 1, Integer::sum);
                sides.merge(pb * 31 + pa * 1_000_003L, -1, Integer::sum);
            }
        }
        String first = sides.entrySet().stream().filter(e -> e.getValue() != 0).findFirst().map(Object::toString).orElse("");
        java.util.List<String> open = new java.util.ArrayList<>();
        for (int i = 0; i < end && open.size() < 6; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = index(mesh, i + k);
                int b = index(mesh, i + (k + 1) % 3);
                long pa = position(mesh, a);
                long pb = position(mesh, b);
                if (pa != pb && !onOneEdge(pa, pb, last) && sides.getOrDefault(pa * 31 + pb * 1_000_003L, 0) != 0) {
                    open.add("tri " + i / 3 + " " + text(pa) + "->" + text(pb) + " v" + a + "," + v(mesh, i) + "," + v(mesh, i + 1) + "," + v(mesh, i + 2));
                }
            }
        }
        assertEquals(0, sides.values().stream().filter(count -> count != 0).count(), "sides with no neighbour " + open);
    }

    /** The ground seen from above covers the tile exactly: the pieces of every cut triangle add up to it. */
    private static void assertCovers(SurfaceMesh mesh, int spacing) {
        long twiceArea = 0;
        int end = mesh.groundOffsets()[mesh.groundOffsets().length - 1];
        ByteBuffer v = mesh.vertices();
        for (int i = 0; i < end; i += 3) {
            int a = index(mesh, i) * PackMesher.SIZE;
            int b = index(mesh, i + 1) * PackMesher.SIZE;
            int c = index(mesh, i + 2) * PackMesher.SIZE;
            long ax = v.getShort(a) & 0xFFFF;
            long az = v.getShort(a + 4) & 0xFFFF;
            long bx = v.getShort(b) & 0xFFFF;
            long bz = v.getShort(b + 4) & 0xFFFF;
            long cx = v.getShort(c) & 0xFFFF;
            long cz = v.getShort(c + 4) & 0xFFFF;
            twiceArea += (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
        }
        long side = (long) CELLS * spacing;
        assertEquals(2 * side * side, Math.abs(twiceArea));
    }

    @Test
    void sharpCutHasNoCracksAndCovers() {
        for (int spacing : new int[] {1, 4, 32}) {
            SurfaceMesh mesh = pack(tile(spacing, spacing), false);
            assertNoCracks(mesh, spacing);
            assertCovers(mesh, spacing);
        }
    }

    @Test
    void softCutHasNoCracksAndCovers() {
        for (int spacing : new int[] {1, 4, 32}) {
            SurfaceMesh mesh = pack(tile(spacing, spacing + 100), true);
            assertNoCracks(mesh, spacing);
            assertCovers(mesh, spacing);
        }
    }

    @Test
    void sharpCutKeepsTheTwoColors() {
        SurfaceMesh mesh = pack(tile(8, 7), false);
        boolean grass = false;
        boolean snow = false;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int color = color(mesh, v);
            assertTrue(color == GRASS || color == SNOW, () -> Integer.toHexString(color));
            grass |= color == GRASS;
            snow |= color == SNOW;
        }
        assertTrue(grass && snow);
        // More vertices than the source: the cuts.
        assertTrue(mesh.vertexCount() > SIDE * SIDE + 4 * SIDE);
    }

    @Test
    void softCutBlendsBetween() {
        SurfaceMesh mesh = pack(tile(8, 9), true);
        boolean between = false;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int color = color(mesh, v);
            between |= color != GRASS && color != SNOW;
        }
        assertTrue(between);
    }

    @Test
    void groupsKeepTheirOrder() {
        SurfaceMesh mesh = pack(tile(1, 3), false);
        int[] offsets = mesh.groundOffsets();
        assertEquals(SurfaceIndices.groups(1) * SurfaceIndices.groups(1) + 1, offsets.length);
        for (int g = 0; g + 1 < offsets.length; g++) {
            assertTrue(offsets[g + 1] > offsets[g]);
        }
        assertTrue(mesh.pack() && !mesh.sharedGround());
    }

    @Test
    void faces() {
        assertEquals(1, PackMesher.face(0.1F, 0.9F, 0.0F));
        assertEquals(5, PackMesher.face(0.8F, 0.3F, 0.0F));
        assertEquals(2, PackMesher.face(0.0F, 0.2F, -0.9F));
        assertEquals(0, PackMesher.face(0.0F, -1.0F, 0.0F));
    }
}
