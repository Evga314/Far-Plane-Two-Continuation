package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.BlockDetail;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link DetailData} into boxes: every run of blocks in a cell becomes a box with a top, an underside where something hangs over
 * air, and side walls wherever the neighbouring cell leaves the side open, also under tree crowns and overhangs. Neighbouring tops of the
 * same height and look are merged into one quad. Every quad faces outwards (counter-clockwise seen from outside), so back faces can be
 * culled: where the walls of two neighbouring tiles lie in the same plane, only the one facing the camera is drawn, never both. Every
 * quad is four vertices; indices come from a shared quad index buffer.
 * <p>
 * How the blocks look depends on {@link BlockDetail}: flat colors taken from the block textures, or the textures themselves, from a
 * blurred copy up to vanilla's own, with thin blocks (snow layers, carpets, slabs) as thin as they are, leaves with gaps and plants
 * on the ground.
 */
final class DetailMeshBuilder {
    private static final int CELLS = DetailData.CELLS;
    private static final int RUNS = DetailData.MAX_RUNS;
    /**
     * Position in fixed point (x and z in 64ths of a block, y in 16ths) and the sprite size in texels as four shorts, the color, and the
     * sprite corner in the atlas in half texels as two shorts. Half the size of floats, which matters for the memory of phones.
     */
    static final int TEXTURED_VERTEX_SIZE = 16;
    static final float XZ_UNITS = 64.0F;
    static final float Y_UNITS = 16.0F;
    // Vanilla's shading of block faces by direction.
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_BOTTOM = 0.5F;
    private static final float SHADE_NORTH_SOUTH = 0.8F;
    private static final float SHADE_EAST_WEST = 0.6F;
    // How the shader maps a face to its texture, kept in the alpha of the vertex color: 255 minus the kind, minus 4 for faces with gaps.
    private static final int KIND_HORIZONTAL = 0;
    private static final int KIND_NORTH_SOUTH = 1;
    private static final int KIND_EAST_WEST = 2;
    private static final int KIND_PLANT = 3;
    private static final int CUTOUT = 4;
    /** Translucent water, drawn in a pass of its own after everything else. */
    private static final int TRANSLUCENT_WATER = 8;
    /**
     * Next to cells without data the rough terrain is shown, a little lower than the blocks. A short wall closes that step without
     * dropping a tall cliff into the rough terrain.
     */
    private static final int UNKNOWN_WALL = 8;
    /**
     * Faces with gaps (leaves) get a second, closed layer one block further in, like the Cull Less Leaves mod: through the gaps the
     * crown looks full of leaves instead of hollow, and nothing behind it has to be drawn there. A little darker, as inside a crown.
     */
    private static final float INNER_LAYER = 1.0F;
    private static final float INNER_SHADE = 0.75F;
    /** Size of the block on top of a stalk, a chorus flower's. */
    private static final float CAP_SIZE = 14.0F / 16.0F;
    private static final int[] DIRECTION_X = {0, 0, -1, 1};
    private static final int[] DIRECTION_Z = {-1, 1, 0, 0};
    private static final Identifier WATER = Identifier.withDefaultNamespace("water");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");

    private DetailMeshBuilder() {
    }

