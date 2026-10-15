package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link DetailData} into boxes. Every quad faces outwards (counter-clockwise seen from outside), so back faces can be culled:
 * where the walls of two neighbouring tiles lie in the same plane, only the one facing the camera is drawn, never both.
 * Turns {@link DetailData} into boxes: every run of blocks in a cell becomes a box with a top, an underside where something hangs
 * over air, and side walls wherever the neighbouring cell leaves the side open, also under tree crowns and overhangs. Neighbouring tops of the same height and color are merged into one
 * quad. Every quad is four vertices; indices come from a shared quad index buffer.
 */
final class DetailMeshBuilder {
    private static final int CELLS = DetailData.CELLS;
    private static final int RUNS = DetailData.MAX_RUNS;
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_BOTTOM = 0.5F;
    private static final float SHADE_NORTH_SOUTH = 0.8F;
    private static final float SHADE_EAST_WEST = 0.65F;
    /**
     * Next to cells without data the rough terrain is shown, a little lower than the blocks. A short wall closes that step without
     * dropping a tall cliff into the rough terrain.
     */
    private static final int UNKNOWN_WALL = 8;
    private static final int[] DIRECTION_X = {0, 0, -1, 1};
    private static final int[] DIRECTION_Z = {-1, 1, 0, 0};

    private DetailMeshBuilder() {
    }

