package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;

/**
 * Builds the rough surface of a tile straight from the world generator's noise. No chunks are generated or loaded, which is what
 * makes it possible to see terrain millions of blocks away. Trees, structures and player builds are not part of the result.
 */
public final class TileGenerator {
    private static final Identifier FALLBACK_BIOME = Identifier.withDefaultNamespace("plains");
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

    private TileGenerator() {
    }

    public static boolean supports(ServerLevel level) {
        return !level.dimensionType().hasCeiling();
    }

    public static TileData generate(ServerLevel level, TilePos pos) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeLookup biomes = new BiomeLookup(generator.getBiomeSource().createUncachedResolver(randomState), pos.level() < 2);
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int spacing = 1 << pos.level();
        long baseX = pos.minX();
        long baseZ = pos.minZ();

        short[] heights = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] biomeIndices = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] flags = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        Palette palette = new Palette();

        DensitySurface fast = generator instanceof NoiseBasedChunkGenerator noise ? new DensitySurface(noise, randomState, minY, maxY) : null;
        boolean estimateOnly = fast != null && pos.level() >= ESTIMATE_LEVEL && fast.estimateMatches(baseX, baseZ, FarPlaneConstants.TILE_CELLS * spacing);
        boolean filtered = fast != null && pos.level() >= FILTER_LEVEL;
        byte[] subBiomes = filtered ? new byte[FarPlaneConstants.TILE_SAMPLE_COUNT * TileData.SUB_SAMPLES] : null;
        short[] subWater = filtered ? new short[FarPlaneConstants.TILE_SAMPLE_COUNT] : null;
        int subStep = spacing / 3;

        boolean neighbourHint = spacing <= NEIGHBOUR_HINT_SPACING;
        int previousGround = Integer.MIN_VALUE;
        int rowStart = Integer.MIN_VALUE;
        for (int sz = 0; sz < FarPlaneConstants.TILE_SAMPLES; sz++) {
            int blockZ = clampToWorld(baseZ + (long) sz * spacing);
            // Rows are walked in the same direction, so a new row starts next to the start of the previous one.
            previousGround = rowStart;
            for (int sx = 0; sx < FarPlaneConstants.TILE_SAMPLES; sx++) {
                int blockX = clampToWorld(baseX + (long) sx * spacing);
                int index = sx + sz * FarPlaneConstants.TILE_SAMPLES;

                if (filtered) {
                    int water = 0;
                    int land = 0;
                    long landSum = 0;
                    int mask = 0;
                    for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                        int x = clampToWorld((long) blockX + (long) (i % 3 - 1) * subStep);
                        int z = clampToWorld((long) blockZ + (long) (i / 3 - 1) * subStep);
                        int estimate = fast.estimate(x, z);
                        int height = estimateOnly ? estimate : fast.surface(x, z, estimate);
                        boolean underWater = height < fast.seaLevel;
                        if (underWater) {
                            water++;
                            mask |= 1 << i;
                        } else {
                            land++;
                            landSum += height;
                        }
                        int biomeY = Mth.clamp(Math.max(height, fast.seaLevel) - 1, minY, maxY);
                        subBiomes[index * TileData.SUB_SAMPLES + i] = (byte) palette.index(biomes.get(x, biomeY, z));
                    }
                    boolean mostlyWater = water > land;
                    heights[index] = (short) (mostlyWater ? fast.seaLevel : (int) (landSum / land));
                    flags[index] = (byte) (mostlyWater ? TileData.FLAG_WATER : 0);
                    subWater[index] = (short) mask;
                    biomeIndices[index] = subBiomes[index * TileData.SUB_SAMPLES + 4];
                    continue;
                }

                int surfaceY = minY;
                int flag = 0;
                if (fast != null) {
                    int groundY;
                    if (estimateOnly) {
                        groundY = fast.estimate(blockX, blockZ);
                    } else {
                        boolean near = neighbourHint && previousGround != Integer.MIN_VALUE;
                        groundY = fast.surface(blockX, blockZ, near ? previousGround : fast.estimate(blockX, blockZ));
                    }
                    previousGround = groundY;
                    if (sx == 0) {
                        rowStart = groundY;
                    }
                    if (groundY < fast.seaLevel) {
                        surfaceY = fast.seaLevel;
                        flag = TileData.FLAG_WATER;
                    } else {
                        surfaceY = groundY;
                    }
                } else {
                    NoiseColumn column = generator.getBaseColumn(blockX, blockZ, level, randomState);
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
                }

                heights[index] = (short) Mth.clamp(surfaceY, Short.MIN_VALUE, Short.MAX_VALUE);
                flags[index] = (byte) flag;
                int biomeY = Mth.clamp(surfaceY - 1, minY, maxY);
                biomeIndices[index] = (byte) palette.index(biomes.get(blockX, biomeY, blockZ));
            }
        }

        return new TileData(heights, biomeIndices, flags, palette.ids, subBiomes, subWater);
    }

    /**
     * Biomes only change every 4 blocks (one quart). Fine tiles have several samples per quart, so the last answers are remembered
     * instead of running the multi-noise lookup again for each of them.
     */
    private static final class BiomeLookup {
        private final BiomeResolver resolver;
        private final @Nullable Long2ObjectOpenHashMap<Holder<Biome>> cache;

        BiomeLookup(BiomeResolver resolver, boolean cached) {
            this.resolver = resolver;
            this.cache = cached ? new Long2ObjectOpenHashMap<>() : null;
        }

        Holder<Biome> get(int x, int y, int z) {
            int quartX = QuartPos.fromBlock(x);
            int quartY = QuartPos.fromBlock(y);
            int quartZ = QuartPos.fromBlock(z);
            if (this.cache == null) {
                return this.resolver.getNoiseBiome(quartX, quartY, quartZ);
            }
            long key = BlockPos.asLong(quartX, quartY, quartZ);
            Holder<Biome> biome = this.cache.get(key);
            if (biome == null) {
                biome = this.resolver.getNoiseBiome(quartX, quartY, quartZ);
                this.cache.put(key, biome);
            }
            return biome;
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
        private final SamplerContext context = SamplerContext.builder().enableCaches().build();
        private final int minY;
        private final int maxY;
        final int seaLevel;

        DensitySurface(NoiseBasedChunkGenerator generator, RandomState randomState, int levelMinY, int levelMaxY) {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            this.sampler = randomState.getSampler(settings.noiseRouter().finalDensity());
            this.surfaceEstimate = randomState.getSampler(settings.noiseRouter().chunkSurfaceLevel());
            this.minY = Math.max(levelMinY, settings.noiseSettings().minY());
            this.maxY = Math.min(levelMaxY, settings.noiseSettings().minY() + settings.noiseSettings().height() - 1);
            this.seaLevel = settings.seaLevel();
        }

        /** Cheap 2D guess of the surface height without caves, overhangs and jagged peaks. */
        int estimate(int x, int z) {
            float value = this.surfaceEstimate.sampleValue(this.context, x, 0, z);
            if (!Float.isFinite(value)) {
                return this.seaLevel;
            }
            return Mth.clamp(Mth.floor(value), this.minY, this.maxY + 1);
        }

        /** Checks the estimate against the exact surface at a few points of a tile. Custom world presets may not provide a usable one. */
        boolean estimateMatches(long baseX, long baseZ, int size) {
            int totalError = 0;
            int samples = 0;
            for (int i = 0; i < 5; i++) {
                long offsetX = i == 4 ? size / 2 : (i & 1) * (long) size;
                long offsetZ = i == 4 ? size / 2 : (i >> 1) * (long) size;
                int x = clampToWorld(baseX + offsetX);
                int z = clampToWorld(baseZ + offsetZ);
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

    static final class Palette {
        final List<Identifier> ids = new ArrayList<>();
        private final Object2IntOpenHashMap<Holder<Biome>> indices = new Object2IntOpenHashMap<>();

        Palette() {
            this.indices.defaultReturnValue(-1);
        }

        int index(Holder<Biome> biome) {
            int index = this.indices.getInt(biome);
            if (index >= 0) {
                return index;
            }
            if (this.ids.size() >= TileData.MAX_PALETTE) {
                return 0;
            }
            index = this.ids.size();
            this.ids.add(biome.unwrapKey().map(key -> key.identifier()).orElse(FALLBACK_BIOME));
            this.indices.put(biome, index);
            return index;
        }
    }

    static int clampToWorld(long coordinate) {
        return (int) Mth.clamp(coordinate, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
    }
}
