package com.Evga314.farplanetwocontinuation.client.lod.surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The triangles of the ground every rough tile on land has: the grid of its samples and the skirts along its edges, the same for
 * all of them. They are ordered by chunk column, so a chunk column vanilla or the detailed tiles draw is left out by drawing the
 * index ranges around it. One list per number of chunk columns, made once.
 * <p>
 * The vertices they point at: the samples row by row from the north-west, then the bottoms of the skirts, edge by edge (north,
 * south, west, east), each from its first sample to its last.
 */
public final class SurfaceIndices {
    private static final int SIDE = SurfaceFields.SIDE;
    private static final int CELLS = SurfaceFields.CELLS;
    /** A chunk column is 16 blocks wide. */
    private static final int CHUNK = 16;
    /** One list for each of 1, 2, 4, 8 and 16 chunk columns along an edge. */
    private static final Layout[] LAYOUTS = new Layout[5];

    /** @param offsets the first index of every chunk column, row by row, and after the last the end */
    public record Layout(ByteBuffer indices, int[] offsets) {
    }

    private SurfaceIndices() {
    }

    /** Chunk columns along a tile's edge: a tile of finer cells than a chunk spans several, the others lie in one or are wider. */
    public static int groups(int spacing) {
        return spacing < CHUNK ? CELLS * spacing / CHUNK : 1;
    }

    /** The bottom of the skirt under sample {@code i} of edge {@code edge}, see {@link SurfaceIndices}. */
    public static int skirt(int edge, int i) {
        return SurfaceFields.SAMPLES + edge * SIDE + i;
    }

    /** The sample at step {@code i} along edge {@code edge}. */
    public static int edgeSample(int edge, int i) {
        return switch (edge) {
            case 0 -> SurfaceFields.at(i, 0);
            case 1 -> SurfaceFields.at(i, CELLS);
            case 2 -> SurfaceFields.at(0, i);
            default -> SurfaceFields.at(CELLS, i);
        };
    }

    /** The list for tiles of this many chunk columns along an edge; read only, shared. */
    public static synchronized Layout ground(int groups) {
        int slot = Integer.numberOfTrailingZeros(groups);
        Layout layout = LAYOUTS[slot];
        if (layout == null) {
            layout = build(groups);
            LAYOUTS[slot] = layout;
        }
        return new Layout(layout.indices.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), layout.offsets);
    }

    private static Layout build(int groups) {
        int cellsPerGroup = CELLS / groups;
        ByteBuffer buffer = ByteBuffer.allocateDirect((CELLS * CELLS * 6 + 4 * CELLS * 6) * 2).order(ByteOrder.nativeOrder());
        int[] offsets = new int[groups * groups + 1];
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                offsets[gx + gz * groups] = buffer.position() / 2;
                int x0 = gx * cellsPerGroup;
                int z0 = gz * cellsPerGroup;
                for (int z = z0; z < z0 + cellsPerGroup; z++) {
                    for (int x = x0; x < x0 + cellsPerGroup; x++) {
                        int v00 = SurfaceFields.at(x, z);
                        // The diagonal alternates like a chessboard, so borders drawn across the cells look the same in every
                        // direction instead of being straight one way and saw-toothed the other.
                        if (((x + z) & 1) == 0) {
                            quad(buffer, v00, v00 + 1, v00 + SIDE, v00 + SIDE + 1);
                        } else {
                            quadOtherDiagonal(buffer, v00, v00 + 1, v00 + SIDE, v00 + SIDE + 1);
                        }
                    }
                }
                // The skirts along the tile's edges hang from the cells of this group there.
                for (int edge = 0; edge < 4; edge++) {
                    boolean along = edge < 2;
                    int across = edge == 0 || edge == 2 ? 0 : groups - 1;
                    if ((along ? gz : gx) != across) {
                        continue;
                    }
                    int first = (along ? gx : gz) * cellsPerGroup;
                    for (int i = first; i < first + cellsPerGroup; i++) {
                        int top0 = edgeSample(edge, i);
                        int top1 = edgeSample(edge, i + 1);
                        // Along the edges at z = 0 and x = CELLS the samples run the other way round the tile.
                        if (edge == 0 || edge == 3) {
                            quad(buffer, top1, top0, skirt(edge, i + 1), skirt(edge, i));
                        } else {
                            quad(buffer, top0, top1, skirt(edge, i), skirt(edge, i + 1));
                        }
                    }
                }
            }
        }
        offsets[groups * groups] = buffer.position() / 2;
        buffer.flip();
        return new Layout(buffer, offsets);
    }

    /** Two triangles facing up (or out, for skirts): a to d is the diagonal not taken, b and c lie on it. */
    private static void quad(ByteBuffer buffer, int a, int b, int c, int d) {
        buffer.putShort((short) a).putShort((short) c).putShort((short) b);
        buffer.putShort((short) b).putShort((short) c).putShort((short) d);
    }

    /** Like {@link #quad}, split along the other diagonal, from a to d. */
    private static void quadOtherDiagonal(ByteBuffer buffer, int a, int b, int c, int d) {
        buffer.putShort((short) a).putShort((short) c).putShort((short) d);
        buffer.putShort((short) a).putShort((short) d).putShort((short) b);
    }
}