    static TileMeshBuilder.Built build(long key, int epoch, DetailData data, List<@Nullable Holder<Biome>> biomes) {
        TilePos pos = TilePos.unpack(key);
        int cell = 1 << (pos.level() - 1);
        long baseX = pos.minX();
        long baseZ = pos.minZ();
        QuadWriter out = new QuadWriter();
        int[] colors = new int[DetailData.CELL_COUNT * RUNS];
        int[] sides = new int[DetailData.CELL_COUNT * RUNS];

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                int index = cx + cz * CELLS;
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                int worldX = (int) Mth.clamp(baseX + (long) cx * cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                int worldZ = (int) Mth.clamp(baseZ + (long) cz * cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                for (int run = 0; run < data.runCounts[index]; run++) {
                    int slot = index * RUNS + run;
                    colors[slot] = color(data.colors[slot] & 0xFF, biome, worldX, worldZ);
                    sides[slot] = data.sides[slot] == data.colors[slot] ? colors[slot] : color(data.sides[slot] & 0xFF, biome, worldX, worldZ);
                    minY = Math.min(minY, data.bottoms[slot]);
                    maxY = Math.max(maxY, data.tops[slot]);
                }
            }
        }

        // Quads are written chunk by chunk, so the parts of the tile that vanilla already draws can be left out when drawing.
        int cellsPerChunk = Math.max(1, 16 / cell);
        int chunksPerSide = CELLS / cellsPerChunk;
        int[] chunkQuads = new int[chunksPerSide * chunksPerSide + 1];
        for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
            for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
                chunkQuads[chunkX + chunkZ * chunksPerSide] = out.quads;
                int startX = chunkX * cellsPerChunk;
                int startZ = chunkZ * cellsPerChunk;
                topFaces(out, data, colors, cell, baseX, baseZ, startX, startX + cellsPerChunk, startZ, startZ + cellsPerChunk);
                for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                    for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                        cellBoxes(out, data, colors, sides, cell, baseX, baseZ, cx, cz);
                    }
                }
            }
        }
        chunkQuads[chunkQuads.length - 1] = out.quads;

        ByteBuffer vertices = out.finish();
        if (minY > maxY) {
            minY = 0.0F;
            maxY = 0.0F;
        }
        return new TileMeshBuilder.Built(
            key, epoch, vertices, minY, maxY, minY, null, out.quads * 6, true, coverage(data, cell), data.isComplete(), chunkQuads
        );
    }

    /** Top faces of the highest runs of a block of cells; following cells of a row with the same height and color share one quad. */
    private static void topFaces(
        QuadWriter out, DetailData data, int[] colors, int cell, long baseX, long baseZ, int startX, int endX, int startZ, int endZ
    ) {
        for (int cz = startZ; cz < endZ; cz++) {
            float z0 = TileMeshBuilder.localCoordinate(baseZ, cz * cell, 1);
            float z1 = TileMeshBuilder.localCoordinate(baseZ, (cz + 1) * cell, 1);
            int cx = startX;
            while (cx < endX) {
                int index = cx + cz * CELLS;
                if (data.runCounts[index] == 0) {
                    cx++;
                    continue;
                }
                int top = data.tops[index * RUNS];
                int color = colors[index * RUNS];
                int end = cx + 1;
                while (end < endX) {
                    int next = end + cz * CELLS;
                    if (data.runCounts[next] == 0 || data.tops[next * RUNS] != top || colors[next * RUNS] != color) {
                        break;
                    }
                    end++;
                }
                float x0 = TileMeshBuilder.localCoordinate(baseX, cx * cell, 1);
                float x1 = TileMeshBuilder.localCoordinate(baseX, end * cell, 1);
                out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, ARGB.scaleRGB(color, SHADE_TOP));
                cx = end;
            }
        }
    }

    /** Lower tops, undersides and walls of the runs of one cell. */
    private static void cellBoxes(QuadWriter out, DetailData data, int[] colors, int[] sides, int cell, long baseX, long baseZ, int cx, int cz) {
        int index = cx + cz * CELLS;
        int runs = data.runCounts[index];
        float x0 = TileMeshBuilder.localCoordinate(baseX, cx * cell, 1);
        float x1 = TileMeshBuilder.localCoordinate(baseX, (cx + 1) * cell, 1);
        float z0 = TileMeshBuilder.localCoordinate(baseZ, cz * cell, 1);
        float z1 = TileMeshBuilder.localCoordinate(baseZ, (cz + 1) * cell, 1);
        for (int run = 0; run < runs; run++) {
            int slot = index * RUNS + run;
            int top = data.tops[slot];
            int bottom = data.bottoms[slot];
            int color = colors[slot];
            int side = sides[slot];
            if (run > 0) {
                out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, ARGB.scaleRGB(color, SHADE_TOP));
            }
            if (run + 1 < runs && bottom > data.tops[slot + 1]) {
                out.quad(x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1, ARGB.scaleRGB(color, SHADE_BOTTOM));
            }
            for (int direction = 0; direction < 4; direction++) {
                int nx = cx + DIRECTION_X[direction];
                int nz = cz + DIRECTION_Z[direction];
                if (nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS) {
                    // The neighbouring tile may be of another detail level, may not be drawn at all, or may be vanilla terrain, so its
                    // blocks cannot be relied on: border walls are always complete. Next to a drawn neighbour they stay hidden under it.
                    wall(out, direction, x0, x1, z0, z1, bottom, top, top, color, side);
                    continue;
                }
                int neighbour = nx + nz * CELLS;
                if (data.isMissing(neighbour)) {
                    // Without data next door only a short step down to the rough terrain is closed.
                    wall(out, direction, x0, x1, z0, z1, Math.max(bottom, data.tops[index * RUNS] - UNKNOWN_WALL), top, top, color, side);
                    continue;
                }
                // The side of this run minus everything the neighbour covers. Below its lowest run the neighbour counts as solid.
                int neighbourRuns = data.runCounts[neighbour];
                int neighbourBase = neighbour * RUNS;
                int open = top;
                for (int n = 0; n < neighbourRuns && open > bottom; n++) {
                    int neighbourTop = data.tops[neighbourBase + n];
                    if (neighbourTop < open) {
                        wall(out, direction, x0, x1, z0, z1, Math.max(bottom, neighbourTop), open, top, color, side);
                    }
                    open = Math.min(open, data.bottoms[neighbourBase + n]);
                }
                if (neighbourRuns == 0) {
                    wall(out, direction, x0, x1, z0, z1, bottom, top, top, color, side);
                }
            }
        }
    }

    /** A wall of a run between two heights: its top block in the top color, the rest in the side color. */
    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, int bottom, int top, int runTop, int color, int side) {
        if (side == color) {
            wall(out, direction, x0, x1, z0, z1, bottom, top, color);
            return;
        }
        int split = Mth.clamp(runTop - 1, bottom, top);
        wall(out, direction, x0, x1, z0, z1, split, top, color);
        wall(out, direction, x0, x1, z0, z1, bottom, split, side);
    }

    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, int bottom, int top, int color) {
        if (bottom >= top) {
            return;
        }
        switch (direction) {
            case 0 -> out.quad(x0, bottom, z0, x0, top, z0, x1, top, z0, x1, bottom, z0, ARGB.scaleRGB(color, SHADE_NORTH_SOUTH));
            case 1 -> out.quad(x0, bottom, z1, x1, bottom, z1, x1, top, z1, x0, top, z1, ARGB.scaleRGB(color, SHADE_NORTH_SOUTH));
            case 2 -> out.quad(x0, bottom, z0, x0, bottom, z1, x0, top, z1, x0, top, z0, ARGB.scaleRGB(color, SHADE_EAST_WEST));
            default -> out.quad(x1, bottom, z0, x1, top, z0, x1, top, z1, x1, bottom, z1, ARGB.scaleRGB(color, SHADE_EAST_WEST));
        }
    }

    /**
     * One bit per 64-block square of the tile, set when every cell in it has real data. Only those squares replace the rough terrain;
     * the rest keeps showing it.
     */
    static long coverage(DetailData data, int cell) {
        int squares = cell;
        int cellsPerSquare = CELLS / squares;
        long mask = 0L;
        for (int sz = 0; sz < squares; sz++) {
            for (int sx = 0; sx < squares; sx++) {
                boolean complete = true;
                for (int cz = sz * cellsPerSquare; complete && cz < (sz + 1) * cellsPerSquare; cz++) {
                    for (int cx = sx * cellsPerSquare; cx < (sx + 1) * cellsPerSquare; cx++) {
                        if (data.isMissing(cx + cz * CELLS)) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (complete) {
                    mask |= 1L << (sx + sz * squares);
                }
            }
        }
        return mask;
    }

    /** Map colors as seen on maps; grass, foliage and water take the biome tint like the real blocks do. */
    private static int color(int colorId, @Nullable Holder<Biome> holder, int x, int z) {
        MapColor mapColor = MapColor.byId(colorId);
        if (holder != null) {
            Biome biome = holder.value();
            if (mapColor == MapColor.GRASS) {
                return ARGB.opaque(biome.getGrassColor(x, z));
            }
            if (mapColor == MapColor.PLANT) {
                return ARGB.opaque(ARGB.scaleRGB(biome.getFoliageColor(), 0.9F));
            }
            if (mapColor == MapColor.WATER) {
                // The same shade as water in the rough terrain, so the two match where they meet.
                return ARGB.opaque(ARGB.scaleRGB(biome.getWaterColor(), 0.85F * TileMeshBuilder.WATER_SHADE));
            }
        }
        return ARGB.opaque(mapColor.col);
    }

    /** Growable POSITION_COLOR vertex buffer, four vertices per quad. */
    private static final class QuadWriter {
        private ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
        int quads;

        void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int color) {
            if (this.buffer.remaining() < 4 * TileMeshBuilder.VERTEX_SIZE) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(this.buffer.capacity() * 2).order(ByteOrder.nativeOrder());
                this.buffer.flip();
                bigger.put(this.buffer);
                this.buffer = bigger;
            }
            this.vertex(ax, ay, az, color);
            this.vertex(bx, by, bz, color);
            this.vertex(cx, cy, cz, color);
            this.vertex(dx, dy, dz, color);
            this.quads++;
        }

        private void vertex(float x, float y, float z, int argb) {
            this.buffer.putFloat(x).putFloat(y).putFloat(z);
            this.buffer.put((byte) ARGB.red(argb)).put((byte) ARGB.green(argb)).put((byte) ARGB.blue(argb)).put((byte) 255);
        }

        ByteBuffer finish() {
            this.buffer.flip();
            return this.buffer;
        }
    }
}
