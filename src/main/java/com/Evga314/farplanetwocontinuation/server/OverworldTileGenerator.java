package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.Nullable;

/**
 * Distant terrain of the overworld (and worlds like it): the highest solid ground under an open sky, with oceans at sea level.
 * <p>
 * The samples of a tile form a regular grid, so the climate the biomes come from is sampled for the whole grid at once. Everything
 * sampled point by point afterwards (the surface, the depth of a biome) goes through the same caching context, which serves the parts
 * that only depend on x and z, shared by all of them, from that grid. The surface itself is not sampled as a grid: it contains
 * interpolated noise, which vanilla samples on its full cell grid for any spacing, far too much for samples thousands of blocks apart.
 */
final class OverworldTileGenerator {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int S = FarPlaneConstants.TILE_SAMPLES;
    /**
     * From this level on (32+ blocks between samples) one sample per cell would pick random islands and hills, which looks like
     * noise from far away. Such cells are sampled on a 3x3 grid and the client averages the colors.
     */
    private static final int FILTER_LEVEL = 5;
    /** From this level on the generator's 2D surface estimate is used instead of an exact search, where it is close enough. */
    private static final int ESTIMATE_LEVEL = 5;
    /** Largest average difference between the estimate and the exact surface for the estimate to be trusted on a tile. */
    private static final int MAX_ESTIMATE_ERROR = 48;
    /**
     * Up to this sample spacing the surface search starts at the previous sample's height, which is closer than the 2D estimate on
     * fine tiles and saves both the estimate and most of the density samples. Farther apart, neighbours say little about each other.
     */
    private static final int NEIGHBOUR_HINT_SPACING = 4;
    /** Grids are sampled from this spacing on, where every sample is on its own quart and biomes can be read from the grid. */
    private static final int GRID_SPACING = 4;

    /**
     * The estimate is often a few blocks off, which hardly shows on hills but decides between land and sea on flat ground near the sea
     * level: plains and forests there would turn into sea on whole tiles. Estimates this close to the sea level are checked exactly.
     */
    private static final int SEA_CHECK = 40;

    private OverworldTileGenerator() {
    }

    private static boolean nearSea(int estimate, int seaLevel) {
        return Math.abs(estimate - seaLevel) <= SEA_CHECK;
    }

    /**
     * Corrected estimates this far under the sea level are checked exactly: the correction comes from the middle of the cells around,
     * and on a rugged coast a hill there can still be estimated a few blocks low, which would draw it as water.
     */
    private static final int SHALLOW_CHECK = 4;
    /** Largest difference between the estimate and the exact surface at any one probe for the estimate to be trusted on a tile. */
    private static final int MAX_PROBE_ERROR = 96;

