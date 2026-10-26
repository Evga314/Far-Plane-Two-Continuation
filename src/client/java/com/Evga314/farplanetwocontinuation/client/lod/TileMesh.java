package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
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
    /** Where in the shared index list of rough tiles this tile's triangles start: floating islands have their own, see TileMeshBuilder. */
    final int firstIndex;
    final boolean detail;
    final long bytes;
    /** Detailed tiles: one bit per 64-block square whose blocks are all known; see {@link DetailMeshBuilder#coverage}. */
    final long coverage;
    /** Detailed tiles: every chunk had data. Incomplete tiles are refreshed sooner, the missing chunks may exist by now. */
    final boolean complete;
    /**
     * Detailed tiles: quads are sorted by the way they face (the groups below) and within a group chunk by chunk, row by row. This holds
     * the first quad of every chunk in every group, group by group, plus the total at the end, so a group of a chunk runs from
     * {@code segments[group * chunks + chunk]} to the next entry.
     */
    final int @Nullable [] segments;
    /** Per chunk, the lowest and highest block with a drawn top or underside; see {@code DetailMeshBuilder.surfaceRange}. */
    final int @Nullable [] chunkSurfaces;
    /** Per chunk the first quad of its glowing things and of its plants, two per chunk, which come last in it; null without. */
    final int @Nullable [] chunkPlants;
    /** Detailed tiles drawn with block textures, in the larger vertex format. */
    final boolean textured;
    /** Has translucent water, drawn in a second pass. */
    final boolean hasWater;
    /** Has faces whose texture has gaps, which the shader drops texel by texel. */
    final boolean hasCutout;
    /** Rough tiles: lowest ground of each part of the tile, for {@link Horizon}. */
    final float @Nullable [] partLows;
    /** Hidden behind terrain this tick, see {@link Horizon}. */
    boolean occluded;
    /** Detailed tiles: chunks hidden behind terrain this tick, see {@link Horizon}. */
    boolean @Nullable [] hiddenChunks;
    /** Index ranges for this frame only, {@link #drawRanges} without the chunks off screen; null uses those. */
    int @Nullable [] frameRanges;
    /** Nothing of this tile is cut away this frame, so it is drawn with a shader that never drops a fragment. */
    boolean solid;
    /** When the data of this tile arrived, used to refresh detailed tiles now and then. Set back to force a refresh. */
    long receivedAt;
    /** When blocks under this tile last changed, 0 if never. */
    long changedAt;
    /** Chunks whose blocks changed since the data arrived, as packed chunk positions; null when none did. */
    it.unimi.dsi.fastutil.longs.@Nullable LongArrayList changedChunks;
    /** Whether the refresh for changed blocks was counted in the performance log already, and whether a put-off one was. */
    boolean changeRefreshCounted;
    boolean changeDeferCounted;
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
    /** The mesh of the same tile that took this one's place, until the layer puts it where this one was drawn. */
    @Nullable TileMesh replacedBy;
    /** Fragments closer than this horizontal distance are dropped, because detailed tiles already cover them. Set every tick. */
    float discardRadius;
    /** Fragments farther than this horizontal distance are dropped, so detailed tiles end exactly at the ring. 0 keeps everything. */
    float keepRadius;
    /** Over this width inside {@link #keepRadius} detailed tiles fade out block by block into the rough terrain. Set every tick. */
    float fadeWidth;
    /** Distance to the camera in the current frame, used to draw near tiles first. */
    double sortDistance;

    TileMesh(TilePos pos, GpuBuffer vertices, GeneralMeshFactory.Built built, long receivedAt) {
        this.pos = pos;
        this.vertices = vertices;
        this.minY = built.minY();
        this.maxY = built.maxY();
        this.terrainMinY = built.terrainMinY();
        this.heights = built.heights();
        this.indexCount = built.indexCount();
        this.firstIndex = !built.detail() && built.indexCount() == TileMeshBuilder.FLOATING_INDEX_COUNT ? TileMeshBuilder.FLOATING_FIRST_INDEX : 0;
        this.detail = built.detail();
        this.bytes = built.vertices().remaining();
        this.coverage = built.coverage();
        this.complete = built.complete();
        this.receivedAt = receivedAt;
        this.segments = built.segments();
        this.chunkSurfaces = built.chunkSurfaces();
        this.chunkPlants = built.chunkPlants();
        this.textured = built.textured();
        this.hasWater = built.hasWater();
        this.hasCutout = built.hasCutout();
        this.partLows = !built.detail() && built.heights() != null ? Horizon.partLows(built.heights()) : null;
    }

    int chunksPerSide() {
        return this.chunkSurfaces == null ? 0 : (int) Math.round(Math.sqrt(this.chunkSurfaces.length / 2.0));
    }

    /**
     * The ways faces of detailed tiles can face, in the order they are stored. Faces turned away from the camera are left out of
     * drawing; the order puts the groups usually seen together next to each other, so they are drawn with one call: tops and other
     * faces, and the two sides facing the camera.
     */
    static final int GROUP_UP = 0;
    /** Plants and the slanted sides of stalks, never left out. */
    static final int GROUP_OTHER = 1;
    static final int GROUP_NORTH = 2;
    static final int GROUP_WEST = 3;
    static final int GROUP_SOUTH = 4;
    static final int GROUP_EAST = 5;
    static final int GROUP_DOWN = 6;
    /**
     * Translucent water, whichever way it faces, last of all: only the water passes draw it, and they draw nothing else, see
     * {@link #waterStart}.
     */
    static final int GROUP_WATER = 7;
    static final int GROUPS = 8;

    /** Detailed tiles: the first index of the translucent water, which comes after everything else; the index count without any. */
    int waterStart() {
        int[] segments = this.segments;
        if (segments == null) {
            return this.indexCount;
        }
        int chunks = segments.length / GROUPS;
        return segments[GROUP_WATER * chunks] * 6;
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

    /** The whole vertex buffer as a slice, made once instead of for every pass of every frame. Render thread only. */
    GpuBufferSlice vertexSlice() {
        if (this.vertexSlice == null) {
            this.vertexSlice = this.vertices.slice();
        }
        return this.vertexSlice;
    }

    private @Nullable GpuBufferSlice vertexSlice;

    @Override
    public void close() {
        if (!this.vertices.isClosed()) {
            this.vertices.close();
        }
    }
}
