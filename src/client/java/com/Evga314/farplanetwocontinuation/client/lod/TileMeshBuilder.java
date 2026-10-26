package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link TileData} into a POSITION_COLOR vertex buffer: a 33x33 height grid plus a skirt around the border that hides cracks
 * between tiles of different detail. Positions are relative to the tile's minimum corner at y = 0.
 * <p>
 * The borders are shaped on a bigger grid: the tile with a margin of the samples of its neighbours around it, as far as they are
 * loaded (see {@link #grid}). Both tiles of a shared edge then shape it from the same samples and agree on it, instead of each
 * keeping the edge as it is, which showed as a line along every tile edge.
 */
public final class TileMeshBuilder {
    /** Samples along the edge of the drawn tile, and its cells. */
    private static final int OUT = FarPlaneConstants.TILE_SAMPLES;
    private static final int OUT_C = FarPlaneConstants.TILE_CELLS;
    /**
     * Rows of the neighbours' samples around the tile the borders are shaped with: as far as shaping a border reaches, so the shared
     * edge comes out the same in both tiles. Even, so the diagonals of the cells alternate the same way in the grid and the tile.
     */
    private static final int MARGIN = 6;
    /** Samples along the edge of the grid the borders are shaped on, its cells and all its samples. */
    private static final int S = OUT + 2 * MARGIN;
    private static final int C = S - 1;
    private static final int GRID_VERTICES = S * S;
    public static final int VERTEX_COUNT = OUT * OUT + 4 * OUT;
    public static final int INDEX_COUNT = OUT_C * OUT_C * 6 + 4 * OUT_C * 6;
    /** The indices of the surface come first, then those of the skirts, see {@link #buildIndices}. */
    public static final int SURFACE_INDEX_COUNT = OUT_C * OUT_C * 6;
    /**
     * Floating islands (the End) have no skirts but an underside: their surface, then a second grid under it drawn facing down, see
     * {@link #underside}. Their indices follow the others in the shared list.
     */
    public static final int FLOATING_INDEX_COUNT = 2 * SURFACE_INDEX_COUNT;
    public static final int FLOATING_FIRST_INDEX = 2 * INDEX_COUNT;
    /** How much deeper the underside of an island lies per block from its edge, and at most: a cone like vanilla's islands. */
    private static final float UNDERSIDE_SLOPE = 0.6F;
    private static final float UNDERSIDE_MAX_DEPTH = 48.0F;
    /** The underside is in shadow, like the bottom of a block. */
    private static final float UNDERSIDE_SHADE = 0.5F;
    /** Island samples closer to the edge than this many cells are lifted out of dips, see {@link #liftRims}. */
    private static final float RIM_CELLS = 2.0F;

    /** Heights come from the server, keep them in a sane range so a bad tile can only look wrong locally. */
    private static final int MIN_HEIGHT = -4096;
    private static final int MAX_HEIGHT = 8192;
    /**
     * Tree tops are stored in the alpha of a vertex as 255 minus their height in quarter blocks, so the shader can lay them flat inside
     * the detailed terrain and vanilla's chunks. Alpha 0 stays the mark of the void.
     */
    private static final float CANOPY_UNITS = 4.0F;
    private static final float MAX_CANOPY = 30.0F;
    /** Vanilla's shades of block faces, the same ones detailed tiles use. */
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_NORTH_SOUTH = 0.8F;
    private static final float SHADE_EAST_WEST = 0.6F;
    /**
     * The baked sun stands in the south-east: a slope catches its light when its height falls towards it, against the gradient,
     * which the two negative parts express. First values, to be judged in game.
     */
    private static final float SUN_X = -0.7F;
    private static final float SUN_Z = -0.7F;
    private static final float SUN_STRENGTH = 0.35F;
    /** How much ground under the average height of its surroundings darkens, per block of difference. */
    private static final float HOLLOW_STRENGTH = 0.5F;

    private TileMeshBuilder() {
    }

    /**
     * @param floating islands over the void (the End): the skirts that close the gaps between tiles of different detail would hang
     *                 under every island edge like curtains there, so they are left out
     */
    /**
     * The samples of a tile with a margin of its neighbours' around it, see {@link #grid}. Biomes are indices into {@link #palette},
     * which holds those of the neighbours too.
     */
    public static final class Grid {
        final short[] heights = new short[GRID_VERTICES];
        final byte[] biomes = new byte[GRID_VERTICES];
        final byte[] flags = new byte[GRID_VERTICES];
        final byte @Nullable [] subBiomes;
        final short @Nullable [] subWater;
        /** See {@link TileData#waterDepths}; 0 where not known. */
        final byte[] waterDepths = new byte[GRID_VERTICES];
        final List<@Nullable Holder<Biome>> palette;
        /** Bit {@code dx + 1 + (dz + 1) * 3} is set for every neighbour whose samples are in the margin. */
        public final int neighbours;

        private Grid(boolean coarse, List<@Nullable Holder<Biome>> palette, int neighbours) {
            this.subBiomes = coarse ? new byte[GRID_VERTICES * TileData.SUB_SAMPLES] : null;
            this.subWater = coarse ? new short[GRID_VERTICES] : null;
            this.palette = palette;
            this.neighbours = neighbours;
        }
    }

    /**
     * Puts a tile and its neighbours of the same level together, see {@link Grid}: {@code around} holds them at
     * {@code dx + 1 + (dz + 1) * 3}, null where one is not loaded. Where one is missing, or does not fit (another kind of samples, too
     * many biomes together), the margin repeats the tile's own edge, and that edge is shaped as before.
     */
    public static Grid grid(TileData center, @Nullable TileData[] around, Registry<Biome> registry) {
        boolean coarse = center.subBiomes != null;
        List<Identifier> ids = new java.util.ArrayList<>(center.palette);
        java.util.Map<Identifier, Integer> known = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            known.putIfAbsent(ids.get(i), i);
        }
        int[][] remap = new int[9][];
        remap[4] = new int[center.palette.size()];
        for (int i = 0; i < remap[4].length; i++) {
            remap[4][i] = i;
        }
        int neighbours = 0;
        for (int d = 0; d < 9; d++) {
            TileData other = around[d];
            if (d == 4 || other == null || (other.subBiomes != null) != coarse) {
                continue;
            }
            int before = ids.size();
            int[] map = new int[other.palette.size()];
            boolean fits = true;
            for (int i = 0; i < map.length; i++) {
                Identifier id = other.palette.get(i);
                Integer index = known.get(id);
                if (index == null) {
                    if (ids.size() >= TileData.MAX_PALETTE) {
                        fits = false;
                        break;
                    }
                    index = ids.size();
                    ids.add(id);
                    known.put(id, index);
                }
                map[i] = index;
            }
            if (!fits) {
                while (ids.size() > before) {
                    known.remove(ids.removeLast());
                }
                continue;
            }
            remap[d] = map;
            neighbours |= 1 << d;
        }
        Grid grid = new Grid(coarse, java.util.Arrays.asList(GeneralMeshFactory.resolveBiomes(registry, ids)), neighbours);
        for (int gz = 0; gz < S; gz++) {
            for (int gx = 0; gx < S; gx++) {
                int x = gx - MARGIN;
                int z = gz - MARGIN;
                int dx = x < 0 ? -1 : x > OUT_C ? 1 : 0;
                int dz = z < 0 ? -1 : z > OUT_C ? 1 : 0;
                int d = dx + 1 + (dz + 1) * 3;
                TileData source;
                int[] map = remap[d];
                if (map != null) {
                    source = d == 4 ? center : around[d];
                    // Neighbours share their edge samples: sample 0 of the next tile is sample OUT_C of this one.
                    x -= dx * OUT_C;
                    z -= dz * OUT_C;
                } else {
                    source = center;
                    map = remap[4];
                    x = Mth.clamp(x, 0, OUT_C);
                    z = Mth.clamp(z, 0, OUT_C);
                }
                int from = x + z * OUT;
                int to = gx + gz * S;
                grid.heights[to] = source.heights[from];
                grid.biomes[to] = (byte) map[source.biomes[from] & 0xFF];
                grid.flags[to] = source.flags[from];
                if (source.waterDepths != null) {
                    grid.waterDepths[to] = source.waterDepths[from];
                }
                if (coarse) {
                    grid.subWater[to] = source.subWater[from];
                    for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                        grid.subBiomes[to * TileData.SUB_SAMPLES + i] = (byte) map[source.subBiomes[from * TileData.SUB_SAMPLES + i] & 0xFF];
                    }
                }
            }
        }
        return grid;
    }

    public static GeneralMeshFactory.Built build(long key, int epoch, Grid data, int seaLevel, boolean floating, GeneralMeshFactory.Smoothing smoothing) {
        try {
            return buildOnGrid(key, epoch, data, seaLevel, floating, smoothing);
        } finally {
            Scratch.releaseAll();
        }
    }

    /**
     * The arrays of samples a mesh is built with, kept per mesh builder thread: building a tile takes about a hundred of them, and
     * making them new every time would keep the garbage collector busy. Each is handed out cleared, as a new one would be, and all are
     * given back once the tile is built; none of them leaves {@link #build}.
     */
    private static final class Scratch {
        private static final ThreadLocal<Scratch> CURRENT = ThreadLocal.withInitial(Scratch::new);

        private final java.util.ArrayDeque<boolean[]> freeBooleans = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<int[]> freeInts = new java.util.ArrayDeque<>();
        private final java.util.ArrayDeque<float[]> freeFloats = new java.util.ArrayDeque<>();
        private final java.util.ArrayList<boolean[]> lentBooleans = new java.util.ArrayList<>();
        private final java.util.ArrayList<int[]> lentInts = new java.util.ArrayList<>();
        private final java.util.ArrayList<float[]> lentFloats = new java.util.ArrayList<>();

        static boolean[] booleans() {
            Scratch scratch = CURRENT.get();
            boolean[] array = scratch.freeBooleans.poll();
            if (array == null) {
                array = new boolean[GRID_VERTICES];
            } else {
                java.util.Arrays.fill(array, false);
            }
            scratch.lentBooleans.add(array);
            return array;
        }

        static int[] ints() {
            Scratch scratch = CURRENT.get();
            int[] array = scratch.freeInts.poll();
            if (array == null) {
                array = new int[GRID_VERTICES];
            } else {
                java.util.Arrays.fill(array, 0);
            }
            scratch.lentInts.add(array);
            return array;
        }

        static float[] floats() {
            Scratch scratch = CURRENT.get();
            float[] array = scratch.freeFloats.poll();
            if (array == null) {
                array = new float[GRID_VERTICES];
            } else {
                java.util.Arrays.fill(array, 0.0F);
            }
            scratch.lentFloats.add(array);
            return array;
        }

        static void releaseAll() {
            Scratch scratch = CURRENT.get();
            scratch.freeBooleans.addAll(scratch.lentBooleans);
            scratch.freeInts.addAll(scratch.lentInts);
            scratch.freeFloats.addAll(scratch.lentFloats);
            scratch.lentBooleans.clear();
            scratch.lentInts.clear();
            scratch.lentFloats.clear();
        }
    }

    private static GeneralMeshFactory.Built buildOnGrid(long key, int epoch, Grid data, int seaLevel, boolean floating, GeneralMeshFactory.Smoothing smoothing) {
        List<@Nullable Holder<Biome>> biomes = data.palette;
        TilePos pos = TilePos.unpack(key);
        int spacing = 1 << pos.level();
        long tileX = pos.minX();
        long tileZ = pos.minZ();
        // Everything below works on the grid with the margin, whose first sample lies that many samples before the tile's.
        long baseX = tileX - (long) MARGIN * spacing;
        long baseZ = tileZ - (long) MARGIN * spacing;
        // Between the islands the samples take the height of the island beside them, so the surface runs level to the island edge
        // instead of hanging in sheets to the bottom of the world, which the edge cut then slices into teeth. The tile keeps
        // reporting the real heights: how high the surface is under the camera is read from them.
        short[] realHeights = null;
        if (floating) {
            realHeights = data.heights.clone();
            fillVoidHeights(data);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect((floating ? VERTEX_COUNT + OUT * OUT : VERTEX_COUNT) * GeneralMeshFactory.ROUGH_VERTEX_SIZE).order(ByteOrder.nativeOrder());
        // Every sample has a land color and a water color, and a value telling how far it is from the coast; the shader draws a sharp
        // coast where that value crosses zero, instead of blending land into water over a whole cell. See shapeCoasts.
        int[] colors = Scratch.ints();
        int[] waterColors = Scratch.ints();
        float[] wet = Scratch.floats();
        boolean[] hasLand = Scratch.booleans();
        int[] alphas = Scratch.ints();
        // Surface heights with the tree tops on them.
        float[] tops = Scratch.floats();
        // What the snow line needs to paint a sample again, see shapeSnow.
        int[] grounds = Scratch.ints();
        float[] slopes = Scratch.floats();
        float[] sideShares = Scratch.floats();
        float[] shades = Scratch.floats();
        boolean[] bare = Scratch.booleans();
        // How high the tree tops would stand at each sample, also where they stay below the edge of the forest; see shapeForest.
        float[] canopies = Scratch.floats();
        // Biomes with few trees standing apart (savannas, swamps), whose patches stay blended; see shapeForest.
        boolean[] sparse = Scratch.booleans();
        // Share of frozen water at each sample and the color of its open water, see smoothIce.
        float[] iceShares = Scratch.floats();
        int[] openWaters = Scratch.ints();
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        // The heights evened out over the samples around each one, for the shade of hollows and ridges; null keeps the old shade.
        float[] blurred = smoothing.shading() > 0 ? blurredHeights(data) : null;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                float height = height(data, index);

                float dx = (sample(data, x + 1, z) - sample(data, x - 1, z)) / (2.0F * spacing);
                float dz = (sample(data, x, z + 1) - sample(data, x, z - 1)) / (2.0F * spacing);
                float slope = Mth.sqrt(dx * dx + dz * dz);
                int flags = data.flags[index];
                alphas[index] = (flags & TileData.FLAG_VOID) != 0 ? 0 : 255;
                boolean water = (flags & TileData.FLAG_WATER) != 0;
                boolean lava = (flags & TileData.FLAG_LAVA) != 0;
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                int worldX = (int) Mth.clamp(baseX + (long) x * spacing, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                int worldZ = (int) Mth.clamp(baseZ + (long) z * spacing, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                // Shaded like vanilla's blocks: a slope is a staircase of block tops and sides, and every side gets vanilla's shade of
                // its direction, so the distant terrain matches the blocks next to it.
                float stepsX = Math.abs(dx);
                float stepsZ = Math.abs(dz);
                float area = 1.0F + stepsX + stepsZ;
                float sides = water ? 0.0F : (stepsX + stepsZ) / area;
                float landShade = (SHADE_TOP + stepsZ * SHADE_NORTH_SOUTH + stepsX * SHADE_EAST_WEST) / area;
                if (blurred != null) {
                    // A fixed sun and the darkening of hollows over the baked colors. Flat ground, and ground at the average
                    // height of its surroundings, keep the shade above exactly, so the border to vanilla does not move.
                    float sun = (dx * SUN_X + dz * SUN_Z) / area;
                    float hollow = Mth.clamp(1.0F + HOLLOW_STRENGTH * (height - blurred[index]) / spacing, 0.82F, 1.05F);
                    landShade *= Mth.clamp((1.0F + SUN_STRENGTH * sun) * hollow, 0.8F, 1.1F);
                }
                grounds[index] = (int) height;
                slopes[index] = slope;
                sideShares[index] = sides;
                shades[index] = landShade;
                if (data.subBiomes != null) {
                    averagedColors(data, index, biomes, worldX, (int) height, worldZ, slope, sides, landShade, seaLevel, scratch, colors, waterColors, wet, iceShares, openWaters);
                    hasLand[index] = wet[index] < 1.0F;
                } else {
                    int waterColor = TileColors.color(biome, worldX, (int) height, worldZ, true, false, 0.0F, 0.0F, seaLevel, scratch);
                    waterColors[index] = ARGB.scaleRGB(waterColor, GeneralMeshFactory.WATER_SHADE);
                    iceShares[index] = TileColors.isIcy(biome, worldX, (int) height, worldZ, seaLevel, scratch) ? 1.0F : 0.0F;
                    openWaters[index] = ARGB.scaleRGB(TileColors.waterColor(biome), GeneralMeshFactory.WATER_SHADE);
                    if (water) {
                        wet[index] = 1.0F;
                    } else {
                        int color = (flags & TileData.FLAG_ICEBERG) != 0
                            ? TileColors.icebergColor(biome, worldX, (int) height, worldZ, sides, flags)
                            : TileColors.color(biome, worldX, (int) height, worldZ, false, lava, slope, sides, seaLevel, scratch);
                        colors[index] = ARGB.scaleRGB(color, lava ? GeneralMeshFactory.WATER_SHADE : landShade);
                        hasLand[index] = true;
                    }
                }
                Canopy.Kind trees = lava || alphas[index] == 0 || slope >= TREES_GONE_SLOPE || biome == null ? null : Canopy.of(biome);
                if (trees != null && (!water || trees.overWater())) {
                    // Trees thin out on steeper ground and are gone on cliffs: gradually, as a hard limit would make every slope near
                    // it a row of tree tops and bare samples by turns, drawn as zigzags.
                    float thinning = 1.0F - smoothstep(TREES_THIN_SLOPE, TREES_GONE_SLOPE, slope);
                    float canopy = Canopy.height(trees, worldX, worldZ, spacing) * thinning;
                    canopies[index] = canopy;
                    sparse[index] = trees.density() < SPARSE_TREES;
                    if (canopy > FOREST_EDGE) {
                        boolean snowy = TileColors.coldEnoughToSnow(biome.value(), worldX, (int) (height + canopy), worldZ, seaLevel, scratch);
                        int leaves = ARGB.scaleRGB(Canopy.color(trees, biome, worldX, (int) (height + canopy), worldZ, snowy, spacing), landShade);
                        // Coarse samples stand for trees and the ground between them. Mangroves over water turn it into tree tops.
                        colors[index] = spacing >= 16 && hasLand[index] ? ARGB.srgbLerp(trees.density(), colors[index], leaves) : leaves;
                        hasLand[index] = true;
                        wet[index] = 0.0F;
                        canopy = Math.min(canopy, MAX_CANOPY);
                        height += canopy;
                        alphas[index] = 255 - Math.round(canopy * CANOPY_UNITS);
                    }
                }
                // Vanilla draws the top of water 1/9 block under the block's top; rough water lies a little under it, so where both are
                // drawn the rough one never covers vanilla's.
                tops[index] = water && !hasLand[index] ? height - WATER_UNDER_VANILLA : height;
                bare[index] = hasLand[index] && !water && !lava && alphas[index] == 255 && biome != null && (flags & TileData.FLAG_ICEBERG) == 0;
                minY = Math.min(minY, height);
                maxY = Math.max(maxY, height);
            }
        }
        boolean[] icy = smoothIce(data, biomes, baseX, baseZ, spacing, alphas, iceShares, openWaters, waterColors);
        landBesideWater(colors, waterColors, hasLand);
        short[] heights = data.heights.clone();
        boolean[] coastal = Scratch.booleans();
        float[] coast = shapeCoasts(data, tops, heights, hasLand, alphas, wet, spacing, seaLevel, coastal, smoothing.coasts());
        if (floating) {
            // There are no coasts between the islands: the value carries the signed distance to the island edge instead, and the
            // shader cuts the surface where it crosses zero, see its FLOATING branches.
            floatingDistances(alphas, coast);
            liftRims(tops, alphas, coast);
        }
        // The land color and the second one are the coast's. The other borders are drawn with a color and a value of their own: the
        // snow line, forest edges and biomes on the land color, waters of different temperatures on the second one, so both run on
        // across coasts. See putVertex.
        int[] thirdColors = colors.clone();
        float[] thirdValues = Scratch.floats();
        java.util.Arrays.fill(thirdValues, NO_SPLIT);
        int[] softness = Scratch.ints();
        boolean[] none = Scratch.booleans();
        boolean[] waterSamples = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            waterSamples[index] = !hasLand[index] && alphas[index] != 0;
        }
        boolean[] snowLine = DebugToggles.noSnowLine ? Scratch.booleans() : shapeSnow(data, biomes, grounds, slopes, sideShares, shades, bare, none, waterSamples, colors, thirdColors, thirdValues, softness, baseX, baseZ, spacing, seaLevel, scratch, smoothing.snow(), smoothing.snowBlur());
        boolean[] forestEdge = DebugToggles.noForestEdges ? Scratch.booleans() : shapeForest(canopies, sparse, alphas, hasLand, none, snowLine, waterSamples, colors, thirdColors, thirdValues, softness, smoothing.forests(), smoothing.forestsBlur());
        boolean[] biomeBorder = DebugToggles.noBiomeBorders ? Scratch.booleans() : shapeBiomes(data, bare, none, snowLine, forestEdge, waterSamples, colors, thirdColors, thirdValues, softness, smoothing.biomes(), smoothing.biomesBlur());
        boolean[] landBorder = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            landBorder[index] = snowLine[index] || forestEdge[index] || biomeBorder[index];
        }
        int[] waterThird = waterColors.clone();
        float[] waterSplit = Scratch.floats();
        java.util.Arrays.fill(waterSplit, NO_SPLIT);
        boolean[] waterBorder = shapeWaters(data, hasLand, alphas, coastal, icy, waterColors, waterThird, waterSplit, smoothing.water());
        mergeCoasts(hasLand, alphas, coastal, landBorder, waterBorder, colors, waterColors, waterThird, waterSplit, thirdColors, thirdValues, softness);
        if (DebugToggles.tileEdges) {
            for (int z = 0; z < OUT; z++) {
                for (int x = 0; x < OUT; x++) {
                    if (x == 0 || z == 0 || x == OUT_C || z == OUT_C) {
                        int index = inGrid(x, z);
                        colors[index] = DEBUG_SKIRT;
                        waterColors[index] = DEBUG_SKIRT;
                        thirdColors[index] = DEBUG_SKIRT;
                        waterThird[index] = DEBUG_SKIRT;
                    }
                }
            }
        }
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        short[] tileHeights = new short[OUT * OUT];
        for (int z = 0; z < OUT; z++) {
            for (int x = 0; x < OUT; x++) {
                int index = inGrid(x, z);
                minY = Math.min(minY, tops[index]);
                maxY = Math.max(maxY, tops[index]);
                tileHeights[x + z * OUT] = realHeights != null ? realHeights[index] : heights[index];
                putVertex(buffer, GeneralMeshFactory.localCoordinate(tileX, x, spacing), tops[index], GeneralMeshFactory.localCoordinate(tileZ, z, spacing), colors[index], alphas[index],
                    softness[index], waterColors[index], coast[index], thirdColors[index], thirdValues[index], waterThird[index], waterSplit[index]);
            }
        }

        // A coarser tile next to this one runs straight between fewer samples along the shared edge, and a coarse tile's samples are
        // averages of the ground around them, so the two edges can be far apart where the ground is steep. The skirt reaches down to
        // the lowest ground along the edge around each sample and a little past it, so it closes the gap however high it is, but no
        // deeper: from under the ground every block of it would show.
        float baseDepth = floating ? 0.0F : Mth.clamp(spacing, SKIRT_MIN_ROOM, SKIRT_MAX_ROOM);
        float deepest = baseDepth;
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < OUT; i++) {
                int x = edgeX(edge, i);
                int z = edgeZ(edge, i);
                int index = inGrid(x, z);
                float depth = baseDepth;
                if (!floating) {
                    float lowest = tops[index];
                    for (int j = Math.max(0, i - SKIRT_REACH); j <= Math.min(OUT_C, i + SKIRT_REACH); j++) {
                        lowest = Math.min(lowest, tops[inGrid(edgeX(edge, j), edgeZ(edge, j))]);
                    }
                    depth = Math.min(baseDepth + tops[index] - lowest, MAX_SKIRT_DEPTH);
                }
                deepest = Math.max(deepest, depth);
                // At a coast skirts are never water: they take the land color, which water samples there carry from the land beside
                // them; open water has no land around and keeps its own color. They show the side of the other borders the sample is on.
                int land = thirdValues[index] > 0.0F ? thirdColors[index] : colors[index];
                int water = waterSplit[index] > 0.0F ? waterThird[index] : waterColors[index];
                boolean openWater = !coastal[index] && coast[index] > 0.0F;
                int skirtColor = openWater ? water : land;
                // The coast value of the bottom is the top's side: under open water a land value would turn the lower half of the skirt
                // into land for the shader, drawn with depth where only water is left to the pass under the detailed tiles, and it
                // would flicker against their seabed.
                putVertex(buffer, GeneralMeshFactory.localCoordinate(tileX, x, spacing), tops[index] - depth, GeneralMeshFactory.localCoordinate(tileZ, z, spacing), skirtColor,
                    floating ? 0 : alphas[index], 0, skirtColor, openWater ? 1.0F : -1.0F, skirtColor, NO_SPLIT, skirtColor, NO_SPLIT);
            }
        }
        if (floating) {
            deepest = Math.max(deepest, underside(buffer, tileX, tileZ, spacing, tops, colors, alphas, coast));
        }
        buffer.flip();
        int indexCount = floating ? FLOATING_INDEX_COUNT : INDEX_COUNT;
        return new GeneralMeshFactory.Built(key, epoch, buffer, minY - deepest, maxY, minY, tileHeights, indexCount, false, 0L, true, null, null, false, false, null, false);
    }

    /** The sample of the grid with the margin at a sample of the tile. */
    private static int inGrid(int x, int z) {
        return x + MARGIN + (z + MARGIN) * S;
    }

    /**
     * The sub-samples of a coarse cell: the average color of its land, of its water, and the share of water, so a cell that is 30%
     * islands still has its coast where the islands are, not a slightly greener sea.
     */
    private static void averagedColors(
        Grid data, int index, List<@Nullable Holder<Biome>> biomes, int x, int height, int z, float slope, float sides, float landShade,
        int seaLevel, BlockPos.MutableBlockPos scratch, int[] colors, int[] waterColors, float[] wet, float[] iceShares, int[] openWaters
    ) {
        int mask = data.subWater[index];
        // Sums of red, green and blue of the land, the water and the open (unfrozen) water of the sub-samples.
        int landRed = 0;
        int landGreen = 0;
        int landBlue = 0;
        int waterRed = 0;
        int waterGreen = 0;
        int waterBlue = 0;
        int openRed = 0;
        int openGreen = 0;
        int openBlue = 0;
        int waterCount = 0;
        int icyCount = 0;
        for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
            boolean isWater = (mask & 1 << i) != 0;
            Holder<Biome> biome = biomes.get(data.subBiomes[index * TileData.SUB_SAMPLES + i] & 0xFF);
            boolean frozenOcean = (data.flags[index] & TileData.FLAG_ICEBERG) != 0 && biome != null && TileColors.isFrozenOcean(biome);
            int color = !isWater && frozenOcean
                ? TileColors.icebergColor(biome, x, height, z, sides, 0)
                : TileColors.color(biome, x, height, z, isWater, false, isWater ? 0.0F : slope, isWater ? 0.0F : sides, seaLevel, scratch);
            color = ARGB.scaleRGB(color, isWater ? GeneralMeshFactory.WATER_SHADE : landShade);
            if (!isWater) {
                landRed += ARGB.red(color);
                landGreen += ARGB.green(color);
                landBlue += ARGB.blue(color);
                continue;
            }
            waterRed += ARGB.red(color);
            waterGreen += ARGB.green(color);
            waterBlue += ARGB.blue(color);
            waterCount++;
            if (TileColors.isIcy(biome, x, height, z, seaLevel, scratch)) {
                icyCount++;
            } else {
                openRed += ARGB.red(color);
                openGreen += ARGB.green(color);
                openBlue += ARGB.blue(color);
            }
        }
        int openCount = waterCount - icyCount;
        Holder<Biome> middle = biomes.get(data.subBiomes[index * TileData.SUB_SAMPLES + 4] & 0xFF);
        openWaters[index] = openCount > 0
            ? ARGB.color(255, openRed / openCount, openGreen / openCount, openBlue / openCount)
            : ARGB.scaleRGB(TileColors.waterColor(middle), GeneralMeshFactory.WATER_SHADE);
        iceShares[index] = waterCount > 0
            ? icyCount / (float) waterCount
            : TileColors.isIcy(middle, x, height, z, seaLevel, scratch) ? 1.0F : 0.0F;
        int landCount = TileData.SUB_SAMPLES - waterCount;
        if (landCount > 0) {
            colors[index] = ARGB.color(255, landRed / landCount, landGreen / landCount, landBlue / landCount);
        }
        if (waterCount > 0) {
            waterColors[index] = ARGB.color(255, waterRed / waterCount, waterGreen / waterCount, waterBlue / waterCount);
        } else {
            Holder<Biome> biome = biomes.get(data.subBiomes[index * TileData.SUB_SAMPLES + 4] & 0xFF);
            waterColors[index] = ARGB.scaleRGB(TileColors.color(biome, x, height, z, true, false, 0.0F, 0.0F, seaLevel, scratch), GeneralMeshFactory.WATER_SHADE);
        }
        wet[index] = waterCount / (float) TileData.SUB_SAMPLES;
    }

    /**
     * Frozen water as whole sheets of ice: whether water is frozen comes from the temperature of each sample's biome, and along the
     * ragged borders of frozen oceans that flips from sample to sample, a speckle of ice and water. The share of ice is softened
     * with the neighbours, and each sample's water is then either all ice or all open water, so the border between them is drawn
     * as a smooth line like those between other waters (see shapeWaters). The samples on the tile edges keep their own share, as
     * the next tile does.
     */
    private static boolean[] smoothIce(
        Grid data, List<@Nullable Holder<Biome>> biomes, long baseX, long baseZ, int spacing, int[] alphas, float[] iceShares,
        int[] openWaters, int[] waterColors
    ) {
        boolean[] frozen = Scratch.booleans();
        boolean any = false;
        boolean[] present = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            present[index] = alphas[index] != 0;
            any |= iceShares[index] > 0.0F;
        }
        if (!any) {
            return frozen;
        }
        float[] smoothed = iceShares.clone();
        smooth(smoothed, present, ICE_PASSES);
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                boolean icy = smoothed[index] > 0.5F;
                frozen[index] = icy;
                boolean was = iceShares[index] > 0.5F;
                boolean mixed = iceShares[index] > 0.0F && iceShares[index] < 1.0F;
                if (!present[index] || icy == was && !mixed) {
                    continue;
                }
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                waterColors[index] = icy
                    ? ARGB.scaleRGB(TileColors.iceColor(biome, worldCoordinate(baseX, x, spacing), 0, worldCoordinate(baseZ, z, spacing)), GeneralMeshFactory.WATER_SHADE)
                    : openWaters[index];
            }
        }
        return frozen;
    }

    /** How many times the share of ice is softened with the neighbours, see {@link #smoothIce}. */
    private static final int ICE_PASSES = 2;

    /**
     * Shapes the coasts: returns the coast value of every sample, above zero over water and below over land, which the shader draws
     * the coast at where it crosses zero, and moves the ground next to the coast so that it reaches the level of the water exactly
     * there. The coast is so always at one height, and its shape can still be smoothed.
     * <p>
     * The value of a sample is how far it lies under the level of the water nearby, in units of {@code reach} (half a cell, within
     * bounds): water next to land is lowered by up to that much, and land is negative by how far it rises above the water. Along a
     * triangle both the value and the height then change the same way, so where the value crosses zero the ground crosses the level
     * of the water. Coarse tiles have nine sub-samples in every cell, a grid three times finer than the samples: there the value starts
     * as the distance to the nearest sub-sample of the other kind, in cells, so the coast follows that finer grid.
     * <p>
     * The coast is smoothed the way the other borders are, which straightens its steps and rounds off its corners and spikes, and the
     * values move with it. Each value is kept to what its sample allows: water may lie from a little to the full reach under the level,
     * which does not show, as the water is flat in color; land may move only a few blocks up or down, so steep shores keep their shape
     * and only flat ones are reshaped. Land never turns into water or back, so islands and narrow rivers stay. At the end the ground
     * is moved to the values. The samples on the tile edges are shared with the next tile and are left as they are, so the coast goes
     * on across the edge.
     */
    private static float[] shapeCoasts(
        Grid data, float[] tops, short[] heights, boolean[] hasLand, int[] alphas, float[] wet, int spacing, int seaLevel,
        boolean[] coastal, int smoothing
    ) {
        boolean[] present = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            present[index] = alphas[index] != 0;
        }
        float radius = smoothingRadius(smoothing);
        float[] raw = Scratch.floats();
        float[] shaped = null;
        if (data.subWater == null) {
            boolean[] wetSamples = Scratch.booleans();
            for (int index = 0; index < GRID_VERTICES; index++) {
                wetSamples[index] = present[index] && !hasLand[index];
            }
            shaped = shapedDistances(present, wetSamples, Scratch.ints(), radius, raw, Scratch.ints());
            boolean[] narrow = keepNarrow(present, wetSamples, raw, shaped);
            softenRestored(shaped, narrow);
            if (!DebugToggles.noCoastSmoothing) {
                cutCoasts(tops, heights, hasLand, present, narrow, spacing, raw, shaped);
            }
        }
        float reach = Mth.clamp(spacing * 0.5F, COAST_DIP_MIN, COAST_DIP_MAX);
        float give = Mth.clamp(spacing / 8.0F, COAST_GIVE_MIN, COAST_GIVE_MAX) / reach;
        float[] levels = Scratch.floats();
        float[] low = Scratch.floats();
        float[] high = Scratch.floats();
        boolean[] water = Scratch.booleans();
        float[] field = Scratch.floats();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                float level = Float.NaN;
                float start = -1.0F;
                if (alphas[index] == 0) {
                    // The void: nothing to shape.
                } else if (data.subWater != null) {
                    // Coarse water lies at the sea level. Tree tops over water (mangroves) are land.
                    boolean canopy = hasLand[index] && wet[index] == 0.0F;
                    start = canopy ? -1.0F : subCoast(data.subWater, x, z);
                    level = seaLevel;
                } else if (!hasLand[index]) {
                    start = 1.0F;
                    level = tops[index];
                } else {
                    float highest = -Float.MAX_VALUE;
                    for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                            int neighbour = nx + nz * S;
                            if (!hasLand[neighbour] && alphas[neighbour] != 0) {
                                highest = Math.max(highest, tops[neighbour]);
                            }
                        }
                    }
                    if (highest != -Float.MAX_VALUE) {
                        level = highest;
                        start = (level - tops[index]) / reach;
                    }
                }
                levels[index] = level;
                water[index] = start > 0.0F;
                if (Float.isNaN(level)) {
                    low[index] = start;
                    high[index] = start;
                } else if (water[index]) {
                    low[index] = COAST_WATER_MIN;
                    high[index] = 1.0F;
                } else {
                    float exact = (level - tops[index]) / reach;
                    high[index] = Math.min(exact + give, -COAST_LAND_MIN);
                    low[index] = Math.min(exact - give, high[index]);
                }
                // A coarse cell with both land and water in it may end up on the other side of the smoothed coast: the coast runs through
                // it somewhere. Water may rise up to the reach over the sea, low land sink as far under it; high land keeps its height.
                if (data.subWater != null && !Float.isNaN(level) && mixed(data.subWater[index])) {
                    if (water[index]) {
                        low[index] = -1.0F;
                    } else if (low[index] >= -1.0F) {
                        high[index] = 1.0F;
                    }
                }
                field[index] = Mth.clamp(start, low[index], high[index]);
            }
        }
        if (DebugToggles.noCoastSmoothing) {
            // The debug build can leave coasts as the ground has them, see DebugToggles.
        } else if (shaped != null) {
            fitCoasts(field, low, high, levels, present, water, shaped);
        } else {
            // Coarse tiles: the coast is smoothed the way the other borders are (see shapeRegion), as distances in cells. Where that
            // moves it, the value of a sample moves by as much (two units per cell), within what the sample allows, as above.
            shaped = shapedDistances(present, water, Scratch.ints(), radius, raw, Scratch.ints());
            for (int z = 1; z < C; z++) {
                for (int x = 1; x < C; x++) {
                    int index = x + z * S;
                    if (present[index] && !Float.isNaN(levels[index])) {
                        field[index] = Mth.clamp(field[index] + 2.0F * (shaped[index] - raw[index]), low[index], high[index]);
                    }
                }
            }
            // Softened with the neighbours, so the coast runs between the samples instead of along the cells.
            boolean[] shaping = Scratch.booleans();
            for (int index = 0; index < GRID_VERTICES; index++) {
                shaping[index] = present[index] && !Float.isNaN(levels[index]);
            }
            smooth(field, shaping, COARSE_COAST_PASSES);
            for (int index = 0; index < GRID_VERTICES; index++) {
                if (shaping[index]) {
                    field[index] = Mth.clamp(field[index], low[index], high[index]);
                }
            }
        }
        // Mixed coarse cells may have changed sides.
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (!Float.isNaN(levels[index])) {
                water[index] = field[index] > 0.0F;
            }
        }
        // Only samples with the other kind next to them take part in a coast; the rest keep their height.
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (Float.isNaN(levels[index])) {
                    continue;
                }
                boolean coast = false;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1) && !coast; nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (water[neighbour] != water[index] && alphas[neighbour] != 0) {
                            coast = true;
                            break;
                        }
                    }
                }
                coastal[index] = coast;
                float top = levels[index] - field[index] * reach;
                if (coast && Math.abs(top - tops[index]) > 0.01F) {
                    heights[index] = (short) Math.round(heights[index] + top - tops[index]);
                    tops[index] = top;
                }
            }
        }
        return field;
    }

    /**
     * Draws the snow line sharp: samples around it keep the color of the bare ground and the color of snow apart, and the shader puts
     * the line where their value crosses zero. Which samples have snow comes from vanilla's temperature at their height in their biome,
     * so the line follows both the height where snow starts and the borders of snowy biomes; {@link #shapeRegion} smooths it. Near
     * coasts the coast has the second color, and snow stays blended there as before. Tree tops keep their own color. Returns the samples
     * given the two colors.
     */
    private static boolean[] shapeSnow(
        Grid data, List<@Nullable Holder<Biome>> biomes, int[] grounds, float[] slopes, float[] sideShares, float[] shades,
        boolean[] bare, boolean[] coastal, boolean[] harmless, int[] colors, int[] secondColors, float[] values, int[] softness, long baseX,
        long baseZ, int spacing, int seaLevel, BlockPos.MutableBlockPos scratch, int smoothing, int blur
    ) {
        float radius = smoothingRadius(smoothing);
        boolean[] candidate = Scratch.booleans();
        boolean[] snowy = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!bare[index] || near(coastal, x, z)) {
                    continue;
                }
                candidate[index] = true;
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                snowy[index] = TileColors.coldEnoughToSnow(
                    biome.value(), worldCoordinate(baseX, x, spacing), grounds[index], worldCoordinate(baseZ, z, spacing), seaLevel, scratch
                );
            }
        }
        boolean[] active = zone(candidate, snowy, Scratch.ints(), radius);
        int[] snowColors = Scratch.ints();
        int[] groundColors = Scratch.ints();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!active[index]) {
                    continue;
                }
                Holder<Biome> biome = biomes.get(data.biomes[index] & 0xFF);
                int worldX = worldCoordinate(baseX, x, spacing);
                int worldZ = worldCoordinate(baseZ, z, spacing);
                groundColors[index] = landColor(data, index, biomes, biome, worldX, grounds[index], worldZ, slopes[index], sideShares[index],
                    shades[index], seaLevel, false);
                snowColors[index] = landColor(data, index, biomes, biome, worldX, grounds[index], worldZ, slopes[index], sideShares[index],
                    shades[index], seaLevel, true);
            }
        }
        shapeRegion(active, snowy, Scratch.ints(), snowColors, groundColors, radius, coastal, Scratch.booleans(), harmless, coastal, false, colors, secondColors, values, softness, blur);
        return active;
    }

    /**
     * Draws the edges of forests sharp, the way the snow line is drawn, with the tree tops as the second color; the edge is a little
     * soft, as forests thin out over some way. Coasts and the snow line have the second color first: next to them forest edges stay
     * blended as before. Returns the samples given the two colors.
     */
    private static boolean[] shapeForest(
        float[] canopies, boolean[] sparse, int[] alphas, boolean[] hasLand, boolean[] coastal, boolean[] snowLine, boolean[] harmless,
        int[] colors, int[] secondColors, float[] values, int[] softness, int smoothing, int blur
    ) {
        float radius = smoothingRadius(smoothing);
        boolean[] candidate = Scratch.booleans();
        boolean[] wooded = Scratch.booleans();
        boolean[] untouchable = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                untouchable[index] = coastal[index] || snowLine[index];
                // Few trees standing apart look like a haze from far away: drawn with edges, the smoothing would merge their patches
                // into big blots. They stay blended.
                if (alphas[index] != 0 && hasLand[index] && !sparse[index] && !near(coastal, x, z) && !near(snowLine, x, z)) {
                    candidate[index] = true;
                    wooded[index] = canopies[index] > FOREST_EDGE;
                }
            }
        }
        boolean[] active = zone(candidate, wooded, Scratch.ints(), radius);
        int[] own = colors.clone();
        shapeRegion(active, wooded, Scratch.ints(), own, own, radius, untouchable, Scratch.booleans(), harmless, coastal, false, colors, secondColors, values, softness, blur);
        return active;
    }

    /**
     * Draws the borders between biomes of clearly different colors (sand and grass, badlands and plains, mycelium and the rest) as a
     * soft line instead of blending them over a whole cell, the way forest edges are drawn. Coasts, the snow line and forest edges have
     * the second color first: near them, and where three biomes meet, borders stay blended as before.
     */
    private static boolean[] shapeBiomes(
        Grid data, boolean[] bare, boolean[] coastal, boolean[] snowLine, boolean[] forestEdge, boolean[] harmless, int[] colors,
        int[] secondColors, float[] values, int[] softness, int smoothing, int blur
    ) {
        boolean[] taken = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            taken[index] = coastal[index] || snowLine[index] || forestEdge[index];
        }
        // Two rows of room around the snow line and forest edges: theirs and the samples just past them, which share triangles with
        // ours. At coasts the border is drawn up to the water, see shapeBorders.
        boolean[] lines = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            lines[index] = snowLine[index] || forestEdge[index];
        }
        boolean[] room = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                room[x + z * S] = near(lines, x, z);
            }
        }
        boolean[] candidate = Scratch.booleans();
        boolean[] colorOnly = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                candidate[index] = bare[index] && !near(room, x, z);
                colorOnly[index] = candidate[index] && near(coastal, x, z);
            }
        }
        return shapeBorders(data, null, candidate, taken, colorOnly, harmless, coastal, false, colors.clone(), colors, secondColors, values, softness, smoothing, BIOME_COLOR_STEP, blur);
    }

    /**
     * Draws the borders between waters of different temperatures (warm, lukewarm, cold, frozen oceans and the rest) the same way as
     * those between biomes, but with a third color and a value of their own: the second color is the water on one side, the third on
     * the other, so the border needs nothing of the coast's two colors and runs on sharp right up to the land. How soft it is drawn is
     * the same for all of it and comes with the tile, see the shader.
     */
    private static boolean[] shapeWaters(
        Grid data, boolean[] hasLand, int[] alphas, boolean[] coastal, boolean[] icy, int[] waterColors, int[] thirdColors,
        float[] thirdValues, int smoothing
    ) {
        boolean[] candidate = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            candidate[index] = !hasLand[index] && alphas[index] != 0;
        }
        // Land around takes the waters next to it afterwards (coastColors), so the border needs no fading next to it.
        if (DebugToggles.oldIce) {
            int[] kinds = Scratch.ints();
            for (int index = 0; index < GRID_VERTICES; index++) {
                kinds[index] = (data.biomes[index] & 0xFF) + (icy[index] ? 256 : 0);
            }
            return shapeBorders(data, kinds, candidate, coastal, Scratch.booleans(), hasLand, coastal, false, waterColors.clone(), waterColors, thirdColors, thirdValues, null, smoothing, WATER_COLOR_STEP, 0);
        }
        // The edge of the ice first, between ice and all open water whatever its temperature: where ice meets two waters of different
        // colors, a border of each pair of colors would stand in the other's way and neither would be drawn.
        boolean[] iceEdge = Scratch.booleans();
        boolean anyIce = false;
        int[] iceKinds = Scratch.ints();
        for (int index = 0; index < GRID_VERTICES; index++) {
            iceKinds[index] = icy[index] ? 1 : 0;
            anyIce |= candidate[index] && icy[index];
        }
        if (anyIce) {
            iceEdge = shapeBorders(data, iceKinds, candidate, coastal, Scratch.booleans(), hasLand, coastal, false, waterColors.clone(), waterColors, thirdColors, thirdValues, null, smoothing, WATER_COLOR_STEP, 0);
        }
        // Then the borders between waters of different temperatures, away from the edge of the ice. Only the samples the edge of the
        // ice really runs by keep it, those with a neighbour on its other side: the rest of its zone lies wholly on one side and shows
        // that side's color, and around frozen oceans, with ice and icebergs all over, that zone takes nearly all water. A row of room
        // around them, so no triangle has both borders.
        boolean[] shown = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!iceEdge[index]) {
                    continue;
                }
                boolean side = thirdValues[index] >= 0.0F;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1) && !shown[index]; nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (iceEdge[neighbour] && thirdValues[neighbour] >= 0.0F != side) {
                            shown[index] = true;
                            break;
                        }
                    }
                }
            }
        }
        // The rest show the color of their side on both, as they look anyway: the other color left in them would come out as a line of
        // ice in the triangles they share with a border between waters, and in ice next to one it would be taken for the ice's own.
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (iceEdge[index] && !shown[index]) {
                int sideColor = thirdValues[index] >= 0.0F ? thirdColors[index] : waterColors[index];
                waterColors[index] = sideColor;
                thirdColors[index] = sideColor;
            }
        }
        boolean[] taken = Scratch.booleans();
        boolean[] open = Scratch.booleans();
        int[] kinds = Scratch.ints();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                boolean room = near(shown, x, z);
                taken[index] = coastal[index] || room;
                open[index] = candidate[index] && !room;
                kinds[index] = data.biomes[index] & 0xFF;
            }
        }
        boolean[] temperature = shapeBorders(data, kinds, open, taken, Scratch.booleans(), hasLand, coastal, false, waterColors.clone(), waterColors, thirdColors, thirdValues, null, smoothing, WATER_COLOR_STEP, 0);
        for (int index = 0; index < GRID_VERTICES; index++) {
            temperature[index] |= iceEdge[index];
        }
        return temperature;
    }

    /**
     * Soft borders between pairs of biomes of clearly different colors among the candidate samples, see {@link #shapeBiomes}. Each
     * border gets its pair of biomes, carried to the samples around it; the one with the larger number is the inside of the region.
     *
     * At coasts the samples keep land and water in their two colors, so the border cannot be drawn there with two colors of its own:
     * the samples next to the coast ({@code colorOnly}) only take the color of the side of the smoothed border they fall on, and the
     * border runs on to the water as a blend over the last cells.
     *
     * @param taken       samples of other borders, which keep their colors
     * @param colorOnly   samples that only take the color of their side
     * @param harmless    samples outside the region that take their colors from it afterwards, see shapeRegion
     * @param ownIsSecond the border is between waters, whose color is the second one; else between lands, whose color is the first
     * @param kinds       what each sample is, by default its biome; samples of different kinds and clearly different colors have a
     *                    border between them
     * @param own         the color of each sample on its own side
     * @param step        how different the colors of two biomes must be for a border between them
     */
    private static boolean[] shapeBorders(
        Grid data, int @Nullable [] kinds, boolean[] candidate, boolean[] taken, boolean[] colorOnly, boolean @Nullable [] harmless, boolean[] coastal,
        boolean ownIsSecond, int[] own,
        int[] colors, int[] secondColors, float[] values, int @Nullable [] softness, int smoothing, int step, int blur
    ) {
        float radius = smoothingRadius(smoothing);
        int[] biome = colorClasses(data, kinds, candidate, own, step / 2);
        // The one other biome of a clearly different color next to each sample: -1 none, -2 more than one.
        int[] partner = Scratch.ints();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                partner[index] = -1;
                if (!candidate[index]) {
                    continue;
                }
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (!candidate[neighbour] || biome[neighbour] == biome[index] || colorDistance(own[index], own[neighbour]) < step) {
                            continue;
                        }
                        partner[index] = partner[index] == -1 || partner[index] == biome[neighbour] ? biome[neighbour] : -2;
                    }
                }
            }
        }
        // The pair of each border, spread ring by ring to the samples of either biome around it; where pairs meet, borders stay blended.
        int rings = zoneRings(radius);
        int[] pair = Scratch.ints();
        java.util.Arrays.fill(pair, -1);
        boolean[] blocked = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (partner[index] >= 0) {
                pair[index] = Math.min(biome[index], partner[index]) << KIND_BITS | Math.max(biome[index], partner[index]);
            } else if (partner[index] == -2) {
                blocked[index] = true;
            }
        }
        for (int ring = 0; ring < rings; ring++) {
            int[] next = pair.clone();
            for (int z = 0; z < S; z++) {
                for (int x = 0; x < S; x++) {
                    int index = x + z * S;
                    if (!candidate[index] || blocked[index] || pair[index] >= 0) {
                        continue;
                    }
                    for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                            int key = pair[nx + nz * S];
                            if (key < 0 || biome[index] != (key >> KIND_BITS) && biome[index] != (key & KIND_MASK)) {
                                continue;
                            }
                            if (next[index] >= 0 && next[index] != key) {
                                blocked[index] = true;
                            }
                            next[index] = key;
                        }
                    }
                }
            }
            pair = next;
        }
        boolean[] active = Scratch.booleans();
        boolean[] inside = Scratch.booleans();
        if (DebugToggles.markBlocked) {
            // The debug build marks where borders meet and stay blended, see DebugToggles.
            for (int index = 0; index < GRID_VERTICES; index++) {
                if (candidate[index] && blocked[index]) {
                    colors[index] = DEBUG_SKIRT;
                }
            }
        }
        for (int index = 0; index < GRID_VERTICES; index++) {
            active[index] = candidate[index] && !blocked[index] && pair[index] >= 0;
            inside[index] = active[index] && biome[index] == (pair[index] & KIND_MASK);
        }
        shapeRegion(active, inside, pair, own, own, radius, taken, colorOnly, harmless, coastal, ownIsSecond, colors, secondColors, values, softness, blur);
        return active;
    }

    /**
     * Puts the borders on land and between waters together at the coasts: the other side of each coast follows the samples beside it.
     * Water samples next to land take the land color beside them, and the border on that land; land samples next to water take the
     * water color beside them, and the border between those waters. Each sample keeps its own borders as well, as the two kinds have
     * colors and values of their own.
     */
    private static void mergeCoasts(
        boolean[] hasLand, int[] alphas, boolean[] coastal, boolean[] landBorder, boolean[] waterBorder, int[] colors, int[] waterColors,
        int[] waterThird, float[] waterSplit, int[] landThird, float[] landSplit, int[] softness
    ) {
        int[] land = colors.clone();
        int[] water = waterColors.clone();
        int[] landThirds = landThird.clone();
        float[] landSplits = landSplit.clone();
        int[] waterThirds = waterThird.clone();
        float[] waterSplits = waterSplit.clone();
        int[] soft = softness.clone();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!coastal[index] || alphas[index] == 0) {
                    continue;
                }
                boolean wet = !hasLand[index];
                int[] third = wet ? landThirds : waterThirds;
                float[] split = wet ? landSplits : waterSplits;
                boolean[] border = wet ? landBorder : waterBorder;
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                int thirdRed = 0;
                int thirdGreen = 0;
                int thirdBlue = 0;
                float splitSum = 0.0F;
                int softSum = 0;
                int handedRed = 0;
                int handedGreen = 0;
                int handedBlue = 0;
                // The samples beside with a border to hand on. Only they count for it; the value of the others is no distance to the
                // border, and averaged in it would draw a stripe of the other side of the border along the coast.
                int handed = 0;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (alphas[neighbour] == 0 || hasLand[neighbour] != wet) {
                            continue;
                        }
                        int color = wet ? land[neighbour] : water[neighbour];
                        red += ARGB.red(color);
                        green += ARGB.green(color);
                        blue += ARGB.blue(color);
                        count++;
                        if (!border[neighbour] || split[neighbour] == NO_SPLIT) {
                            continue;
                        }
                        thirdRed += ARGB.red(third[neighbour]);
                        thirdGreen += ARGB.green(third[neighbour]);
                        thirdBlue += ARGB.blue(third[neighbour]);
                        handedRed += ARGB.red(color);
                        handedGreen += ARGB.green(color);
                        handedBlue += ARGB.blue(color);
                        splitSum += split[neighbour];
                        softSum += soft[neighbour];
                        handed++;
                    }
                }
                if (count == 0) {
                    continue;
                }
                // With a border handed on, the color on its near side comes from the same samples as the far one, so the two always
                // belong together: the average of all would blend both sides of the border (ice and open water) into neither.
                int average = handed > 0 && !DebugToggles.noCoastBorders
                    ? ARGB.color(255, handedRed / handed, handedGreen / handed, handedBlue / handed)
                    : ARGB.color(255, red / count, green / count, blue / count);
                if (wet) {
                    colors[index] = average;
                } else {
                    waterColors[index] = average;
                }
                if (handed == 0 || DebugToggles.noCoastBorders) {
                    continue;
                }
                int handedColor = ARGB.color(255, thirdRed / handed, thirdGreen / handed, thirdBlue / handed);
                if (wet) {
                    landThird[index] = handedColor;
                    landSplit[index] = splitSum / handed;
                    softness[index] = Math.round(softSum / (float) handed);
                } else {
                    waterThird[index] = handedColor;
                    waterSplit[index] = splitSum / handed;
                }
            }
        }
    }

    /**
     * The biome of every candidate sample, with biomes of nearly the same color in this tile taken as one (deep and plain lukewarm
     * ocean, desert and beach): a border is drawn only where the color changes, and three biomes of two colors meeting would otherwise
     * break it into pieces.
     */
    private static int[] colorClasses(Grid data, int @Nullable [] kinds, boolean[] candidate, int[] own, int same) {
        int[] red = new int[KINDS];
        int[] green = new int[KINDS];
        int[] blue = new int[KINDS];
        int[] count = new int[KINDS];
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (candidate[index]) {
                int id = kinds != null ? kinds[index] : data.biomes[index] & 0xFF;
                red[id] += ARGB.red(own[index]);
                green[id] += ARGB.green(own[index]);
                blue[id] += ARGB.blue(own[index]);
                count[id]++;
            }
        }
        int[] root = new int[KINDS];
        int[] average = new int[KINDS];
        for (int id = 0; id < KINDS; id++) {
            root[id] = id;
            if (count[id] > 0) {
                average[id] = ARGB.color(255, red[id] / count[id], green[id] / count[id], blue[id] / count[id]);
            }
        }
        for (int a = 0; a < KINDS; a++) {
            if (count[a] == 0 || root[a] != a) {
                continue;
            }
            for (int b = a + 1; b < KINDS; b++) {
                if (count[b] > 0 && root[b] == b && colorDistance(average[a], average[b]) < same) {
                    root[b] = a;
                }
            }
        }
        int[] classes = Scratch.ints();
        for (int index = 0; index < GRID_VERTICES; index++) {
            classes[index] = root[kinds != null ? kinds[index] : data.biomes[index] & 0xFF];
        }
        return classes;
    }

    /**
     * The samples that take part in drawing a border: the candidates within a few rows of a border between two kinds, enough for the
     * smoothing to round it off. Only samples of the same group are compared.
     */
    private static boolean[] zone(boolean[] candidate, boolean[] kind, int[] group, float radius) {
        boolean[] active = Scratch.booleans();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!candidate[index]) {
                    continue;
                }
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1) && !active[index]; nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (candidate[neighbour] && kind[neighbour] != kind[index] && group[neighbour] == group[index]) {
                            active[index] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int ring = 0; ring < zoneRings(radius); ring++) {
            boolean[] next = active.clone();
            for (int z = 0; z < S; z++) {
                for (int x = 0; x < S; x++) {
                    int index = x + z * S;
                    next[index] |= candidate[index] && near(active, x, z);
                }
            }
            active = next;
        }
        return active;
    }

    /**
     * Shapes the border between the inside and the outside of a region among the active samples, as distances: the value of a sample
     * is how far it lies from the border, in units of two cells, above zero inside. The shader puts the border where it crosses zero; a distance
     * changes evenly from sample to sample, so the border falls in the right place between them.
     * <p>
     * The border is smoothed the way images are: the region is grown and shrunk again by the radius, which fills gaps and notches
     * narrower than twice the radius, then shrunk and grown again, which takes away patches and spikes that narrow; this also rounds
     * corners off by the radius and evens steps out. The distances are then softened a little with the neighbours.
     * <p>
     * Each sample gets the color of the outside as its land color and of the inside as its second: its own on its side, the nearest
     * sample's of the other side on the other. Samples far from the border, and those just past the active ones, show their own color
     * on both sides, so the switch the shader makes in the triangles they share with the rest does not show.
     *
     * @param group        only samples of the same group are compared (one pair of biomes, for example)
     * @param untouchable  samples of other borders, which keep their colors
     * @param colorOnly    active samples that only take the color of their side, see shapeBorders: their own slot, and the other one
     *                     too away from the coast
     * @param harmless     samples outside the region that take their colors from it afterwards: next to them the border is drawn
     *                     as everywhere else
     * @param ownIsSecond  whether that own slot is the second color (water) rather than the first (land)
     * @param soft         how soft the border is drawn, see putVertex
     */
    private static void shapeRegion(
        boolean[] active, boolean[] inside, int[] group, int[] insideColors, int[] outsideColors, float radius, boolean[] untouchable,
        boolean[] colorOnly, boolean @Nullable [] harmless, boolean[] coastal, boolean ownIsSecond, int[] colors, int[] secondColors, float[] values,
        int @Nullable [] softness, int soft
    ) {
        float[] raw = Scratch.floats();
        int[] nearest = Scratch.ints();
        float[] shaped = shapedDistances(active, inside, group, radius, raw, nearest);
        boolean[] twoColored = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            twoColored[index] = active[index] && !colorOnly[index] || harmless != null && harmless[index];
        }
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!active[index]) {
                    continue;
                }
                // The samples on the tile edges are shared with the next tile, which does not know this tile's samples: they keep
                // their own side, half a cell from the border, the same in both tiles.
                boolean edge = x == 0 || z == 0 || x == C || z == C;
                float distance = edge ? (inside[index] ? 0.5F : -0.5F) : shaped[index];
                int other = nearest[index];
                if (colorOnly[index]) {
                    boolean side = distance >= 0.0F;
                    int sideColor = side == inside[index] || other < 0
                        ? (inside[index] ? insideColors[index] : outsideColors[index])
                        : (side ? insideColors[other] : outsideColors[other]);
                    if (ownIsSecond || !coastal[index]) {
                        secondColors[index] = sideColor;
                    }
                    if (!ownIsSecond || !coastal[index]) {
                        colors[index] = sideColor;
                    }
                    continue;
                }
                // Next to a sample that takes no part, which keeps its own color on both sides, the border cannot be drawn without a
                // stripe of the wrong color between them: such samples show their own color on both sides as well, and the border fades
                // there as before.
                boolean far = other < 0 || Math.abs(raw[index]) > radius + 1.0F || !allAround(twoColored, x, z);
                int insideColor = inside[index] ? insideColors[index] : far ? outsideColors[index] : insideColors[other];
                int outsideColor = !inside[index] ? outsideColors[index] : far ? insideColors[index] : outsideColors[other];
                colors[index] = outsideColor;
                secondColors[index] = insideColor;
                values[index] = Mth.clamp(distance * 0.5F, -COAST_RANGE, 1.0F);
                if (softness != null) {
                    softness[index] = soft;
                }
            }
        }
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!active[index] && !untouchable[index] && near(active, x, z)) {
                    secondColors[index] = colors[index];
                }
            }
        }
    }

    /**
     * The smoothed distances of the active samples to the border of the inside, see {@link #shapeRegion}: the region is closed and
     * opened by the radius, and the distances softened a little.
     *
     * @param raw     receives the distances before the smoothing
     * @param nearest receives the nearest sample on the other side, see {@link #signedDistance}
     */
    private static float[] shapedDistances(boolean[] active, boolean[] inside, int[] group, float radius, float[] raw, int[] nearest) {
        int window = (int) Math.ceil(radius) + 3;
        signedDistance(active, inside, group, window, raw, nearest);
        boolean[] mask = Scratch.booleans();
        float[] grown = Scratch.floats();
        float[] shaped = Scratch.floats();
        // Closing: grow by the radius, then shrink.
        for (int index = 0; index < GRID_VERTICES; index++) {
            mask[index] = active[index] && raw[index] >= -radius;
        }
        signedDistance(active, mask, group, window, grown, null);
        // Opening: shrink the closed region by the radius, then grow.
        for (int index = 0; index < GRID_VERTICES; index++) {
            mask[index] = active[index] && grown[index] - radius >= radius;
        }
        signedDistance(active, mask, group, window, shaped, null);
        for (int index = 0; index < GRID_VERTICES; index++) {
            shaped[index] += radius;
        }
        smooth(shaped, active, SHAPE_SOFTEN_PASSES);
        return shaped;
    }

    /**
     * Distance from every active sample to the border of the masked region, in cells, above zero inside: half a cell less than the
     * distance to the nearest active sample of the same group on the other side, looked for up to the window; beyond it the window.
     *
     * @param nearest when not null, receives that nearest sample, -1 when there is none within the window
     */
    private static void signedDistance(boolean[] active, boolean[] mask, int[] group, int window, float[] out, int @Nullable [] nearest) {
        if (DebugToggles.oldDistances) {
            signedDistanceByWindow(active, mask, group, window, out, nearest);
            return;
        }
        // Per group, whichever is less work: the search through the window around every sample, or an exact distance transform
        // (Felzenszwalb and Huttenlocher) over the part of the tile the group can reach, two passes for each side. Both give the same,
        // see firstAt and nearestInWindow.
        int[] targets = Scratch.ints();
        int[] squared = Scratch.ints();
        int[] found = Scratch.ints();
        boolean[] done = Scratch.booleans();
        int searchCost = (2 * window + 1) * (2 * window + 1);
        for (int first = 0; first < GRID_VERTICES; first++) {
            if (!active[first] || done[first]) {
                continue;
            }
            int id = group[first];
            int count = 0;
            int minX = C;
            int minZ = C;
            int maxX = 0;
            int maxZ = 0;
            for (int index = first; index < GRID_VERTICES; index++) {
                if (active[index] && group[index] == id) {
                    done[index] = true;
                    count++;
                    minX = Math.min(minX, index % S);
                    maxX = Math.max(maxX, index % S);
                    minZ = Math.min(minZ, index / S);
                    maxZ = Math.max(maxZ, index / S);
                }
            }
            int x0 = Math.max(0, minX - window);
            int z0 = Math.max(0, minZ - window);
            int x1 = Math.min(C, maxX + window);
            int z1 = Math.min(C, maxZ + window);
            if (count * searchCost <= TRANSFORM_COST * (x1 - x0 + 1) * (z1 - z0 + 1)) {
                for (int index = first; index < GRID_VERTICES; index++) {
                    if (active[index] && group[index] == id) {
                        setDistance(index, nearestInWindow(active, mask, group, window, index % S, index / S), mask, window, out, nearest);
                    }
                }
                continue;
            }
            for (int side = 0; side < 2; side++) {
                boolean targetSide = side == 0;
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        int index = x + z * S;
                        targets[index] = active[index] && group[index] == id && mask[index] == targetSide ? 0 : FAR;
                    }
                }
                distanceTransform(targets, x0, z0, x1, z1, squared, found);
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        int index = x + z * S;
                        if (!active[index] || group[index] != id || mask[index] == targetSide) {
                            continue;
                        }
                        int other = found[index];
                        if (other >= 0 && Math.abs(other % S - x) <= window && Math.abs(other / S - z) <= window) {
                            other = firstAt(targets, x, z, window, squared[index]);
                        } else if (other >= 0 && squared[index] <= 2 * window * window) {
                            // The nearest lies outside the window, but a farther one may lie inside it: the search there finds that one.
                            other = nearestInWindow(active, mask, group, window, x, z);
                        } else {
                            other = -1;
                        }
                        setDistance(index, other, mask, window, out, nearest);
                    }
                }
            }
        }
    }

    /** Work of the distance transform per sample of the part of the tile it runs over, against one sample the search looks at. */
    private static final int TRANSFORM_COST = 6;

    private static void setDistance(int index, int other, boolean[] mask, int window, float[] out, int @Nullable [] nearest) {
        int dx = other % S - index % S;
        int dz = other / S - index / S;
        float distance = other < 0 ? window : Mth.sqrt(dx * dx + dz * dz) - 0.5F;
        out[index] = mask[index] ? distance : -distance;
        if (nearest != null) {
            nearest[index] = other;
        }
    }

    /** Larger than any squared distance within a tile: no target. */
    private static final int FAR = 1 << 20;

    /**
     * Squared distance from every sample of a rectangle of the tile to the nearest target in it (value 0 in {@code targets}, others
     * {@link #FAR}) and that target, -1 when there is none: the 1D lower envelope of parabolas along every column, then along every row.
     */
    private static void distanceTransform(int[] targets, int x0, int z0, int x1, int z1, int[] squared, int[] found) {
        int width = x1 - x0 + 1;
        int height = z1 - z0 + 1;
        int[] line = new int[S];
        int[] lineArg = new int[S];
        int[] values = new int[S];
        int[] args = new int[S];
        int[] hull = new int[S];
        float[] bounds = new float[S + 1];
        int[] columnDistance = Scratch.ints();
        int[] columnTarget = Scratch.ints();
        for (int x = x0; x <= x1; x++) {
            for (int z = 0; z < height; z++) {
                line[z] = targets[x + (z0 + z) * S];
            }
            envelope(line, height, values, args, hull, bounds);
            for (int z = 0; z < height; z++) {
                columnDistance[x + (z0 + z) * S] = values[z];
                columnTarget[x + (z0 + z) * S] = args[z] < 0 ? -1 : z0 + args[z];
            }
        }
        for (int z = z0; z <= z1; z++) {
            for (int x = 0; x < width; x++) {
                line[x] = columnDistance[x0 + x + z * S];
                lineArg[x] = columnTarget[x0 + x + z * S];
            }
            envelope(line, width, values, args, hull, bounds);
            for (int x = 0; x < width; x++) {
                int index = x0 + x + z * S;
                squared[index] = values[x];
                found[index] = args[x] < 0 ? -1 : x0 + args[x] + lineArg[args[x]] * S;
            }
        }
    }

    /**
     * The 1D distance transform of the first {@code n} values of {@code f}: {@code d[q] = min over p of (q - p)^2 + f[p]}, and the p,
     * -1 where every f is {@link #FAR}.
     */
    private static void envelope(int[] f, int n, int[] d, int[] arg, int[] hull, float[] bounds) {
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] >= FAR) {
                continue;
            }
            float s = 0.0F;
            while (k >= 0) {
                int p = hull[k];
                s = ((f[q] + q * q) - (f[p] + p * p)) / (2.0F * (q - p));
                if (s <= bounds[k]) {
                    k--;
                } else {
                    break;
                }
            }
            k++;
            hull[k] = q;
            bounds[k] = k == 0 ? Float.NEGATIVE_INFINITY : s;
            bounds[k + 1] = Float.POSITIVE_INFINITY;
        }
        if (k < 0) {
            java.util.Arrays.fill(d, 0, n, FAR);
            java.util.Arrays.fill(arg, 0, n, -1);
            return;
        }
        int j = 0;
        for (int q = 0; q < n; q++) {
            while (bounds[j + 1] < q) {
                j++;
            }
            int p = hull[j];
            d[q] = (q - p) * (q - p) + f[p];
            arg[q] = p;
        }
    }

    /**
     * Of the targets at a squared distance from a sample, the one the search through the window finds first (row by row, from the
     * north-west corner), so equal distances pick the same sample's colors as it does.
     */
    private static int firstAt(int[] targets, int x, int z, int window, int squared) {
        for (int nz = Math.max(0, z - window); nz <= Math.min(C, z + window); nz++) {
            int rest = squared - (nz - z) * (nz - z);
            if (rest < 0) {
                continue;
            }
            int dx = (int) Math.round(Math.sqrt(rest));
            if (dx * dx != rest || dx > window) {
                continue;
            }
            if (x - dx >= 0 && targets[x - dx + nz * S] == 0) {
                return x - dx + nz * S;
            }
            if (dx != 0 && x + dx <= C && targets[x + dx + nz * S] == 0) {
                return x + dx + nz * S;
            }
        }
        return -1;
    }

    /** The nearest active sample of the same group on the other side within the window, searched sample by sample; -1 if none. */
    private static int nearestInWindow(boolean[] active, boolean[] mask, int[] group, int window, int x, int z) {
        int index = x + z * S;
        int best = Integer.MAX_VALUE;
        int found = -1;
        for (int nz = Math.max(0, z - window); nz <= Math.min(C, z + window); nz++) {
            for (int nx = Math.max(0, x - window); nx <= Math.min(C, x + window); nx++) {
                int other = nx + nz * S;
                if (!active[other] || mask[other] == mask[index] || group[other] != group[index]) {
                    continue;
                }
                int squared = (nx - x) * (nx - x) + (nz - z) * (nz - z);
                if (squared < best) {
                    best = squared;
                    found = other;
                }
            }
        }
        return found;
    }

    /** The search through the window around every sample, which {@link #signedDistance} replaces; kept to compare in the debug build. */
    private static void signedDistanceByWindow(boolean[] active, boolean[] mask, int[] group, int window, float[] out, int @Nullable [] nearest) {
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (!active[index]) {
                    continue;
                }
                int best = Integer.MAX_VALUE;
                int found = -1;
                for (int nz = Math.max(0, z - window); nz <= Math.min(C, z + window); nz++) {
                    for (int nx = Math.max(0, x - window); nx <= Math.min(C, x + window); nx++) {
                        int other = nx + nz * S;
                        if (!active[other] || mask[other] == mask[index] || group[other] != group[index]) {
                            continue;
                        }
                        int squared = (nx - x) * (nx - x) + (nz - z) * (nz - z);
                        if (squared < best) {
                            best = squared;
                            found = other;
                        }
                    }
                }
                float distance = found < 0 ? window : Mth.sqrt(best) - 0.5F;
                out[index] = mask[index] ? distance : -distance;
                if (nearest != null) {
                    nearest[index] = found;
                }
            }
        }
    }

    /** Whether the sample and all its neighbours within the tile are marked. */
    private static boolean allAround(boolean[] marks, int x, int z) {
        for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
            for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                if (!marks[nx + nz * S]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The radius in cells the borders are smoothed by, from the setting. */
    private static float smoothingRadius(int smoothing) {
        return smoothing * 0.5F;
    }

    /** Rows of samples around a border taken into its shaping: enough for the smoothing radius and a row of room. */
    private static int zoneRings(float radius) {
        return (int) Math.ceil(radius) + 2;
    }

    /** How many times the shaped distances are softened with their neighbours, see {@link #shapeRegion}. */
    private static final int SHAPE_SOFTEN_PASSES = 2;

    /**
     * Evens the values of the marked samples out with their marked neighbours, the given number of times. The samples on the tile
     * edges are left as they are, as the next tile has them too.
     */
    private static void smooth(float[] values, boolean[] marked, int passes) {
        float[] field = values;
        float[] next = values.clone();
        for (int pass = 0; pass < passes; pass++) {
            for (int z = 1; z < C; z++) {
                for (int x = 1; x < C; x++) {
                    int index = x + z * S;
                    if (!marked[index]) {
                        continue;
                    }
                    float sum = 0.0F;
                    float weights = 0.0F;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int neighbour = index + dx + dz * S;
                            if ((dx != 0 || dz != 0) && marked[neighbour]) {
                                float weight = dx == 0 || dz == 0 ? 2.0F : 1.0F;
                                sum += weight * field[neighbour];
                                weights += weight;
                            }
                        }
                    }
                    next[index] = weights == 0.0F ? field[index] : COAST_KEEP * field[index] + (1.0F - COAST_KEEP) * sum / weights;
                }
            }
            float[] swap = field;
            field = next;
            next = swap;
        }
        if (field != values) {
            System.arraycopy(field, 0, values, 0, values.length);
        }
    }

    /** Kinds of samples a border can be between: the biomes of a tile, and for water each also frozen, see shapeWaters. */
    private static final int KIND_BITS = 9;
    private static final int KINDS = 1 << KIND_BITS;
    private static final int KIND_MASK = KINDS - 1;

    private static int colorDistance(int a, int b) {
        return Math.abs(ARGB.red(a) - ARGB.red(b)) + Math.abs(ARGB.green(a) - ARGB.green(b)) + Math.abs(ARGB.blue(a) - ARGB.blue(b));
    }

    /** Biomes whose colors differ less than this (red, green and blue differences added up) blend into each other as before. */
    private static final int BIOME_COLOR_STEP = 24;
    /**
     * The same for waters of different temperatures: water is dark, so the same difference shows more. Biomes differing by less than
     * half the step are taken as one, see {@link #colorClasses}.
     */
    private static final int WATER_COLOR_STEP = 8;

    /** Trees start to thin out from this slope on and are gone from the next one, see the tree tops in {@link #build}. */
    private static final float TREES_THIN_SLOPE = 0.9F;
    private static final float TREES_GONE_SLOPE = 1.8F;

    private static float smoothstep(float from, float to, float value) {
        float t = Mth.clamp((value - from) / (to - from), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** Biomes with a smaller share of the ground under trees count as sparse, see shapeForest. */
    private static final float SPARSE_TREES = 0.4F;

    /** Tree tops lower than this many blocks are left out; the edge of a forest is drawn where they reach it. */
    private static final float FOREST_EDGE = 0.5F;

    /** Whether the sample or one of its neighbours is marked. */
    private static boolean near(boolean[] marks, int x, int z) {
        for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
            for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                if (marks[nx + nz * S]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int worldCoordinate(long base, int sample, int spacing) {
        return (int) Mth.clamp(base + (long) sample * spacing, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
    }

    /** The shaded color of the land of a sample with or without snow, the average over its land sub-samples on coarse tiles. */
    private static int landColor(
        Grid data, int index, List<@Nullable Holder<Biome>> biomes, @Nullable Holder<Biome> biome, int x, int y, int z, float slope,
        float sides, float shade, int seaLevel, boolean snowy
    ) {
        if (data.subBiomes == null || data.subWater == null) {
            return ARGB.scaleRGB(TileColors.landColor(biome, x, y, z, slope, sides, seaLevel, snowy), shade);
        }
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
            if ((data.subWater[index] & 1 << i) != 0) {
                continue;
            }
            Holder<Biome> sub = biomes.get(data.subBiomes[index * TileData.SUB_SAMPLES + i] & 0xFF);
            int color = TileColors.landColor(sub, x, y, z, slope, sides, seaLevel, snowy);
            red += ARGB.red(color);
            green += ARGB.green(color);
            blue += ARGB.blue(color);
            count++;
        }
        if (count == 0) {
            return ARGB.scaleRGB(TileColors.landColor(biome, x, y, z, slope, sides, seaLevel, snowy), shade);
        }
        return ARGB.scaleRGB(ARGB.color(255, red / count, green / count, blue / count), shade);
    }

    /**
     * Keeps narrow land and water as they are: the smoothing takes away whatever is narrower than twice its radius, which would also
     * take away whole islands, peninsulas and rivers. Of the samples it moves to the other side, only those along what is left of
     * their own side may go (corners and steps of a coast); a group of them reaching farther out keeps its old distances.
     *
     * @param inside the samples of one side, the other present samples are the other side
     */
    private static boolean[] keepNarrow(boolean[] present, boolean[] inside, float[] raw, float[] shaped) {
        boolean[] restored = Scratch.booleans();
        boolean[] moved = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            moved[index] = present[index] && shaped[index] > 0.0F != inside[index];
        }
        boolean[] seen = Scratch.booleans();
        int[] group = Scratch.ints();
        for (int first = 0; first < GRID_VERTICES; first++) {
            if (!moved[first] || seen[first]) {
                continue;
            }
            int size = 0;
            group[size++] = first;
            seen[first] = true;
            boolean fringe = true;
            for (int i = 0; i < size; i++) {
                int index = group[i];
                int x = index % S;
                int z = index / S;
                boolean along = false;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (!present[neighbour] || inside[neighbour] != inside[index]) {
                            continue;
                        }
                        if (moved[neighbour]) {
                            if (!seen[neighbour]) {
                                seen[neighbour] = true;
                                group[size++] = neighbour;
                            }
                        } else {
                            along = true;
                        }
                    }
                }
                fringe &= along;
            }
            if (!fringe) {
                for (int i = 0; i < size; i++) {
                    shaped[group[i]] = raw[group[i]];
                    restored[group[i]] = true;
                }
            }
        }
        return restored;
    }

    /**
     * Rounds off narrow land and water kept by {@link #keepNarrow}: their distances are those of the unsmoothed samples, a staircase
     * with right angles. They are softened with the samples around them, but stay on their own side, so nothing is taken away.
     */
    private static void softenRestored(float[] shaped, boolean[] restored) {
        boolean[] marked = Scratch.booleans();
        boolean any = false;
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                marked[x + z * S] = near(restored, x, z);
                any |= restored[x + z * S];
            }
        }
        if (!any) {
            return;
        }
        float[] softened = shaped.clone();
        smooth(softened, marked, COARSE_COAST_PASSES);
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (restored[index]) {
                shaped[index] = shaped[index] > 0.0F ? Math.max(softened[index], COAST_FIT_MIN) : Math.min(softened[index], -COAST_FIT_MIN);
            }
        }
    }

    /**
     * Where the smoothing moves a coast inland, lowers the land next to the water (never raises it) to a gentle slope up from the
     * water at the new coast, so the coast can be drawn there at the level of the water, see fitCoasts. The land stays above the
     * water, so nothing turns into water. The samples on the tile edges are shared with the next tile and are left as they are.
     *
     * @param shaped smoothed distances to the coast in cells, above zero over water, see shapedDistances
     */
    private static void cutCoasts(
        float[] tops, short[] heights, boolean[] hasLand, boolean[] present, boolean[] narrow, int spacing, float[] raw, float[] shaped
    ) {
        float[] before = tops.clone();
        for (int z = 1; z < C; z++) {
            for (int x = 1; x < C; x++) {
                int index = x + z * S;
                // Narrow land kept whole (small islands, icebergs, see keepNarrow) keeps its height too.
                if (!present[index] || !hasLand[index] || narrow[index] || shaped[index] <= raw[index] + 0.05F) {
                    continue;
                }
                float level = -Float.MAX_VALUE;
                for (int nz = z - 1; nz <= z + 1; nz++) {
                    for (int nx = x - 1; nx <= x + 1; nx++) {
                        int neighbour = nx + nz * S;
                        if (present[neighbour] && !hasLand[neighbour]) {
                            level = Math.max(level, before[neighbour]);
                        }
                    }
                }
                if (level == -Float.MAX_VALUE) {
                    continue;
                }
                float cap = level + Math.max(Math.max(-shaped[index], 0.0F) * spacing * COAST_CUT_SLOPE, COAST_CUT_ABOVE);
                if (tops[index] > cap) {
                    heights[index] = (short) Math.round(heights[index] + cap - tops[index]);
                    tops[index] = cap;
                }
            }
        }
    }

    /**
     * Fits the coast values to the smoothed coast: along every edge between land and water the shader draws the coast where the
     * value crosses zero, so where the values of the two samples are in the same proportion as their smoothed distances to the coast,
     * it is drawn on the smoothed line. Land keeps the values its height asks for (within what it allows, see shapeCoasts), and the
     * water beside it takes values in proportion to them; then the land is evened out the same way against the water, and the water
     * once more. Only the depth of the water changes much, which does not show, as it is flat in color; so the coast follows the
     * smooth line and stays at the level of the water.
     */
    private static void fitCoasts(float[] field, float[] low, float[] high, float[] levels, boolean[] present, boolean[] water, float[] shaped) {
        for (int pass = 0; pass < 3; pass++) {
            boolean wet = pass != 1;
            float[] current = field.clone();
            for (int z = 1; z < C; z++) {
                for (int x = 1; x < C; x++) {
                    int index = x + z * S;
                    if (!present[index] || Float.isNaN(levels[index]) || water[index] != wet) {
                        continue;
                    }
                    float sum = 0.0F;
                    int count = 0;
                    for (int nz = z - 1; nz <= z + 1; nz++) {
                        for (int nx = x - 1; nx <= x + 1; nx++) {
                            int neighbour = nx + nz * S;
                            if (!present[neighbour] || Float.isNaN(levels[neighbour]) || water[neighbour] == wet) {
                                continue;
                            }
                            float distance = wet ? Math.min(shaped[neighbour], -COAST_FIT_MIN) : Math.max(shaped[neighbour], COAST_FIT_MIN);
                            sum += current[neighbour] / distance;
                            count++;
                        }
                    }
                    if (count > 0) {
                        float own = wet ? Math.max(shaped[index], COAST_FIT_MIN) : Math.min(shaped[index], -COAST_FIT_MIN);
                        field[index] = Mth.clamp(sum / count * own, low[index], high[index]);
                    }
                }
            }
        }
    }

    /** The smallest distance to the coast, in cells, fitCoasts counts with, so samples the smoothing moved still get a value. */
    private static final float COAST_FIT_MIN = 0.1F;
    /** How high over the water land cut back by {@link #cutCoasts} stays at least, in blocks. */
    private static final float COAST_CUT_ABOVE = 0.5F;

    /** How steep the ground rises from the water where coasts are cut back, see {@link #cutCoasts}: blocks up per block inland. */
    private static final float COAST_CUT_SLOPE = 1.5F;

    /**
     * The coast value of a coarse sample from the sub-samples around it: the distance to the nearest sub-sample of the other kind, less
     * half the step between sub-samples, in cells, positive over water. The sub-samples of a cell lie a third of a cell apart around
     * the sample. Samples on a tile edge only look along the edge, at sub-samples the next tile has too, so both tiles agree.
     */
    private static float subCoast(short[] subWater, int x, int z) {
        int waterCount = Integer.bitCount(subWater[x + z * S] & SUB_MASK);
        // The same side as the generator gives the sample (its height and flags): the side of most of its sub-samples. The middle one
        // alone would draw a pit of water in a cell that is mostly land, and the other way round.
        boolean water = waterCount * 2 > TileData.SUB_SAMPLES;
        int minX = x == 0 || x == C ? x : x - 1;
        int maxX = x == 0 || x == C ? x : x + 1;
        int minZ = z == 0 || z == C ? z : z - 1;
        int maxZ = z == 0 || z == C ? z : z + 1;
        int nearest = Integer.MAX_VALUE;
        for (int nz = minZ; nz <= maxZ; nz++) {
            for (int nx = minX; nx <= maxX; nx++) {
                int mask = subWater[nx + nz * S];
                for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                    if (((mask & 1 << i) != 0) != water) {
                        int dx = 3 * (nx - x) + i % 3 - 1;
                        int dz = 3 * (nz - z) + i / 3 - 1;
                        nearest = Math.min(nearest, dx * dx + dz * dz);
                    }
                }
            }
        }
        float distance = nearest == Integer.MAX_VALUE ? 1.0F : Mth.clamp(Mth.sqrt(nearest) / 3.0F - 1.0F / 6.0F, COAST_FIT_MIN, 1.0F);
        // How much of the cell is on its side counts too, so a cell that is barely water lies close to the coast.
        float share = Math.abs(waterCount - TileData.SUB_SAMPLES * 0.5F) / (TileData.SUB_SAMPLES * 0.5F);
        float value = 0.5F * (distance + share);
        return water ? value : -value;
    }

    /** All sub-sample bits of a cell. */
    private static final int SUB_MASK = (1 << TileData.SUB_SAMPLES) - 1;

    /** Whether a coarse cell has both land and water in it. */
    private static boolean mixed(short subWater) {
        int bits = subWater & SUB_MASK;
        return bits != 0 && bits != SUB_MASK;
    }

    /** How many times coarse coasts are softened with their neighbours, see {@link #shapeCoasts}. */
    private static final int COARSE_COAST_PASSES = 2;

    /** The value of the third border of samples without one, see {@link #putVertex}; borders only give it by chance. */
    private static final float NO_SPLIT = -1.0F;
    /** How much of its own coast value a sample keeps when softened with its neighbours. */
    private static final float COAST_KEEP = 0.6F;
    /** The coast values closest to zero water and land keep, so softening never moves the coast past a sample. */
    private static final float COAST_WATER_MIN = 0.1F;
    private static final float COAST_LAND_MIN = 0.05F;
    /** How many blocks land next to the coast may be moved up or down to smooth it: an eighth of a cell, within these bounds. */
    private static final float COAST_GIVE_MIN = 1.0F;
    private static final float COAST_GIVE_MAX = 16.0F;
    /** How far below zero coast values are kept for the shader, in units of the reach, see {@link #putVertex}. */
    static final float COAST_RANGE = 15.0F;

    /** How far water next to land may be lowered, see {@link #shapeCoasts}: half a cell, within these bounds. */
    private static final float COAST_DIP_MIN = 1.5F;
    private static final float COAST_DIP_MAX = 32.0F;

    /**
     * Water samples get the land color of the land next to them, so the land up to the coast between them keeps its own color instead
     * of fading towards some other one. Water with no land around keeps its water color there, where no coast is drawn anyway.
     */
    private static void landBesideWater(int[] colors, int[] waterColors, boolean[] hasLand) {
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (hasLand[index]) {
                    continue;
                }
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(C, z + 1); nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(C, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (hasLand[neighbour]) {
                            red += ARGB.red(colors[neighbour]);
                            green += ARGB.green(colors[neighbour]);
                            blue += ARGB.blue(colors[neighbour]);
                            count++;
                        }
                    }
                }
                colors[index] = count == 0 ? waterColors[index] : ARGB.color(255, red / count, green / count, blue / count);
            }
        }
    }

    /**
     * How many samples along the edge on each side a skirt looks at for the lowest ground: a neighbour up to three levels coarser runs
     * straight over eight of this tile's cells.
     */
    private static final int SKIRT_REACH = 8;
    /** How far rough water lies under the block top of the sea level: vanilla's water surface is at 8/9, this a little more. */
    private static final float WATER_UNDER_VANILLA = 0.15F;
    /** The color the debug build marks things with, see DebugToggles. */
    private static final int DEBUG_SKIRT = 0xFFFF0060;
    private static final float MAX_SKIRT_DEPTH = 1024.0F;
    /** Room a skirt has below the lowest ground near it, in blocks: the spacing of the tile within these bounds. */
    private static final float SKIRT_MIN_ROOM = 2.0F;
    private static final float SKIRT_MAX_ROOM = 128.0F;

    /** Samples along the edges of the drawn tile, see the skirts. */
    private static int edgeX(int edge, int i) {
        return switch (edge) {
            case 0, 1 -> i;
            case 2 -> 0;
            default -> OUT_C;
        };
    }

    private static int edgeZ(int edge, int i) {
        return switch (edge) {
            case 0 -> 0;
            case 1 -> OUT_C;
            case 2, 3 -> i;
            default -> 0;
        };
    }

    /**
     * Gives every void sample the height of the nearest land sample, so a floating tile's surface runs level between its islands.
     * Only the heights move: the void stays the void, and a tile of nothing but void keeps its heights, as there is no land in it.
     */
    private static void fillVoidHeights(Grid data) {
        boolean[] all = Scratch.booleans();
        boolean[] land = Scratch.booleans();
        int[] nearest = Scratch.ints();
        for (int index = 0; index < GRID_VERTICES; index++) {
            land[index] = (data.flags[index] & TileData.FLAG_VOID) == 0;
            all[index] = true;
        }
        // The void samples must be active: signedDistance only writes the nearest sample of the other side for active ones.
        signedDistance(all, land, Scratch.ints(), S, Scratch.floats(), nearest);
        for (int index = 0; index < GRID_VERTICES; index++) {
            if (!land[index] && nearest[index] >= 0) {
                data.heights[index] = data.heights[nearest[index]];
            }
        }
    }

    /**
     * Lifts island samples near the edge out of dips to the average height of the island samples around them. Coarse samples at the
     * edge of an island average the few sub-samples that hit land, often its low rim, and the surface would hang from the island in
     * teeth down to them.
     */
    private static void liftRims(float[] tops, int[] alphas, float[] distances) {
        float[] lifted = Scratch.floats();
        System.arraycopy(tops, 0, lifted, 0, GRID_VERTICES);
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                if (alphas[index] == 0 || distances[index] >= RIM_CELLS) {
                    continue;
                }
                float sum = 0.0F;
                int count = 0;
                for (int nz = Math.max(0, z - 1); nz <= Math.min(S - 1, z + 1); nz++) {
                    for (int nx = Math.max(0, x - 1); nx <= Math.min(S - 1, x + 1); nx++) {
                        int neighbour = nx + nz * S;
                        if (neighbour != index && alphas[neighbour] != 0) {
                            sum += tops[neighbour];
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    lifted[index] = Math.max(tops[index], sum / count);
                }
            }
        }
        System.arraycopy(lifted, 0, tops, 0, GRID_VERTICES);
    }

    /**
     * The underside of floating islands, after the skirts' place in the buffer: under every sample of the surface, deeper the farther it
     * is from the edge of the island, so an island is a closed body shaped like vanilla's, its two sides meeting where the shader cuts
     * them at the edge. Returns the greatest depth.
     */
    private static float underside(ByteBuffer buffer, long tileX, long tileZ, int spacing, float[] tops, int[] colors, int[] alphas, float[] distances) {
        float deepest = 0.0F;
        for (int z = 0; z < OUT; z++) {
            for (int x = 0; x < OUT; x++) {
                int index = inGrid(x, z);
                float depth = Math.min(Math.max(0.0F, distances[index]) * spacing * UNDERSIDE_SLOPE, UNDERSIDE_MAX_DEPTH);
                deepest = Math.max(deepest, depth);
                int color = ARGB.scaleRGB(colors[index], UNDERSIDE_SHADE);
                putVertex(buffer, GeneralMeshFactory.localCoordinate(tileX, x, spacing), tops[index] - depth, GeneralMeshFactory.localCoordinate(tileZ, z, spacing), color, alphas[index], 0,
                    color, distances[index], color, NO_SPLIT, color, NO_SPLIT);
            }
        }
        return deepest;
    }

    /** The signed distance of every sample to the island edge, in cells, over land above zero: what the coast value carries on floating tiles. */
    private static void floatingDistances(int[] alphas, float[] coast) {
        boolean[] all = Scratch.booleans();
        boolean[] land = Scratch.booleans();
        for (int index = 0; index < GRID_VERTICES; index++) {
            all[index] = true;
            land[index] = alphas[index] != 0;
        }
        signedDistance(all, land, Scratch.ints(), S, coast, null);
    }

    /** The heights of the grid evened out over the three samples around each one, in both directions. */
    private static float[] blurredHeights(Grid data) {
        float[] rows = Scratch.floats();
        float[] blurred = Scratch.floats();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                int left = x > 0 ? index - 1 : index;
                int right = x + 1 < S ? index + 1 : index;
                rows[index] = (data.heights[left] + data.heights[index] + data.heights[right]) / 3.0F;
            }
        }
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int index = x + z * S;
                int up = z > 0 ? index - S : index;
                int down = z + 1 < S ? index + S : index;
                blurred[index] = (rows[up] + rows[index] + rows[down]) / 3.0F;
            }
        }
        return blurred;
    }

    private static float sample(Grid data, int x, int z) {
        return height(data, Mth.clamp(x, 0, C) + Mth.clamp(z, 0, C) * S);
    }

    private static float height(Grid data, int index) {
        return Mth.clamp(data.heights[index], MIN_HEIGHT, MAX_HEIGHT);
    }

    /**
     * The alpha of the land color: 0 marks the void, where the shader drops everything closer to a void sample than to land. Land is
     * 1 and up: 21 steps per softness of its border (0 sharp up to {@link GeneralMeshFactory#MAX_SOFTNESS}, each twice as wide as the one before), and
     * within them the height of the tree tops in steps of 1.5 blocks. After the land color comes the second color (water), with the
     * value of {@link #shapeCoasts} in its alpha, -{@link #COAST_RANGE} to 1 as 0 to 255. Then come the colors beyond the borders on
     * land (the snow line, forest edges, biomes) and between waters (of different temperatures), each with the value of its border in
     * its alpha, -1 to 1 as 0 to 255: beyond its border each takes the place of the land color or of the second one. The softness of
     * the borders between waters is the same everywhere and comes with the tile.
     *
     * @param alpha 0 for the void, else 255 minus the height of the tree tops in {@link #CANOPY_UNITS}
     */
    private static void putVertex(
        ByteBuffer buffer, float x, float y, float z, int argb, int alpha, int softness, int water, float coast, int landThird,
        float landSplit, int waterThird, float waterSplit
    ) {
        int steps = Mth.clamp(softness, 0, GeneralMeshFactory.MAX_SOFTNESS) * 21 + Math.min(20, Math.round((255 - alpha) / CANOPY_UNITS / 1.5F));
        int code = alpha == 0 ? 0 : 1 + steps;
        buffer.putFloat(x).putFloat(y).putFloat(z);
        buffer.put((byte) ARGB.red(argb)).put((byte) ARGB.green(argb)).put((byte) ARGB.blue(argb)).put((byte) code);
        buffer.put((byte) ARGB.red(water)).put((byte) ARGB.green(water)).put((byte) ARGB.blue(water)).put((byte) Math.round((Mth.clamp(coast, -COAST_RANGE, 1.0F) + COAST_RANGE) / (COAST_RANGE + 1.0F) * 255.0F));
        putThird(buffer, landThird, landSplit);
        putThird(buffer, waterThird, waterSplit);
    }

    private static void putThird(ByteBuffer buffer, int color, float split) {
        buffer.put((byte) ARGB.red(color)).put((byte) ARGB.green(color)).put((byte) ARGB.blue(color)).put((byte) Math.round((Mth.clamp(split, -1.0F, 1.0F) + 1.0F) * 127.5F));
    }

    /**
     * The same index list is shared by every tile because all tiles have the same topology. The surface faces up and the skirts face out
     * of the tile; rough tiles are drawn from the front only (see LodRenderer). After them come the same triangles the other way round,
     * then those of floating islands: their surface, and their underside facing down.
     */
    public static ByteBuffer buildIndices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect((2 * INDEX_COUNT + FLOATING_INDEX_COUNT) * 2).order(ByteOrder.nativeOrder());
        for (int z = 0; z < OUT_C; z++) {
            for (int x = 0; x < OUT_C; x++) {
                int v00 = x + z * OUT;
                // The diagonal alternates like a chessboard, so borders drawn across the cells look the same in every direction
                // instead of being straight one way and saw-toothed the other.
                if (((x + z) & 1) == 0) {
                    quad(buffer, v00, v00 + 1, v00 + OUT, v00 + OUT + 1);
                } else {
                    quadOtherDiagonal(buffer, v00, v00 + 1, v00 + OUT, v00 + OUT + 1);
                }
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            int skirtBase = OUT * OUT + edge * OUT;
            for (int i = 0; i < OUT_C; i++) {
                int top0 = edgeX(edge, i) + edgeZ(edge, i) * OUT;
                int top1 = edgeX(edge, i + 1) + edgeZ(edge, i + 1) * OUT;
                // Along the edges at z = 0 and x = OUT_C the samples run the other way round the tile.
                if (edge == 0 || edge == 3) {
                    quad(buffer, top1, top0, skirtBase + i + 1, skirtBase + i);
                } else {
                    quad(buffer, top0, top1, skirtBase + i, skirtBase + i + 1);
                }
            }
        }
        for (int i = 0; i < INDEX_COUNT; i += 3) {
            short a = buffer.getShort(i * 2);
            short b = buffer.getShort((i + 1) * 2);
            short c = buffer.getShort((i + 2) * 2);
            buffer.putShort(a).putShort(c).putShort(b);
        }
        for (int i = 0; i < SURFACE_INDEX_COUNT; i++) {
            buffer.putShort(buffer.getShort(i * 2));
        }
        for (int i = 0; i < SURFACE_INDEX_COUNT; i += 3) {
            buffer.putShort((short) (buffer.getShort(i * 2) + VERTEX_COUNT));
            buffer.putShort((short) (buffer.getShort((i + 2) * 2) + VERTEX_COUNT));
            buffer.putShort((short) (buffer.getShort((i + 1) * 2) + VERTEX_COUNT));
        }
        buffer.flip();
        return buffer;
    }

    /** Like {@link #quad}, split along the other diagonal, from a to d. */
    private static void quadOtherDiagonal(ByteBuffer buffer, int a, int b, int c, int d) {
        buffer.putShort((short) a).putShort((short) c).putShort((short) d);
        buffer.putShort((short) a).putShort((short) d).putShort((short) b);
    }

    private static void quad(ByteBuffer buffer, int a, int b, int c, int d) {
        buffer.putShort((short) a).putShort((short) c).putShort((short) b);
        buffer.putShort((short) b).putShort((short) c).putShort((short) d);
    }

}
