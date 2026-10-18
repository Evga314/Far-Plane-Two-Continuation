package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** A tile uploaded to the GPU, either a rough terrain tile or a detailed one built from real blocks. */
final class TileMesh implements AutoCloseable {
    final TilePos pos;
    final GpuBuffer vertices;
    final float minY;
    final float maxY;
    /** Lowest terrain point, without the skirt. */
    final float terrainMinY;
    /** Sample heights of rough tiles, kept to know how high the LOD surface is right under the camera. */
    private final short @Nullable [] heights;
    final int indexCount;
    final boolean detail;
    final long bytes;
    /** Detailed tiles: one bit per 64-block square whose blocks are all known; see {@link DetailMeshBuilder#coverage}. */
    final long coverage;
    /** Detailed tiles: every chunk had data. Incomplete tiles are refreshed sooner, the missing chunks may exist by now. */
    final boolean complete;
    /** Detailed tiles: first quad of every chunk of the tile, row by row, plus the total at the end. */
    final int @Nullable [] chunkQuads;
    /** Per chunk, the lowest and highest block with a drawn top or underside; see {@code DetailMeshBuilder.surfaceRange}. */
    final int @Nullable [] chunkSurfaces;
    /** Per chunk the first quad of its plants, which come last in the chunk; null without plants. */
    final int @Nullable [] chunkPlants;
    /** Detailed tiles drawn with block textures, in the larger vertex format. */
    final boolean textured;
    /** Has translucent water, drawn in a second pass. */
    final boolean hasWater;
    /** When the data of this tile arrived, used to refresh detailed tiles now and then. Set back to force a refresh. */
    long receivedAt;
    /** When blocks under this tile last changed, 0 if never. */
    long changedAt;
    /**
     * Index ranges to draw this frame as pairs of first index and count, leaving out chunks vanilla already draws. Null draws
     * everything. Set every tick.
     */
    int @Nullable [] drawRanges;
    /**
     * Chunks vanilla draws only partly, four numbers each: first index, index count, the sections vanilla draws as one bit each and
     * the lowest of them. They are drawn without those sections. Null when there are none. Set every tick.
     */
    int @Nullable [] clippedChunks;
    long lastUsedTick;
    /** Fragments closer than this horizontal distance are dropped, because detailed tiles already cover them. Set every tick. */
    float discardRadius;
    /** Fragments farther than this horizontal distance are dropped, so detailed tiles end exactly at the ring. 0 keeps everything. */
    float keepRadius;
    /** Over this width inside {@link #keepRadius} detailed tiles fade out block by block into the rough terrain. Set every tick. */
    float fadeWidth;
    /** Distance to the camera in the current frame, used to draw near tiles first. */
    double sortDistance;

    TileMesh(TilePos pos, GpuBuffer vertices, TileMeshBuilder.Built built, long receivedAt) {
        this.pos = pos;
        this.vertices = vertices;
        this.minY = built.minY();
        this.maxY = built.maxY();
        this.terrainMinY = built.terrainMinY();
        this.heights = built.heights();
        this.indexCount = built.indexCount();
        this.detail = built.detail();
        this.bytes = built.vertices().remaining();
        this.coverage = built.coverage();
        this.complete = built.complete();
        this.receivedAt = receivedAt;
        this.chunkQuads = built.chunkQuads();
        this.chunkSurfaces = built.chunkSurfaces();
        this.chunkPlants = built.chunkPlants();
        this.textured = built.textured();
        this.hasWater = built.hasWater();
    }

    int chunksPerSide() {
        return this.chunkQuads == null ? 0 : (int) Math.round(Math.sqrt(this.chunkQuads.length - 1));
    }

    /** Height of the LOD surface at a world position inside this tile, interpolated the same way the mesh is drawn. */
    float heightAt(double x, double z) {
        if (this.heights == null) {
            return this.maxY;
        }
        double spacing = 1 << this.pos.level();
        double localX = Mth.clamp((x - this.pos.minX()) / spacing, 0.0, FarPlaneConstants.TILE_CELLS);
        double localZ = Mth.clamp((z - this.pos.minZ()) / spacing, 0.0, FarPlaneConstants.TILE_CELLS);
        int x0 = Math.min((int) localX, FarPlaneConstants.TILE_CELLS - 1);
        int z0 = Math.min((int) localZ, FarPlaneConstants.TILE_CELLS - 1);
        double fx = localX - x0;
        double fz = localZ - z0;
        int row = FarPlaneConstants.TILE_SAMPLES;
        double h00 = this.heights[x0 + z0 * row];
        double h10 = this.heights[x0 + 1 + z0 * row];
        double h01 = this.heights[x0 + (z0 + 1) * row];
        double h11 = this.heights[x0 + 1 + (z0 + 1) * row];
        return (float) Mth.lerp(fz, Mth.lerp(fx, h00, h10), Mth.lerp(fx, h01, h11));
    }

    @Override
    public void close() {
        if (!this.vertices.isClosed()) {
            this.vertices.close();
        }
    }
}
