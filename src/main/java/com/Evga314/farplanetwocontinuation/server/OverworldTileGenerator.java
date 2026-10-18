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
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
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

    private OverworldTileGenerator() {
    }

    static TileData generate(ServerLevel level, TilePos pos, ChunkGenerator generator, RandomState randomState, TileGenerator.BiomeLookup biomes) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int spacing = 1 << pos.level();
        long baseX = pos.minX();
        long baseZ = pos.minZ();

        short[] heights = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] biomeIndices = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] flags = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        TileGenerator.Palette palette = new TileGenerator.Palette();

        SamplerContext context = SamplerContext.builder().enableCaches().build();
        DensitySurface fast = generator instanceof NoiseBasedChunkGenerator noise ? new DensitySurface(noise, randomState, minY, maxY, context) : null;
        if (fast == null) {
            exactColumns(level, pos, generator, randomState, biomes, heights, biomeIndices, flags, palette);
            return new TileData(heights, biomeIndices, flags, palette.ids, null, null);
        }
        boolean filtered = pos.level() >= FILTER_LEVEL;
        // Sub-samples sit on whole quarts, so their biomes come from the same grid as their heights.
        int subStep = filtered ? Math.max(GRID_SPACING, (spacing / 3) & -GRID_SPACING) : 0;
        boolean gridded = spacing >= GRID_SPACING && insideWorld(baseX - subStep, baseZ - subStep, (S - 1L) * spacing + 2L * subStep);
        ClimateGrid climate = gridded && ClimateGrid.supported(fast.router) ? new ClimateGrid(generator, fast.router, randomState, context) : null;
        boolean estimateOnly = pos.level() >= ESTIMATE_LEVEL && fast.estimateMatches(baseX, baseZ, FarPlaneConstants.TILE_CELLS * spacing);

        if (filtered) {
            byte[] subBiomes = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT * TileData.SUB_SAMPLES];
            short[] subWater = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
            int[] land = new int[FarPlaneConstants.TILE_SAMPLE_COUNT];
            long[] landSum = new long[FarPlaneConstants.TILE_SAMPLE_COUNT];
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
                    int estimate = fast.estimate(x, z);
                    int height = estimateOnly ? estimate : fast.surface(x, z, estimate);
                    if (height < fast.seaLevel) {
                        subWater[index] |= (short) (1 << i);
                    } else {
                        land[index]++;
                        landSum[index] += height;
                    }
                    int biomeY = Mth.clamp(Math.max(height, fast.seaLevel) - 1, minY, maxY);
                    Holder<Biome> biome = climate != null ? climate.biome(index, x, biomeY, z) : biomes.get(x, biomeY, z);
                    subBiomes[index * TileData.SUB_SAMPLES + i] = (byte) palette.index(biome);
                }
            }
            for (int index = 0; index < FarPlaneConstants.TILE_SAMPLE_COUNT; index++) {
                boolean mostlyWater = land[index] * 2 < TileData.SUB_SAMPLES;
                heights[index] = (short) (mostlyWater ? fast.seaLevel : (int) (landSum[index] / land[index]));
                flags[index] = (byte) (mostlyWater ? TileData.FLAG_WATER : 0);
                biomeIndices[index] = subBiomes[index * TileData.SUB_SAMPLES + 4];
            }
            return new TileData(heights, biomeIndices, flags, palette.ids, subBiomes, subWater);
        }

        if (climate != null) {
            climate.sampleGrid(baseX, baseZ, spacing);
        }
        boolean neighbourHint = spacing <= NEIGHBOUR_HINT_SPACING;
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
                    groundY = fast.estimate(blockX, blockZ);
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
                heights[index] = (short) Mth.clamp(surfaceY, Short.MIN_VALUE, Short.MAX_VALUE);
                flags[index] = (byte) (water ? TileData.FLAG_WATER : 0);
                int biomeY = Mth.clamp(surfaceY - 1, minY, maxY);
                Holder<Biome> biome = climate != null ? climate.biome(index, blockX, biomeY, blockZ) : biomes.get(blockX, biomeY, blockZ);
                biomeIndices[index] = (byte) palette.index(biome);
            }
        }
        return new TileData(heights, biomeIndices, flags, palette.ids, null, null);
    }

    /** Generators without density functions: whole noise columns, the top block and its fluid. */
    private static void exactColumns(
        ServerLevel level, TilePos pos, ChunkGenerator generator, RandomState randomState, TileGenerator.BiomeLookup biomes, short[] heights,
        byte[] biomeIndices, byte[] flags, TileGenerator.Palette palette
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

        DensitySurface(NoiseBasedChunkGenerator generator, RandomState randomState, int levelMinY, int levelMaxY, SamplerContext context) {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            this.sampler = randomState.getSampler(settings.noiseRouter().finalDensity());
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
            int samples = 0;
            for (int i = 0; i < 5; i++) {
                long offsetX = i == 4 ? size / 2 : (i & 1) * (long) size;
                long offsetZ = i == 4 ? size / 2 : (i >> 1) * (long) size;
                int x = TileGenerator.clampToWorld(baseX + offsetX);
                int z = TileGenerator.clampToWorld(baseZ + offsetZ);
                int estimate = this.estimate(x, z);
                totalError += Math.abs(this.surface(x, z, estimate) - estimate);
                samples++;
            }
            return totalError <= MAX_ESTIMATE_ERROR * samples;
        }

        private boolean solid(int x, int y, int z) {
            return this.sampler.sampleValue(this.context, x, y, z) > 0.0F;
        }

        /**
         * Returns the y just above the highest solid block, searching from {@code hint}: steps that double in size until the surface
         * is passed, then bisection. A close hint takes about five samples, a hint hundreds of blocks off still only a dozen.
         */
        int surface(int x, int z, int hint) {
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