    /** How a surface is drawn: its color (the tint for textured faces), and for textured faces the texture and whether it has gaps. */
    /** {@code uShift} moves the texture sideways on the atlas, see {@link #column}. */
    private record Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout, boolean water, float uShift) {
        Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout, boolean water) {
            this(color, sprite, cutout, water, 0.0F);
        }

        Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout) {
            this(color, sprite, cutout, false, 0.0F);
        }

        /** The same texture with its gaps filled, for the closed layer inside faces with gaps. */
        Paint closed() {
            return new Paint(this.color, this.sprite, false, false, this.uShift);
        }
    }

    static TileMeshBuilder.Built build(long key, int epoch, DetailData data, List<@Nullable Holder<Biome>> biomes, BlockDetail detail) {
        TilePos pos = TilePos.unpack(key);
        int cell = 1 << (pos.level() - 1);
        long baseX = pos.minX();
        long baseZ = pos.minZ();
        boolean textured = detail.textured();
        QuadWriter out = new QuadWriter(textured);
        Paints paints = new Paints();
        int slots = DetailData.CELL_COUNT * RUNS;
        int[] topPaints = new int[slots];
        int[] edgePaints = new int[slots];
        int[] sidePaints = new int[slots];
        int[] underPaints = new int[slots];
        // Stalks drawn as columns: their thickness (0 for boxes), side and top paints.
        float[] columnWidths = new float[slots];
        int[] columnSides = new int[slots];
        int[] columnTops = new int[slots];
        // A different block on top of a stalk (a chorus flower), drawn as a block of its own.
        boolean[] columnCaps = new boolean[slots];
        float[] topHeights = new float[slots];
        int[] plantPaints = new int[DetailData.CELL_COUNT];
        Identifier[] blockIds = data.blockPalette.toArray(Identifier[]::new);
        boolean hasWater = false;

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                int index = cx + cz * CELLS;
                plantPaints[index] = -1;
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                int worldX = (int) Mth.clamp(baseX + (long) cx * cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                int worldZ = (int) Mth.clamp(baseZ + (long) cz * cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                for (int run = 0; run < data.runCounts[index]; run++) {
                    int slot = index * RUNS + run;
                    int topY = data.tops[slot] - 1;
                    Identifier topId = blockIds[data.blocks[slot] & 0xFF];
                    BlockLooks.Look top = BlockLooks.get(topId);
                    BlockLooks.Look side = BlockLooks.get(blockIds[data.sides[slot] & 0xFF]);
                    boolean water = top.fluid() && topId.equals(WATER);
                    topPaints[slot] = paints.index(paint(top.top(), top.state(), water, detail, biome, worldX, topY, worldZ));
                    edgePaints[slot] = paints.index(paint(top.side(), top.state(), water, detail, biome, worldX, topY, worldZ));
                    if (!detail.thinBlocks() && data.heights[slot] < 16) {
                        // A thin block on top drawn as a full block: only its top shows it, the sides show what it lies on.
                        edgePaints[slot] = -1;
                    }
                    boolean sideWater = side.fluid() && blockIds[data.sides[slot] & 0xFF].equals(WATER);
                    sidePaints[slot] = paints.index(paint(side.side(), side.state(), sideWater, detail, biome, worldX, topY - 1, worldZ));
                    if (edgePaints[slot] < 0) {
                        edgePaints[slot] = sidePaints[slot];
                    }
                    // A thin block with gaps on top (leaf litter) shows the top of the block it lies on through them.
                    underPaints[slot] = data.heights[slot] < 16 && top.top().cutout()
                        ? paints.index(paint(side.top(), side.state(), sideWater, detail, biome, worldX, topY - 1, worldZ))
                        : -1;
                    BlockLooks.Look stalk = side.columnWidth() > 0.0F ? side : top.columnWidth() > 0.0F ? top : null;
                    if (stalk != null) {
                        columnWidths[slot] = stalk.columnWidth();
                        Paint stalkSide = paint(stalk.side(), stalk.state(), false, detail, biome, worldX, topY, worldZ);
                        // On single-block cells the stalk texture is moved so its middle is in the middle of the column; on coarser cells
                        // the column is not where the texture repeats, so it gets the stalk's color.
                        columnSides[slot] = paints.index(cell == 1 && stalkSide.sprite() != null
                            ? new Paint(stalkSide.color(), stalkSide.sprite(), stalkSide.cutout(), false,
                                stalk.columnU() - (stalkSide.sprite().getU0() + stalkSide.sprite().getU1()) * 0.5F)
                            : new Paint(ARGB.opaque(stalk.side().color(stalk.state(), biome, worldX, topY, worldZ)), null, false));
                        columnTops[slot] = paints.index(new Paint(ARGB.opaque(top.top().color(top.state(), biome, worldX, topY, worldZ)), null, false));
                        columnCaps[slot] = stalk == side && top.columnWidth() <= 0.0F && data.tops[slot] - 1 > data.bottoms[slot];
                    }
                    hasWater |= water && detail.translucentWater();
                    topHeights[slot] = detail.thinBlocks() ? data.tops[slot] - 1 + data.heights[slot] / 16.0F : data.tops[slot];
                    minY = Math.min(minY, data.bottoms[slot]);
                    maxY = Math.max(maxY, data.tops[slot] + (run == 0 && detail.plants() ? 1 : 0));
                }
                int plant = data.plants[index] & 0xFF;
                if (detail.plants() && cell == 1 && plant != DetailData.NO_PLANT && data.runCounts[index] > 0 && plant - 1 < blockIds.length) {
                    BlockLooks.Look look = BlockLooks.get(blockIds[plant - 1]);
                    Paint paint = paint(look.plant(), look.state(), false, detail, biome, worldX, data.tops[index * RUNS], worldZ);
                    plantPaints[index] = paints.index(new Paint(paint.color(), paint.sprite(), true));
                }
            }
        }

        // Quads are written chunk by chunk, so the parts of the tile that vanilla already draws can be left out when drawing.
        int cellsPerChunk = Math.max(1, 16 / cell);
        int chunksPerSide = CELLS / cellsPerChunk;
        int[] chunkQuads = new int[chunksPerSide * chunksPerSide + 1];
        int[] chunkSurfaces = new int[chunksPerSide * chunksPerSide * 2];
        // Plants come last in every chunk, so far chunks can be drawn without them.
        boolean plants = detail.plants() && cell == 1;
        int[] chunkPlants = plants ? new int[chunksPerSide * chunksPerSide] : null;
        BlockLooks.Look stone = BlockLooks.get(STONE);
        int floorPaint = paints.index(paint(stone.top(), stone.state(), false, detail, null, 0, 0, 0));
        Cells cells = new Cells(
            data, paints, topPaints, edgePaints, sidePaints, underPaints, columnWidths, columnSides, columnTops, columnCaps, topHeights, plantPaints,
            floorPaint, cell, baseX, baseZ, detail
        );
        boolean[] clearBlocks = clear(blockIds);
        for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
            for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
                WorkThrottle.yieldToFrames();
                chunkQuads[chunkX + chunkZ * chunksPerSide] = out.quads;
                int startX = chunkX * cellsPerChunk;
                int startZ = chunkZ * cellsPerChunk;
                surfaceRange(data, clearBlocks, startX, startZ, cellsPerChunk, chunkSurfaces, (chunkX + chunkZ * chunksPerSide) * 2);
                cells.topFaces(out, startX, startX + cellsPerChunk, startZ, startZ + cellsPerChunk);
                for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                    for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                        cells.boxes(out, cx, cz);
                    }
                }
                if (chunkPlants != null) {
                    chunkPlants[chunkX + chunkZ * chunksPerSide] = out.quads;
                    for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                        for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                            cells.plant(out, cx, cz);
                        }
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
            key, epoch, vertices, minY, maxY, minY, null, out.quads * 6, true, coverage(data, cell), data.isComplete(), chunkQuads, chunkSurfaces,
            textured, hasWater, chunkPlants
        );
    }

    /** Which blocks of the palette let the runs under them be seen: water, lava and blocks with gaps like leaves. */
    private static boolean[] clear(Identifier[] blockIds) {
        boolean[] clear = new boolean[blockIds.length];
        for (int i = 0; i < blockIds.length; i++) {
            BlockLooks.Look look = BlockLooks.get(blockIds[i]);
            clear[i] = look.fluid() || look.top().cutout();
        }
        return clear;
    }

    /**
     * The paint of one face of a block at a place. Flat faces get the average color of the texture with the biome tint; textured
     * faces only the tint. Water stays in the flat color of the rough terrain's water at every level, so the two match where they meet.
     */
    private static Paint paint(
        BlockLooks.Face face, BlockState state, boolean water, BlockDetail detail, @Nullable Holder<Biome> biome, int x, int y, int z
    ) {
        boolean textured = detail.textured();
        if (water && detail.translucentWater() && face.sprite() != null) {
            int tint = biome == null ? 0xFF3F76E4 : ARGB.opaque(biome.value().getWaterColor());
            return new Paint(tint, face.sprite(), false, true);
        }
        if (water) {
            int color = ARGB.scaleRGB(TileColors.waterColor(biome), TileMeshBuilder.WATER_SHADE);
            return new Paint(color, null, false);
        }
        if (!textured || face.sprite() == null) {
            return new Paint(ARGB.opaque(face.color(state, biome, x, y, z)), null, false);
        }
        return new Paint(face.tintColor(state, biome, x, y, z), face.sprite(), face.cutout() && detail.cutout());
    }

    /**
     * Lowest and highest block of a block of cells whose top or underside can be seen from outside: what vanilla has to draw too
     * before this part of the tile may be left out. The top of every column counts. Lower tops and undersides, under a bridge or an
     * End city, count where a neighbouring column is open to the sky down to them; the floors and ceilings of caves are closed in by
     * the ground around them and do not. Writes {@code Integer.MAX_VALUE, Integer.MIN_VALUE} when nothing is there.
     */
    private static void surfaceRange(DetailData data, boolean[] clear, int startX, int startZ, int cells, int[] out, int offset) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int cz = startZ; cz < startZ + cells; cz++) {
            for (int cx = startX; cx < startX + cells; cx++) {
                int index = cx + cz * CELLS;
                int runs = data.runCounts[index];
                if (runs == 0) {
                    continue;
                }
                int top = data.tops[index * RUNS] - 1;
                low = Math.min(low, top);
                // A plant stands in the block above the ground, which may be in the next section.
                high = Math.max(high, data.plants[index] != DetailData.NO_PLANT ? top + 1 : top);
                // The side of the column down to its lowest neighbour is seen too, also next to another chunk: a bank over water
                // there may lie in a section vanilla leaves out while all its tops are in sections vanilla draws.
                low = Math.min(low, Math.max(data.bottoms[index * RUNS], lowestNeighbour(data, cx, cz, top + 1)));
                if (runs == 1) {
                    continue;
                }
                int open = openDownTo(data, cx, cz);
                for (int run = 0; run + 1 < runs; run++) {
                    int slot = index * RUNS + run;
                    int bottom = data.bottoms[slot];
                    int below = data.tops[slot + 1] - 1;
                    if (bottom > below + 1 && open <= bottom) {
                        low = Math.min(low, bottom);
                    }
                    // The ground under water or leaves is seen through them.
                    boolean throughClear = clear[data.blocks[slot] & 0xFF] && (run == 0 || open <= data.tops[slot]);
                    if (open <= below + 1 || throughClear) {
                        low = Math.min(low, below);
                    }
                }
            }
        }
        out[offset] = low;
        out[offset + 1] = high;
    }

    /**
     * Lowest block of a column's side that its four neighbours leave open, at most its top. Next to a neighbour outside the tile or
     * without data the side counts as open down as far as border walls are drawn.
     */
    private static int lowestNeighbour(DetailData data, int cx, int cz, int top) {
        int lowest = top - 1;
        for (int direction = 0; direction < 4; direction++) {
            int nx = cx + DIRECTION_X[direction];
            int nz = cz + DIRECTION_Z[direction];
            if (nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS || data.isMissing(nx + nz * CELLS)) {
                lowest = Math.min(lowest, top - UNKNOWN_WALL);
                continue;
            }
            int neighbour = nx + nz * CELLS;
            if (data.runCounts[neighbour] > 0) {
                lowest = Math.min(lowest, data.tops[neighbour * RUNS]);
            }
        }
        return lowest;
    }

    /** Lowest height down to which one of the four neighbours of a cell is open to the sky; neighbours outside the tile do not count. */
    private static int openDownTo(DetailData data, int cx, int cz) {
        int open = Integer.MAX_VALUE;
        for (int direction = 0; direction < 4; direction++) {
            int nx = cx + DIRECTION_X[direction];
            int nz = cz + DIRECTION_Z[direction];
            if (nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS) {
                continue;
            }
            int neighbour = nx + nz * CELLS;
            if (data.isMissing(neighbour)) {
                continue;
            }
            open = Math.min(open, data.runCounts[neighbour] == 0 ? Integer.MIN_VALUE : data.tops[neighbour * RUNS]);
        }
        return open;
    }

    /** Everything known about the cells of one tile while its quads are written. */
    private record Cells(
        DetailData data, Paints paints, int[] topPaints, int[] edgePaints, int[] sidePaints, int[] underPaints, float[] columnWidths,
        int[] columnSides, int[] columnTops, boolean[] columnCaps, float[] topHeights, int[] plantPaints, int floorPaint, int cell, long baseX, long baseZ,
        BlockDetail detail
    ) {
        private float x(int cx) {
            return TileMeshBuilder.localCoordinate(this.baseX, cx * this.cell, 1);
        }

        private float z(int cz) {
            return TileMeshBuilder.localCoordinate(this.baseZ, cz * this.cell, 1);
        }

        /**
         * Top faces of the highest runs of a block of cells; following cells of a row with the same height and look share one quad.
         * Stalks are left out, they draw their own.
         */
        void topFaces(QuadWriter out, int startX, int endX, int startZ, int endZ) {
            for (int cz = startZ; cz < endZ; cz++) {
                float z0 = this.z(cz);
                float z1 = this.z(cz + 1);
                int cx = startX;
                while (cx < endX) {
                    int index = cx + cz * CELLS;
                    // Stalks draw their own top.
                    if (this.data.runCounts[index] == 0 || this.columnWidths[index * RUNS] > 0.0F) {
                        cx++;
                        continue;
                    }
                    float top = this.topHeights[index * RUNS];
                    int paint = this.topPaints[index * RUNS];
                    int end = cx + 1;
                    while (end < endX) {
                        int next = end + cz * CELLS;
                        if (this.data.runCounts[next] == 0 || this.columnWidths[next * RUNS] > 0.0F || this.topHeights[next * RUNS] != top
                            || this.topPaints[next * RUNS] != paint) {
                            break;
                        }
                        end++;
                    }
                    float x0 = this.x(cx);
                    float x1 = this.x(end);
                    out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, this.paints.get(paint), SHADE_TOP, KIND_HORIZONTAL);
                    cx = end;
                }
            }
        }

        /** Lower tops, undersides and walls of the runs of one cell. */
        void boxes(QuadWriter out, int cx, int cz) {
            DetailData data = this.data;
            int index = cx + cz * CELLS;
            int runs = data.runCounts[index];
            float x0 = this.x(cx);
            float x1 = this.x(cx + 1);
            float z0 = this.z(cz);
            float z1 = this.z(cz + 1);
            for (int run = 0; run < runs; run++) {
                int slot = index * RUNS + run;
                float top = this.topHeights[slot];
                int bottom = data.bottoms[slot];
                if (this.columnWidths[slot] > 0.0F) {
                    float centerX = (x0 + x1) * 0.5F;
                    float centerZ = (z0 + z1) * 0.5F;
                    int stalkTop = this.columnCaps[slot] ? data.tops[slot] - 1 : data.tops[slot];
                    column(out, centerX, centerZ, this.columnWidths[slot], bottom, stalkTop, this.paints.get(this.columnSides[slot]),
                        this.paints.get(this.columnTops[slot]));
                    if (this.columnCaps[slot]) {
                        float half = CAP_SIZE * 0.5F;
                        Paint capSide = this.paints.get(this.edgePaints[slot]);
                        for (int direction = 0; direction < 4; direction++) {
                            face(out, direction, centerX - half, centerX + half, centerZ - half, centerZ + half, stalkTop, stalkTop + CAP_SIZE, capSide, 1.0F);
                        }
                        float capTop = stalkTop + CAP_SIZE;
                        out.quad(centerX - half, capTop, centerZ - half, centerX - half, capTop, centerZ + half, centerX + half, capTop, centerZ + half,
                            centerX + half, capTop, centerZ - half, this.paints.get(this.topPaints[slot]), SHADE_TOP, KIND_HORIZONTAL);
                    }
                    continue;
                }
                Paint edge = this.paints.get(this.edgePaints[slot]);
                Paint side = this.paints.get(this.sidePaints[slot]);
                boolean clearWater = this.paints.get(this.topPaints[slot]).water();
                // Ground under translucent water: its border walls would hang under the water like curtains, deep down to the end of the
                // run; a short step is enough there.
                boolean underClearWater = run > 0 && this.paints.get(this.topPaints[slot - 1]).water();
                int borderBottom = underClearWater ? Math.max(bottom, data.tops[slot] - UNKNOWN_WALL) : bottom;
                Paint topPaint = this.paints.get(this.topPaints[slot]);
                if (run > 0) {
                    out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, topPaint, SHADE_TOP, KIND_HORIZONTAL);
                }
                if (topPaint.cutout() && top - INNER_LAYER > bottom) {
                    float inner = top - INNER_LAYER;
                    out.quad(x0, inner, z0, x0, inner, z1, x1, inner, z1, x1, inner, z0, topPaint.closed(), SHADE_TOP * INNER_SHADE, KIND_HORIZONTAL);
                }
                if (this.underPaints[slot] >= 0 && data.tops[slot] - 1 > bottom) {
                    // Under a thin block with gaps: the top of the block it lies on, also where vanilla draws the thin block and leaves the
                    // section under it to this tile.
                    float under = data.tops[slot] - 1;
                    out.quad(x0, under, z0, x0, under, z1, x1, under, z1, x1, under, z0, this.paints.get(this.underPaints[slot]), SHADE_TOP, KIND_HORIZONTAL);
                }
                boolean grounded = run + 1 < runs && data.tops[slot + 1] == bottom && !this.paints.get(this.topPaints[slot + 1]).water();
                if (clearWater && !grounded) {
                    // Chunks not fully generated may have nothing under their water; a floor there keeps the sky from showing through.
                    out.quad(x0, bottom, z0, x0, bottom, z1, x1, bottom, z1, x1, bottom, z0, this.paints.get(this.floorPaint), SHADE_TOP, KIND_HORIZONTAL);
                }
                if (run + 1 < runs && bottom > data.tops[slot + 1]) {
                    out.quad(x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1, side, SHADE_BOTTOM, KIND_HORIZONTAL);
                }
                int runTop = data.tops[slot];
                for (int direction = 0; direction < 4; direction++) {
                    int nx = cx + DIRECTION_X[direction];
                    int nz = cz + DIRECTION_Z[direction];
                    // Cells of 2 or 4 blocks take their highest column; where vanilla draws the next chunk, its real ground at the
                    // border may lie lower than that, and vanilla leaves the side open towards the solid blocks it knows are there.
                    boolean otherChunk = this.cell > 1 && (nx * this.cell >> 4 != cx * this.cell >> 4 || nz * this.cell >> 4 != cz * this.cell >> 4);
                    boolean outside = nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS;
                    if (clearWater && (outside || otherChunk)) {
                        // Walls of translucent water would show through it as sheets; the water next door is level with it anyway.
                        if (outside) {
                            continue;
                        }
                    } else if (outside || otherChunk) {
                        // The neighbouring tile may be of another detail level, may not be drawn at all, or may be vanilla terrain, so its
                        // blocks cannot be relied on: border walls are always complete. Next to a drawn neighbour they stay hidden under it.
                        // The same holds for chunk borders of coarse tiles, see above.
                        wall(out, direction, x0, x1, z0, z1, borderBottom, top, runTop, edge, side);
                        continue;
                    }
                    int neighbour = nx + nz * CELLS;
                    if (data.isMissing(neighbour)) {
                        // Without data next door only a short step down to the rough terrain is closed.
                        wall(out, direction, x0, x1, z0, z1, Math.max(bottom, data.tops[index * RUNS] - UNKNOWN_WALL), top, runTop, edge, side);
                        continue;
                    }
                    // The side of this run minus everything the neighbour covers. Below its lowest run the neighbour counts as solid.
                    // Translucent water next to ground covers nothing: the step of the ground under it is seen through it.
                    int neighbourRuns = data.runCounts[neighbour];
                    int neighbourBase = neighbour * RUNS;
                    float open = top;
                    for (int n = 0; n < neighbourRuns && open > bottom; n++) {
                        // A stalk next door covers almost nothing of this side.
                        if (this.columnWidths[neighbourBase + n] > 0.0F || !clearWater && this.paints.get(this.topPaints[neighbourBase + n]).water()) {
                            continue;
                        }
                        float neighbourTop = this.topHeights[neighbourBase + n];
                        if (neighbourTop < open) {
                            wall(out, direction, x0, x1, z0, z1, Math.max(bottom, neighbourTop), open, runTop, edge, side);
                        }
                        open = Math.min(open, data.bottoms[neighbourBase + n]);
                    }
                    if (neighbourRuns == 0) {
                        wall(out, direction, x0, x1, z0, z1, bottom, top, runTop, edge, side);
                    }
                }
            }
        }

        /** The plant on the ground of a cell as two crossed planes, seen from both sides, the way vanilla draws grass and flowers. */
        void plant(QuadWriter out, int cx, int cz) {
            int index = cx + cz * CELLS;
            int paint = this.plantPaints[index];
            if (paint < 0) {
                return;
            }
            Paint plant = this.paints.get(paint);
            float x0 = this.x(cx);
            float x1 = this.x(cx + 1);
            float z0 = this.z(cz);
            float z1 = this.z(cz + 1);
            float y0 = this.topHeights[index * RUNS];
            float y1 = y0 + this.cell;
            out.quad(x0, y0, z0, x0, y1, z0, x1, y1, z1, x1, y0, z1, plant, SHADE_TOP, KIND_PLANT);
            out.quad(x1, y0, z1, x1, y1, z1, x0, y1, z0, x0, y0, z0, plant, SHADE_TOP, KIND_PLANT);
            out.quad(x0, y0, z1, x0, y1, z1, x1, y1, z0, x1, y0, z0, plant, SHADE_TOP, KIND_PLANT);
            out.quad(x1, y0, z0, x1, y1, z0, x0, y1, z1, x0, y0, z1, plant, SHADE_TOP, KIND_PLANT);
        }
    }

    /**
     * A stalk (bamboo, chorus plant) as an eight-sided column standing in the middle of its cell, as thick as vanilla's. Its sides
     * are mapped by their position along x or z like every other face, with the texture shifted so the stalk drawn on it is centered on
     * the column. The top is the flat color of the top block.
     */
    private static void column(QuadWriter out, float centerX, float centerZ, float width, float bottom, float top, Paint side, Paint cap) {
        float r = width * 0.5F;
        float s = r * (float) (Math.sqrt(2.0) - 1.0);
        // The corners going round from the north-west, the order in which every side faces outwards.
        float[] xs = {-s, s, r, r, s, -s, -r, -r};
        float[] zs = {-r, -r, -s, s, r, r, s, -s};
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) & 7;
            float ax = centerX + xs[i];
            float az = centerZ + zs[i];
            float bx = centerX + xs[j];
            float bz = centerZ + zs[j];
            boolean eastWest = i == 2 || i == 6;
            float shade = i % 2 == 1 ? (SHADE_NORTH_SOUTH + SHADE_EAST_WEST) * 0.5F : eastWest ? SHADE_EAST_WEST : SHADE_NORTH_SOUTH;
            out.quad(ax, bottom, az, ax, top, az, bx, top, bz, bx, bottom, bz, side, shade, eastWest ? KIND_EAST_WEST : KIND_NORTH_SOUTH);
        }
        out.quad(centerX - s, top, centerZ - r, centerX - s, top, centerZ + r, centerX + s, top, centerZ + r, centerX + s, top, centerZ - r, cap,
            SHADE_TOP, KIND_HORIZONTAL);
        out.quad(centerX - r, top, centerZ - s, centerX - r, top, centerZ + s, centerX - s, top, centerZ + r, centerX - s, top, centerZ - r, cap,
            SHADE_TOP, KIND_HORIZONTAL);
        out.quad(centerX + s, top, centerZ - r, centerX + s, top, centerZ + r, centerX + r, top, centerZ + s, centerX + r, top, centerZ - s, cap,
            SHADE_TOP, KIND_HORIZONTAL);
    }

    /** A wall of a run between two heights: its top block looks like the side of the block on top, the rest like the side block. */
    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, int runTop, Paint edge, Paint side) {
        if (edge.equals(side)) {
            wall(out, direction, x0, x1, z0, z1, bottom, top, edge);
            return;
        }
        float split = Mth.clamp(runTop - 1, bottom, top);
        wall(out, direction, x0, x1, z0, z1, split, top, edge);
        wall(out, direction, x0, x1, z0, z1, bottom, split, side);
    }

    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, Paint paint) {
        if (bottom >= top) {
            return;
        }
        face(out, direction, x0, x1, z0, z1, bottom, top, paint, 1.0F);
        if (paint.cutout()) {
            float inset = Math.min(INNER_LAYER, Math.max(x1 - x0, z1 - z0));
            switch (direction) {
                case 0 -> face(out, direction, x0, x1, z0 + inset, z1, bottom, top, paint.closed(), INNER_SHADE);
                case 1 -> face(out, direction, x0, x1, z0, z1 - inset, bottom, top, paint.closed(), INNER_SHADE);
                case 2 -> face(out, direction, x0 + inset, x1, z0, z1, bottom, top, paint.closed(), INNER_SHADE);
                default -> face(out, direction, x0, x1 - inset, z0, z1, bottom, top, paint.closed(), INNER_SHADE);
            }
        }
    }

    /** One side face of a box, on its side in the given direction. */
    private static void face(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, Paint paint, float shade) {
        switch (direction) {
            case 0 -> out.quad(x0, bottom, z0, x0, top, z0, x1, top, z0, x1, bottom, z0, paint, SHADE_NORTH_SOUTH * shade, KIND_NORTH_SOUTH);
            case 1 -> out.quad(x0, bottom, z1, x1, bottom, z1, x1, top, z1, x0, top, z1, paint, SHADE_NORTH_SOUTH * shade, KIND_NORTH_SOUTH);
            case 2 -> out.quad(x0, bottom, z0, x0, bottom, z1, x0, top, z1, x0, top, z0, paint, SHADE_EAST_WEST * shade, KIND_EAST_WEST);
            default -> out.quad(x1, bottom, z0, x1, top, z0, x1, top, z1, x1, bottom, z1, paint, SHADE_EAST_WEST * shade, KIND_EAST_WEST);
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

    /** The different paints of one tile, so equal neighbouring faces can be found by comparing numbers. */
    private static final class Paints {
        private final List<Paint> list = new ArrayList<>();
        private final Map<Paint, Integer> indices = new HashMap<>();

        int index(Paint paint) {
            Integer index = this.indices.get(paint);
            if (index == null) {
                index = this.list.size();
                this.list.add(paint);
                this.indices.put(paint, index);
            }
            return index;
        }

        Paint get(int index) {
            return this.list.get(index);
        }
    }

    /**
     * Growable vertex buffer, four vertices per quad. Flat colors: position and color. Textured: position, color, the corner of the
     * sprite in the atlas and its size in texels; the shader repeats the sprite over every block of a face.
     */
    private static final class QuadWriter {
        private final boolean textured;
        private final int vertexSize;
        private ByteBuffer buffer;
        int quads;

        /**
         * The growing buffer quads are written to, kept per mesh builder thread: building every tile in a fresh one that doubles a few
         * times would leave megabytes of native memory behind per tile until the garbage collector frees it.
         */
        private static final ThreadLocal<ByteBuffer> SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder())
        );

        QuadWriter(boolean textured) {
            this.textured = textured;
            this.vertexSize = textured ? TEXTURED_VERTEX_SIZE : TileMeshBuilder.VERTEX_SIZE;
            this.buffer = SCRATCH.get();
            this.buffer.clear();
        }

        void quad(
            float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, Paint paint, float shade,
            int kind
        ) {
            if (this.buffer.remaining() < 4 * this.vertexSize) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(this.buffer.capacity() * 2).order(ByteOrder.nativeOrder());
                this.buffer.flip();
                bigger.put(this.buffer);
                this.buffer = bigger;
                SCRATCH.set(bigger);
            }
            int color = ARGB.scaleRGB(paint.color(), shade);
            int alpha = 255 - (kind | (paint.cutout() ? CUTOUT : 0) | (paint.water() ? TRANSLUCENT_WATER : 0));
            this.vertex(ax, ay, az, color, alpha, paint);
            this.vertex(bx, by, bz, color, alpha, paint);
            this.vertex(cx, cy, cz, color, alpha, paint);
            this.vertex(dx, dy, dz, color, alpha, paint);
            this.quads++;
        }

        private void vertex(float x, float y, float z, int rgb, int alpha, Paint paint) {
            TextureAtlasSprite sprite = paint.sprite();
            if (!this.textured) {
                this.buffer.putFloat(x).putFloat(y).putFloat(z);
                this.buffer.put((byte) ARGB.red(rgb)).put((byte) ARGB.green(rgb)).put((byte) ARGB.blue(rgb)).put((byte) 255);
                return;
            }
            // A flat color has the sprite size 0, which the shader recognizes. Sprites are square (one frame of an animated one), so one
            // size serves both directions.
            this.buffer.putShort((short) Math.round(x * XZ_UNITS)).putShort((short) Math.round(y * Y_UNITS)).putShort((short) Math.round(z * XZ_UNITS))
                .putShort((short) (sprite == null ? 0 : sprite.contents().width()));
            this.buffer.put((byte) ARGB.red(rgb)).put((byte) ARGB.green(rgb)).put((byte) ARGB.blue(rgb)).put((byte) alpha);
            if (sprite == null) {
                this.buffer.putShort((short) 0).putShort((short) 0);
            } else {
                // The corner comes from the texture coordinates: getX and getY include the padding around the sprite in the atlas.
                double atlasWidth = sprite.contents().width() / (double) (sprite.getU1() - sprite.getU0());
                double atlasHeight = sprite.contents().height() / (double) (sprite.getV1() - sprite.getV0());
                this.buffer.putShort((short) Math.round((sprite.getU0() + paint.uShift()) * atlasWidth * 2.0))
                    .putShort((short) Math.round(sprite.getV0() * atlasHeight * 2.0));
            }
        }

        /** The quads written, in a buffer of their own: the scratch buffer is used again by the next tile. */
        ByteBuffer finish() {
            this.buffer.flip();
            ByteBuffer result = ByteBuffer.allocateDirect(this.buffer.remaining()).order(ByteOrder.nativeOrder());
            result.put(this.buffer);
            result.flip();
            return result;
        }
    }
}
