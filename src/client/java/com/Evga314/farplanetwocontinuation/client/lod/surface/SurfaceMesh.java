package com.Evga314.farplanetwocontinuation.client.lod.surface;

import java.nio.ByteBuffer;

/**
 * A rough tile's mesh for the new renderer, see {@link SurfaceMesher}.
 *
 * @param vertices     {@link LodVertex}es: the ground first, then the water
 * @param baseY        the height the vertices' heights count from
 * @param groups       chunk columns along one edge of the tile, see {@link SurfaceIndices#groups}
 * @param sharedGround whether the ground's triangles are those every tile of its kind has ({@link SurfaceIndices#ground}); else they
 *                     come first in {@link #indices}
 * @param indices      the tile's own triangles, unsigned shorts
 * @param groundOffsets the first index of every chunk column's ground (row by row from the north-west), and after the last its end
 * @param waterOffsets the same for the water, in {@link #indices}
 */
public record SurfaceMesh(
    ByteBuffer vertices, int vertexCount, float baseY, int groups, boolean sharedGround, ByteBuffer indices, int[] groundOffsets,
    int[] waterOffsets
) {
    /** Indices drawn for the whole tile. */
    public int indexCount() {
        return this.groundOffsets[this.groundOffsets.length - 1] - this.groundOffsets[0] + this.waterOffsets[this.waterOffsets.length - 1]
            - this.waterOffsets[0];
    }

    /** The vertices and then the tile's own indices in one buffer, as they go to the GPU. */
    public ByteBuffer combined() {
        ByteBuffer all = ByteBuffer.allocateDirect(this.vertices.remaining() + this.indices.remaining()).order(java.nio.ByteOrder.nativeOrder());
        all.put(this.vertices.duplicate()).put(this.indices.duplicate()).flip();
        return all;
    }

    public SurfaceTile tile() {
        return new SurfaceTile(this.groups, this.sharedGround, this.groundOffsets, this.waterOffsets, this.vertices.remaining(), this.baseY);
    }

    /** Bytes the tile takes on the GPU itself, without the shared triangles. */
    public int bytes() {
        return this.vertices.capacity() + this.indices.capacity();
    }
}
