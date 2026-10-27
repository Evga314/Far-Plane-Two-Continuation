package com.Evga314.farplanetwocontinuation.client.lod.surface;

/**
 * What drawing needs to know of a rough tile's {@link SurfaceMesh} once it is on the GPU, without the mesh's data: the vertices and
 * the tile's own indices lie one after the other in the tile's GPU memory, see {@link SurfaceMesh#combined}.
 *
 * @param vertexBytes  where the tile's own indices start in its GPU memory
 * @param groundOffsets see {@link SurfaceMesh#groundOffsets}; into the shared list, or into the tile's own indices
 * @param waterOffsets see {@link SurfaceMesh#waterOffsets}, into the tile's own indices
 */
public record SurfaceTile(int groups, boolean sharedGround, int[] groundOffsets, int[] waterOffsets, int vertexBytes, float baseY) {
    public boolean hasWater() {
        return this.waterOffsets[this.waterOffsets.length - 1] > this.waterOffsets[0];
    }
}