    static TileData generate(ServerLevel level, TilePos pos, ChunkGenerator generator, RandomState randomState, TileGenerator.BiomeLookup biomes) {
        long cpu = ServerPerf.cpu();
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int spacing = 1 << pos.level();
        long baseX = pos.minX();
        long baseZ = pos.minZ();

        short[] heights = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] biomeIndices = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] flags = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] depths = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        TileGenerator.Palette palette = new TileGenerator.Palette();

        State state = State.of(generator, randomState, minY, maxY);
        DensitySurface fast = state.fast;
        if (fast == null) {
            exactColumns(level, pos, generator, randomState, biomes, heights, biomeIndices, flags, depths, palette);
            return new TileData(heights, biomeIndices, flags, palette.ids, null, null, depths);
        }
        boolean filtered = pos.level() >= FILTER_LEVEL;
        // Sub-samples sit on whole quarts, so their biomes come from the same grid as their heights.
        int subStep = filtered ? Math.max(GRID_SPACING, (spacing / 3) & -GRID_SPACING) : 0;
        boolean gridded = spacing >= GRID_SPACING && insideWorld(baseX - subStep, baseZ - subStep, (S - 1L) * spacing + 2L * subStep);
        ClimateGrid climate = gridded ? state.climate : null;
        // Far tiles look for the surface in the terrain without caves, which is much cheaper to sample, see State.far.
        DensitySurface far = state.far;
        boolean estimateOnly = pos.level() >= ESTIMATE_LEVEL && fast.estimateMatches(baseX, baseZ, FarPlaneConstants.TILE_CELLS * spacing);

        if (filtered) {
            byte[] subBiomes = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT * TileData.SUB_SAMPLES];
            short[] subWater = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
            int[] land = state.land();
            long[] landSum = state.landSum();
            // Sum of how deep the water is at the water sub-samples of each cell, for the average.
            int[] depthSum = state.depthSum();
            // Where the estimate is close to the sea level, the exact surface is found at the middle of the cell: one exact search per
            // cell instead of nine. The estimates of the sub-samples are corrected by the difference there, see subCorrection.
            int[] correction = state.correction();
            boolean[] corrected = state.corrected();
            IcebergPillars pillars = state.pillars;
            boolean[] icebergCells = state.icebergCells();
            // The estimates of all sub-samples first: which cells need an exact look depends on them. Each is written below.
            int[] estimates = state.estimates;
            for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                WorkThrottle.yieldToFrames();
                long offsetX = (long) (i % 3 - 1) * subStep;
                long offsetZ = (long) (i / 3 - 1) * subStep;
                for (int index = 0; index < FarPlaneConstants.TILE_SAMPLE_COUNT; index++) {
                    int x = TileGenerator.clampToWorld(baseX + (long) (index % S) * spacing + offsetX);
                    int z = TileGenerator.clampToWorld(baseZ + (long) (index / S) * spacing + offsetZ);
                    estimates[i * FarPlaneConstants.TILE_SAMPLE_COUNT + index] = fast.estimate(x, z);
                }
            }
            if (estimateOnly) {
                correctNearSea(far, baseX, baseZ, spacing, estimates, correction, corrected);
            }
            // One sub-sample position of every cell at a time: each forms a regular grid of its own.
            for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                WorkThrottle.yieldToFrames();
                long offsetX = (long) (i % 3 - 1) * subStep;
                long offsetZ = (long) (i / 3 - 1) * subStep;
                if (climate != null) {
                    climate.sampleGrid(baseX + offsetX, baseZ + offsetZ, spacing);
                }
                for (int index = 0; index < FarPlaneConstants.TILE_SAMPLE_COUNT; index++) {
                    int x = TileGenerator.clampToWorld(baseX + (long) (index % S) * spacing + offsetX);
                    int z = TileGenerator.clampToWorld(baseZ + (long) (index / S) * spacing + offsetZ);
                    int estimate = estimates[i * FarPlaneConstants.TILE_SAMPLE_COUNT + index];
                    int height;
                    if (estimateOnly) {
                        height = estimate + subCorrection(correction, corrected, index, i);
                        // Water or land is all that matters there: one look at the block under the sea level instead of a search.
                        if (height < fast.seaLevel && height >= fast.seaLevel - SHALLOW_CHECK && far.solid(x, fast.seaLevel - 1, z)) {
                            height = fast.seaLevel;
                        }
                    } else {
                        height = far.surface(x, z, estimate);
                    }
                    if (height < fast.seaLevel) {
                        subWater[index] |= (short) (1 << i);
                        depthSum[index] += fast.seaLevel - height;
                    } else {
                        land[index]++;
                        landSum[index] += height;
                    }
                    int biomeY = Mth.clamp(Math.max(height, fast.seaLevel) - 1, minY, maxY);
                    Holder<Biome> biome = climate != null ? climate.biome(index, x, biomeY, z) : biomes.get(x, biomeY, z);
                    subBiomes[index * TileData.SUB_SAMPLES + i] = (byte) palette.index(biome);
                    if (height < fast.seaLevel) {
                        int pillar = pillars.at(x, z, biome) >> 2;
                        if (pillar > 0) {
                            // A pillar of ice out of the frozen ocean: land after all.
                            subWater[index] &= (short) ~(1 << i);
                            depthSum[index] -= fast.seaLevel - height;
                            land[index]++;
                            landSum[index] += fast.seaLevel + pillar;
                            icebergCells[index] = true;
                        }
                    }
                }
            }
            for (int index = 0; index < FarPlaneConstants.TILE_SAMPLE_COUNT; index++) {
                boolean mostlyWater = land[index] * 2 < TileData.SUB_SAMPLES;
                heights[index] = (short) (mostlyWater ? fast.seaLevel : (int) (landSum[index] / land[index]));
                flags[index] = (byte) ((mostlyWater ? TileData.FLAG_WATER : 0) | (icebergCells[index] ? TileData.FLAG_ICEBERG : 0));
                int waterCount = Integer.bitCount(subWater[index] & 0xFFFF);
                depths[index] = mostlyWater && waterCount > 0 ? depthByte(depthSum[index] / waterCount) : 0;
                biomeIndices[index] = subBiomes[index * TileData.SUB_SAMPLES + 4];
            }
            if (ServerPerf.ON) {
                (estimateOnly ? ServerPerf.ROUGH_ESTIMATED : ServerPerf.ROUGH_EXACT).add(ServerPerf.cpu() - cpu);
            }
            return new TileData(heights, biomeIndices, flags, palette.ids, subBiomes, subWater, depths);
        }

        if (climate != null) {
            climate.sampleGrid(baseX, baseZ, spacing);
        }
        boolean neighbourHint = spacing <= NEIGHBOUR_HINT_SPACING;
        // Vanilla's icebergs, where they are; coarse tiles leave them out, they would only be specks.
        // Each sample takes the highest iceberg of the cell around it, so the field reaches half a cell past the tile.
        int cellStart = -(spacing / 2);
        long icebergCpu = ServerPerf.cpu();
        VanillaIcebergs.Field icebergs = VanillaIcebergs.collect(
            level.getSeed(), minY, generator, biomes, baseX + cellStart, baseZ + cellStart, pos.size() + spacing
        );
        if (ServerPerf.ON) {
            ServerPerf.ICEBERGS.add(ServerPerf.cpu() - icebergCpu);
        }
        IcebergPillars pillars = state.pillars;
        int previousGround = Integer.MIN_VALUE;
        int rowStart = Integer.MIN_VALUE;
        for (int sz = 0; sz < S; sz++) {
            WorkThrottle.yieldToFrames();
            int blockZ = TileGenerator.clampToWorld(baseZ + (long) sz * spacing);
            // Rows are walked in the same direction, so a new row starts next to the start of the previous one.
            previousGround = rowStart;
            for (int sx = 0; sx < S; sx++) {
                int blockX = TileGenerator.clampToWorld(baseX + (long) sx * spacing);
                int index = sx + sz * S;
                int groundY;
                if (estimateOnly) {
                    int estimate = fast.estimate(blockX, blockZ);
                    groundY = nearSea(estimate, fast.seaLevel) ? fast.surface(blockX, blockZ, estimate) : estimate;
                } else if (neighbourHint && previousGround != Integer.MIN_VALUE) {
                    groundY = fast.surface(blockX, blockZ, previousGround);
                } else {
                    groundY = fast.surface(blockX, blockZ, fast.estimate(blockX, blockZ));
                }
                previousGround = groundY;
                if (sx == 0) {
                    rowStart = groundY;
                }
                boolean water = groundY < fast.seaLevel;
                int surfaceY = water ? fast.seaLevel : groundY;
                int biomeY = Mth.clamp(surfaceY - 1, minY, maxY);
                Holder<Biome> biome = climate != null ? climate.biome(index, blockX, biomeY, blockZ) : biomes.get(blockX, biomeY, blockZ);
                biomeIndices[index] = (byte) palette.index(biome);
                int flag = water ? TileData.FLAG_WATER : 0;
                int highest = water && icebergs != null ? icebergs.highest(blockX + cellStart, blockZ + cellStart, spacing) : 0;
                if (water) {
                    int pillar = pillars.at(blockX, blockZ, biome);
                    if (pillar >> 2 > highest >> 2) {
                        highest = pillar;
                    }
                }
                int berg = highest >> 2;
                if (berg > 0) {
                    surfaceY = fast.seaLevel + berg;
                    int top = highest & 3;
                    flag = TileData.FLAG_ICEBERG | (top == VanillaIcebergs.SNOW ? TileData.FLAG_ICEBERG_SNOW : 0)
                        | (top == VanillaIcebergs.BLUE ? TileData.FLAG_BLUE_ICE : 0);
                }
                heights[index] = (short) Mth.clamp(surfaceY, Short.MIN_VALUE, Short.MAX_VALUE);
                flags[index] = (byte) flag;
                depths[index] = flag == TileData.FLAG_WATER ? depthByte(fast.seaLevel - groundY) : 0;
            }
        }
        if (ServerPerf.ON) {
            ServerPerf.ROUGH_NEAR.add(ServerPerf.cpu() - cpu);
        }
        return new TileData(heights, biomeIndices, flags, palette.ids, null, null, depths);
    }

    /** A water depth as {@link TileData#waterDepths} keeps it: at least 1 block, as 0 means none, and at most 255. */
    private static byte depthByte(int depth) {
        return (byte) Mth.clamp(depth, 1, 255);
    }

    /**
     * Vanilla's pillars of ice on frozen oceans, which its surface builds from noise (MaterialSystem.frozenOceanExtension): most of
     * the big icebergs. They depend on the position alone, so they are taken the same way here, as their height above the sea and
     * the kind of their top (see VanillaIcebergs) in one int, height times 4 plus kind. Snow lies on the tops of the tall ones;
     * vanilla draws its exact depth from a random of the column, this takes its middle.
     */
    private static final class IcebergPillars {
        private final Noise surface;
        private final Noise pillar;
        private final Noise roof;
        private final int seaLevel;
        private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        IcebergPillars(RandomState randomState, int seaLevel) {
            this.surface = randomState.getOrCreateNoise(Noises.ICEBERG_SURFACE);
            this.pillar = randomState.getOrCreateNoise(Noises.ICEBERG_PILLAR);
            this.roof = randomState.getOrCreateNoise(Noises.ICEBERG_PILLAR_ROOF);
            this.seaLevel = seaLevel;
        }

        int at(int x, int z, Holder<Biome> biome) {
            if (!biome.is(Biomes.FROZEN_OCEAN) && !biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                return 0;
            }
            double iceberg = Math.min(Math.abs(this.surface.get(x, 0.0, z) * 8.25), this.pillar.get(x * 1.28, 0.0, z * 1.28) * 15.0F);
            if (iceberg <= 1.8) {
                return 0;
            }
            double roofHeight = Math.abs(this.roof.get(x * 1.17, 0.0, z * 1.17) * 1.5);
            double top = Math.min(iceberg * iceberg * 1.2, Math.ceil(roofHeight * 40.0) + 14.0);
            if (biome.value().shouldMeltFrozenOceanIcebergSlightly(this.scratch.set(x, this.seaLevel, z), this.seaLevel)) {
                top -= 2.0;
            }
            if (top <= 2.0) {
                return 0;
            }
            int height = (int) (top + this.seaLevel) - this.seaLevel;
            if (height <= 0) {
                return 0;
            }
            boolean snow = this.seaLevel + height - 1 > this.seaLevel + PILLAR_SNOW_ABOVE;
            return height * 4 + (snow ? VanillaIcebergs.SNOW : VanillaIcebergs.PACKED);
        }
    }

    /** Pillar tops higher than this over the sea have snow, the middle of vanilla's 18 to 27. */
    private static final int PILLAR_SNOW_ABOVE = 22;

    /**
     * Where the estimate is close to the sea level, the exact surface is found at the middle of the cell: the difference corrects the
     * estimates of its sub-samples, see subCorrection. A cell next to one near the sea level can reach it with its sub-samples, so it
     * is checked too. Samples on the tile edges only look at themselves: the next tile shares them and must find the same.
     */
    private static void correctNearSea(
        DensitySurface fast, long baseX, long baseZ, int spacing, int[] estimates, int[] correction, boolean[] corrected
    ) {
        int count = FarPlaneConstants.TILE_SAMPLE_COUNT;
        // The middle sub-sample is the cell's own sample.
        int middle = 4 * count;
        for (int index = 0; index < count; index++) {
            int sx = index % S;
            int sz = index / S;
            boolean check = nearSea(estimates[middle + index], fast.seaLevel);
            if (!isEdge(sx, sz)) {
                for (int nz = sz - 1; nz <= sz + 1 && !check; nz++) {
                    for (int nx = sx - 1; nx <= sx + 1; nx++) {
                        if (nearSea(estimates[middle + nx + nz * S], fast.seaLevel)) {
                            check = true;
                            break;
                        }
                    }
                }
            }
            if (!check) {
                continue;
            }
            if (sx == 0) {
                WorkThrottle.yieldToFrames();
            }
            int x = TileGenerator.clampToWorld(baseX + (long) sx * spacing);
            int z = TileGenerator.clampToWorld(baseZ + (long) sz * spacing);
            int estimate = estimates[middle + index];
            correction[index] = fast.surface(x, z, estimate) - estimate;
            corrected[index] = true;
        }
    }

    /**
     * What generating tiles needs that stays the same from tile to tile, kept per generator thread instead of made for every tile: the
     * samplers of the generator's density functions with their cache, the climate grid and its buffers, and the arrays of the far
     * tiles that do not leave the generator. It belongs to one generator of one world; another one makes a new state.
     */
    private static final class State {
        private static final ThreadLocal<@Nullable State> CURRENT = new ThreadLocal<>();

        final ChunkGenerator generator;
        final RandomState randomState;
        final int minY;
        final int maxY;
        final @Nullable DensitySurface fast;
        /**
         * The surface of far tiles: the generator's terrain without its caves (vanilla's sloped cheese, which the final density
         * takes the caves out of). Near the sea level the two only differ where a cave breaks through the surface, which far tiles,
         * 32 blocks and more between samples, cannot show anyway; the caves are most of the work of the final density. Without such a
         * function (other world presets) it is the final density.
         */
        final @Nullable DensitySurface far;
        final @Nullable ClimateGrid climate;
        final @Nullable IcebergPillars pillars;
        private final int[] land = new int[FarPlaneConstants.TILE_SAMPLE_COUNT];
        private final long[] landSum = new long[FarPlaneConstants.TILE_SAMPLE_COUNT];
        private final int[] depthSum = new int[FarPlaneConstants.TILE_SAMPLE_COUNT];
        private final int[] correction = new int[FarPlaneConstants.TILE_SAMPLE_COUNT];
        private final boolean[] corrected = new boolean[FarPlaneConstants.TILE_SAMPLE_COUNT];
        private final boolean[] icebergCells = new boolean[FarPlaneConstants.TILE_SAMPLE_COUNT];
        final int[] estimates = new int[FarPlaneConstants.TILE_SAMPLE_COUNT * TileData.SUB_SAMPLES];

        private State(ChunkGenerator generator, RandomState randomState, int minY, int maxY) {
            this.generator = generator;
            this.randomState = randomState;
            this.minY = minY;
            this.maxY = maxY;
            SamplerContext context = SamplerContext.builder().enableCaches().build();
            if (generator instanceof NoiseBasedChunkGenerator noise) {
                NoiseRouter router = noise.generatorSettings().value().noiseRouter();
                this.fast = new DensitySurface(noise, randomState, minY, maxY, context, router.finalDensity());
                DensityFunction terrain = terrainWithoutCaves(router.finalDensity());
                // A context of its own: its caches are those of another sampler.
                DensitySurface far = terrain != null
                    ? new DensitySurface(noise, randomState, minY, maxY, SamplerContext.builder().enableCaches().build(), terrain)
                    : this.fast;
                if (terrain != null && !terrainMatches(far, this.fast)) {
                    // A worldgen pack can give another shape the name of the terrain without caves; far tiles then fall back
                    // to the final density: slower, never the wrong surface. Warned once per run and not only in debug builds:
                    // a wrong surface would be silent otherwise.
                    if (TERRAIN_REJECTED.compareAndSet(false, true)) {
                        LOGGER.warn("[FarPlane terrain] the density function named like sloped cheese is not the terrain without caves; far tiles use the final density");
                    }
                    far = this.fast;
                }
                if (ServerPerf.ON) {
                    LOGGER.info("[FarPlane terrain] far tiles look for the surface in {}", far != this.fast ? terrain : "the final density");
                }
                this.far = far;
                this.climate = ClimateGrid.supported(router) ? new ClimateGrid(generator, router, randomState, context) : null;
                this.pillars = new IcebergPillars(randomState, this.fast.seaLevel);
            } else {
                this.fast = null;
                this.far = null;
                this.climate = null;
                this.pillars = null;
            }
        }

        static State of(ChunkGenerator generator, RandomState randomState, int minY, int maxY) {
            State state = CURRENT.get();
            if (state == null || state.generator != generator || state.randomState != randomState || state.minY != minY || state.maxY != maxY) {
                state = new State(generator, randomState, minY, maxY);
                CURRENT.set(state);
            }
            return state;
        }

        int[] land() {
            java.util.Arrays.fill(this.land, 0);
            return this.land;
        }

        int[] depthSum() {
            java.util.Arrays.fill(this.depthSum, 0);
            return this.depthSum;
        }

        long[] landSum() {
            java.util.Arrays.fill(this.landSum, 0L);
            return this.landSum;
        }

        int[] correction() {
            java.util.Arrays.fill(this.correction, 0);
            return this.correction;
        }

        boolean[] corrected() {
            java.util.Arrays.fill(this.corrected, false);
            return this.corrected;
        }

        boolean[] icebergCells() {
            java.util.Arrays.fill(this.icebergCells, false);
            return this.icebergCells;
        }

        /** The named terrain without caves inside the final density, see {@link #far}; null when there is none. */
        private static @Nullable DensityFunction terrainWithoutCaves(DensityFunction finalDensity) {
            DensityFunction[] found = new DensityFunction[1];
            DfRewriteRule finder = new DfRewriteRule() {
                @Override
                public DensityFunction rewrite(DensityFunction function) {
                    if (found[0] != null) {
                        return function;
                    }
                    if (function instanceof DensityFunctions.HolderHolder holder && holder.function().unwrapKey()
                        .map(key -> key.identifier().getPath().endsWith(TERRAIN_WITHOUT_CAVES)).orElse(false)) {
                        found[0] = function;
                        return function;
                    }
                    function.rewriteChildren(this);
                    return function;
                }
            };
            try {
                finder.rewrite(finalDensity);
            } catch (RuntimeException e) {
                return null;
            }
            return found[0];
        }

        /**
         * Whether a candidate really is the terrain without caves, probed against the final density at spread columns. Caves
         * only carve down, so it is never under the real surface, beyond the few blocks structure terrain adaptation can add
         * to the real one; and it is far above the real surface only where caves break through, which is not everywhere.
         */
        private static boolean terrainMatches(DensitySurface candidate, DensitySurface fast) {
            int[][] probes = {{0, 0}, {1531, -977}, {-2048, 511}, {977, 2047}, {-511, -1531}, {3071, 1531}, {-3071, -2047}, {511, -3071}};
            int tooHigh = 0;
            for (int[] probe : probes) {
                int hint = fast.estimate(probe[0], probe[1]);
                int real = fast.surface(probe[0], probe[1], hint);
                int found = candidate.surface(probe[0], probe[1], hint);
                if (found < real - 4) {
                    return false;
                }
                if (found > real + 32) {
                    tooHigh++;
                }
            }
            return tooHigh < 3;
        }

        /** Set when a named terrain without caves was rejected, so the warning is written once. */
        private static final java.util.concurrent.atomic.AtomicBoolean TERRAIN_REJECTED = new java.util.concurrent.atomic.AtomicBoolean();
        private static final String TERRAIN_WITHOUT_CAVES = "/sloped_cheese";
    }

    private static boolean isEdge(int sx, int sz) {
        return sx == 0 || sz == 0 || sx == S - 1 || sz == S - 1;
    }

    /**
     * The correction of the estimate at sub-sample {@code i} of a cell: blended from the corrections at the middle of the cells it lies
     * between, by how far it lies towards them (a third of a cell), as the error of the estimate changes over a cell on rugged
     * ground. Cells without a correction count with the cell's own. Samples on tile edges keep their own correction, see generate.
     */
    private static int subCorrection(int[] correction, boolean[] corrected, int index, int i) {
        int sx = index % S;
        int sz = index / S;
        int own = correction[index];
        if (isEdge(sx, sz)) {
            return own;
        }
        int dx = i % 3 - 1;
        int dz = i / 3 - 1;
        float wx = Math.abs(dx) / 3.0F;
        float wz = Math.abs(dz) / 3.0F;
        int across = index + dx;
        int down = index + dz * S;
        int diagonal = index + dx + dz * S;
        float blended = (1.0F - wx) * (1.0F - wz) * own
            + wx * (1.0F - wz) * (corrected[across] ? correction[across] : own)
            + (1.0F - wx) * wz * (corrected[down] ? correction[down] : own)
            + wx * wz * (corrected[diagonal] ? correction[diagonal] : own);
        return Math.round(blended);
    }

    /** Generators without density functions: whole noise columns, the top block and its fluid. */
    private static void exactColumns(
        ServerLevel level, TilePos pos, ChunkGenerator generator, RandomState randomState, TileGenerator.BiomeLookup biomes, short[] heights,
        byte[] biomeIndices, byte[] flags, byte[] depths, TileGenerator.Palette palette
    ) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int spacing = 1 << pos.level();
        for (int index = 0; index < FarPlaneConstants.TILE_SAMPLE_COUNT; index++) {
            int blockX = TileGenerator.clampToWorld(pos.minX() + (long) (index % S) * spacing);
            int blockZ = TileGenerator.clampToWorld(pos.minZ() + (long) (index / S) * spacing);
            NoiseColumn column = generator.getBaseColumn(blockX, blockZ, level, randomState);
            int surfaceY = minY;
            int flag = 0;
            for (int y = maxY; y >= minY; y--) {
                BlockState state = column.getBlock(y);
                if (state.isAir()) {
                    continue;
                }
                surfaceY = y + 1;
                if (state.is(Blocks.WATER)) {
                    flag = TileData.FLAG_WATER;
                    int ground = y;
                    while (ground > minY && column.getBlock(ground).is(Blocks.WATER)) {
                        ground--;
                    }
                    depths[index] = depthByte(surfaceY - ground - 1);
                } else if (state.is(Blocks.LAVA)) {
                    flag = TileData.FLAG_LAVA;
                }
                break;
            }
            heights[index] = (short) Mth.clamp(surfaceY, Short.MIN_VALUE, Short.MAX_VALUE);
            flags[index] = (byte) flag;
            biomeIndices[index] = (byte) palette.index(biomes.get(blockX, Mth.clamp(surfaceY - 1, minY, maxY), blockZ));
        }
    }

    /** Whether a square of samples lies inside the world, where no sample is moved onto the border. */
    private static boolean insideWorld(long minX, long minZ, long size) {
        return minX >= -FarPlaneConstants.WORLD_LIMIT && minZ >= -FarPlaneConstants.WORLD_LIMIT
            && minX + size <= FarPlaneConstants.WORLD_LIMIT && minZ + size <= FarPlaneConstants.WORLD_LIMIT;
    }

    private static DensityVolume grid(long minX, long minZ, int spacing) {
        return new DensityVolume(S, 1, S, (int) minX, 0, (int) minZ, spacing, 1, spacing);
    }

    /**
     * The climate behind the biomes on a grid of samples. Climate values that do not depend on the height are read from the grid; the
     * rest (depth, which picks cave biomes under the surface) is sampled at each point, which reuses the cached grid for its flat parts.
     */
    private static final class ClimateGrid {
        private final Climate.Sampler sampler;
        private final DensitySampler.Bound[] parameters;
        private final boolean[] flat = new boolean[6];
        private final DensityBuffer[] buffers = new DensityBuffer[6];
        private final @Nullable MultiNoiseBiomeSource multiNoise;
        private final BiomeResolver resolver;

        ClimateGrid(ChunkGenerator generator, NoiseRouter router, RandomState randomState, SamplerContext context) {
            this.sampler = randomState.createClimateSampler(context);
            this.parameters = new DensitySampler.Bound[] {
                this.sampler.temperature(), this.sampler.humidity(), this.sampler.continentalness(), this.sampler.erosion(), this.sampler.depth(),
                this.sampler.weirdness()
            };
            DensityFunction[] functions = {router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges()};
            for (int i = 0; i < 6; i++) {
                this.flat[i] = (functions[i].domainAxes() & DensityFunction.AXIS_Y) == 0;
                this.buffers[i] = DensityBuffer.createUnpooled(FarPlaneConstants.TILE_SAMPLE_COUNT);
            }
            BiomeSource source = generator.getBiomeSource();
            this.multiNoise = source instanceof MultiNoiseBiomeSource multi ? multi : null;
            this.resolver = source.createResolver(this.sampler);
        }

        /**
         * Whether the climate can be sampled on a grid: vanilla's climate is plain noise and splines. Interpolated noise in it (from
         * a data pack) would be sampled on its full cell grid, see the class comment.
         */
        static boolean supported(NoiseRouter router) {
            boolean[] interpolated = new boolean[1];
            DfRewriteRule finder = new DfRewriteRule() {
                @Override
                public DensityFunction rewrite(DensityFunction function) {
                    if (function instanceof InterpolatedFunction) {
                        interpolated[0] = true;
                    } else if (!interpolated[0]) {
                        function.rewriteChildren(this);
                    }
                    return function;
                }
            };
            for (DensityFunction function : new DensityFunction[] {
                router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges()
            }) {
                finder.rewrite(function);
            }
            return !interpolated[0];
        }

        /** Samples every parameter on the grid; the ones that depend on height only so their flat parts land in the cache. */
        void sampleGrid(long minX, long minZ, int spacing) {
            DensityVolume volume = grid(minX, minZ, spacing);
            for (int i = 0; i < 6; i++) {
                this.parameters[i].sampleVolume(this.buffers[i], volume);
            }
        }

        /** The biome at a sample of the last grid, at the given height. */
        Holder<Biome> biome(int index, int x, int y, int z) {
            if (this.multiNoise == null) {
                return this.resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            }
            int blockY = QuartPos.toBlock(QuartPos.fromBlock(y));
            return this.multiNoise.getNoiseBiome(Climate.target(
                this.value(0, index, x, blockY, z), this.value(1, index, x, blockY, z), this.value(2, index, x, blockY, z),
                this.value(3, index, x, blockY, z), this.value(4, index, x, blockY, z), this.value(5, index, x, blockY, z)
            ));
        }

        private float value(int parameter, int index, int x, int y, int z) {
            return this.flat[parameter] ? this.buffers[parameter].get(index) : this.parameters[parameter].sampleValue(x, y, z);
        }
    }

    /**
     * Finds the terrain surface by sampling the generator's final density at single points instead of building a whole noise column.
     * The search starts next to the neighbouring sample's height, so it usually needs only a handful of samples. Density above zero
     * means solid. Aquifers are skipped: anything below sea level counts as ocean.
     */
    static final class DensitySurface {
        private static final int STEP = 8;

        private final DensitySampler sampler;
        private final DensitySampler surfaceEstimate;
        private final SamplerContext context;
        final NoiseRouter router;
        private final int minY;
        private final int maxY;
        final int seaLevel;

        /** @param density where it is above zero is solid: the final density, or for far tiles the terrain without caves */
        DensitySurface(
            NoiseBasedChunkGenerator generator, RandomState randomState, int levelMinY, int levelMaxY, SamplerContext context, DensityFunction density
        ) {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            this.sampler = randomState.getSampler(density);
            this.surfaceEstimate = randomState.getSampler(settings.noiseRouter().chunkSurfaceLevel());
            this.context = context;
            this.router = settings.noiseRouter();
            this.minY = Math.max(levelMinY, settings.noiseSettings().minY());
            this.maxY = Math.min(levelMaxY, settings.noiseSettings().minY() + settings.noiseSettings().height() - 1);
            this.seaLevel = settings.seaLevel();
        }

        /** Cheap 2D guess of the surface height without caves, overhangs and jagged peaks. */
        int estimate(int x, int z) {
            return this.toHeight(this.surfaceEstimate.sampleValue(this.context, x, 0, z));
        }

        private int toHeight(float value) {
            return Float.isFinite(value) ? Mth.clamp(Mth.floor(value), this.minY, this.maxY + 1) : this.seaLevel;
        }

        /** Checks the estimate against the exact surface at a few points of a tile. Custom world presets may not provide a usable one. */
        boolean estimateMatches(long baseX, long baseZ, int size) {
            int totalError = 0;
            int maxError = 0;
            int samples = 0;
            for (int i = 0; i < 5; i++) {
                long offsetX = i == 4 ? size / 2 : (i & 1) * (long) size;
                long offsetZ = i == 4 ? size / 2 : (i >> 1) * (long) size;
                int x = TileGenerator.clampToWorld(baseX + offsetX);
                int z = TileGenerator.clampToWorld(baseZ + offsetZ);
                int estimate = this.estimate(x, z);
                int error = Math.abs(this.surface(x, z, estimate) - estimate);
                totalError += error;
                maxError = Math.max(maxError, error);
                samples++;
            }
            // One badly estimated probe means the estimate goes badly wrong somewhere on the tile, even when the others are right.
            return totalError <= MAX_ESTIMATE_ERROR * samples && maxError <= MAX_PROBE_ERROR;
        }

        private boolean solid(int x, int y, int z) {
            return this.sampler.sampleValue(this.context, x, y, z) > 0.0F;
        }

        /**
         * Returns the y just above the highest solid block, searching from {@code hint}: steps that double in size until the surface
         * is passed, then bisection. A close hint takes about five samples, a hint hundreds of blocks off still only a dozen.
         */
        int surface(int x, int z, int hint) {
            if (ServerPerf.ON) {
                ServerPerf.SURFACE_SEARCHES.incrementAndGet();
            }
            if (this.maxY < this.minY) {
                return this.minY;
            }
            int guess = Mth.clamp(hint, this.minY, this.maxY);
            int solidY;
            int airY;
            int step = STEP;
            if (this.solid(x, guess, z)) {
                solidY = guess;
                while (true) {
                    if (solidY >= this.maxY) {
                        return this.maxY + 1;
                    }
                    int probe = Math.min(solidY + step, this.maxY);
                    if (!this.solid(x, probe, z)) {
                        airY = probe;
                        break;
                    }
                    solidY = probe;
                    step <<= 1;
                }
            } else {
                airY = guess;
                while (true) {
                    if (airY <= this.minY) {
                        return this.minY;
                    }
                    int probe = Math.max(airY - step, this.minY);
                    if (this.solid(x, probe, z)) {
                        solidY = probe;
                        break;
                    }
                    airY = probe;
                    step <<= 1;
                }
            }
            while (airY - solidY > 1) {
                int middle = (solidY + airY) >> 1;
                if (this.solid(x, middle, z)) {
                    solidY = middle;
                } else {
                    airY = middle;
                }
            }
            return Mth.clamp(solidY + 1, this.minY, this.maxY + 1);
        }
    }
}
