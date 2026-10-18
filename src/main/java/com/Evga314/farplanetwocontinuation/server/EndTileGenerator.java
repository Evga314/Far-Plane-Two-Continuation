package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.jspecify.annotations.Nullable;

/**
 * Distant terrain of the End: islands floating over the void. Most samples are void and are recognized from the island shape alone;
 * only samples on islands look for the top. Void samples get the height of the land next to them, so island edges stay flat, and the
 * client leaves them out.
 */
final class EndTileGenerator {
    /**
     * From this level on (256+ blocks between samples) most islands are smaller than a cell. Such samples look at a 3x3 grid and are
     * land where a good part of it is, so small islands still show up without joining into big plates.
     */
    private static final int FILTER_LEVEL = 8;
    private static final int ENOUGH_LAND = 3;
    /**
     * From this level on (8+ blocks between samples) the top of an island is worked out from the island shape and two samples of
     * the 3D noise, see {@link FloatingSurface#estimate}: a few blocks off at most. Finer rough tiles are rarely drawn at all, the
     * detailed terrain covers the area around the player.
     */
    private static final int ESTIMATE_LEVEL = 3;

    private EndTileGenerator() {
    }

    static TileData generate(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState, TileGenerator.BiomeLookup biomes, TilePos pos) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int spacing = 1 << pos.level();
        boolean filtered = pos.level() >= FILTER_LEVEL;
        boolean estimate = pos.level() >= ESTIMATE_LEVEL;
        int subStep = spacing / 3;
        int margin = filtered ? subStep : 0;
        IslandShape shape = new IslandShape(level.getSeed(), pos.minX() - margin, pos.minZ() - margin,
            pos.minX() + (long) FarPlaneConstants.TILE_CELLS * spacing + margin, pos.minZ() + (long) FarPlaneConstants.TILE_CELLS * spacing + margin);
        FloatingSurface surface = new FloatingSurface(level, generator, randomState, minY, maxY);
        short[] heights = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] biomeIndices = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        byte[] flags = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        int landX = TileGenerator.clampToWorld(pos.minX());
        int landZ = TileGenerator.clampToWorld(pos.minZ());
        int landY = minY;
        boolean anyLand = false;
        for (int sz = 0; sz < FarPlaneConstants.TILE_SAMPLES; sz++) {
            int blockZ = TileGenerator.clampToWorld(pos.minZ() + (long) sz * spacing);
            for (int sx = 0; sx < FarPlaneConstants.TILE_SAMPLES; sx++) {
                int blockX = TileGenerator.clampToWorld(pos.minX() + (long) sx * spacing);
                int index = sx + sz * FarPlaneConstants.TILE_SAMPLES;
                int top;
                if (filtered) {
                    int land = 0;
                    int sum = 0;
                    for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
                        int x = TileGenerator.clampToWorld((long) blockX + (long) (i % 3 - 1) * subStep);
                        int z = TileGenerator.clampToWorld((long) blockZ + (long) (i / 3 - 1) * subStep);
                        float islands = shape.density(x, z);
                        int subTop = estimate ? surface.estimate(x, z, islands) : surface.top(x, z, islands);
                        if (subTop != FloatingSurface.NONE) {
                            land++;
                            sum += subTop;
                        }
                    }
                    top = land >= ENOUGH_LAND ? sum / land : FloatingSurface.NONE;
                } else {
                    float islands = shape.density(blockX, blockZ);
                    top = estimate ? surface.estimate(blockX, blockZ, islands) : surface.top(blockX, blockZ, islands);
                }
                if (top == FloatingSurface.NONE) {
                    flags[index] = (byte) TileData.FLAG_VOID;
                    continue;
                }
                heights[index] = (short) top;
                if (!anyLand) {
                    anyLand = true;
                    landX = blockX;
                    landZ = blockZ;
                    landY = top - 1;
                }
            }
        }
        // Every End biome is drawn as end stone from far away, while each biome lookup computes the island shape again: one per tile.
        TileGenerator.Palette palette = new TileGenerator.Palette();
        palette.index(biomes.get(landX, landY, landZ));
        fillVoidHeights(heights, flags, minY);
        return new TileData(heights, biomeIndices, flags, palette.ids);
    }

    /** Void samples take the average height of the land around them, so the edge between an island and the void stays level. */
    private static void fillVoidHeights(short[] heights, byte[] flags, int minY) {
        int samples = FarPlaneConstants.TILE_SAMPLES;
        for (int index = 0; index < heights.length; index++) {
            if ((flags[index] & TileData.FLAG_VOID) == 0) {
                continue;
            }
            int x = index % samples;
            int z = index / samples;
            int sum = 0;
            int count = 0;
            for (int nz = Math.max(0, z - 1); nz <= Math.min(samples - 1, z + 1); nz++) {
                for (int nx = Math.max(0, x - 1); nx <= Math.min(samples - 1, x + 1); nx++) {
                    int neighbour = nx + nz * samples;
                    if ((flags[neighbour] & TileData.FLAG_VOID) == 0) {
                        sum += heights[neighbour];
                        count++;
                    }
                }
            }
            heights[index] = (short) (count > 0 ? sum / count : minY);
        }
    }

    /**
     * The island shape of the End, the same as vanilla's {@code EndIslandFunction} plus the main island. Vanilla looks at 625
     * neighbouring 16-block cells for every column to find nearby island centers; neighbouring samples of a tile share nearly all of
     * them, so the island centers of the whole tile are found once and kept in a small array.
     */
    static final class IslandShape {
        /** Largest grid of cells kept for one tile (1 byte each); coarser tiles look at the cells of each sample separately. */
        private static final int MAX_CELLS = 1 << 20;
        /** Vanilla's reach, in 16-block cells. */
        private static final int REACH = 12;
        /**
         * Reach for tiles too coarse for a grid, 256+ blocks between samples: islands farther than 8 cells only add to the void around
         * them, which at most moves an island's fringe there, so a sample looks at about half as many cells.
         */
        private static final int COARSE_REACH = 8;

        private final SimplexNoise noise;
        private final byte @Nullable [] sizes;
        private final int minCellX;
        private final int minCellZ;
        private final int width;
        private final int depth;
        private final int reach;

        IslandShape(long seed, long minX, long minZ, long maxX, long maxZ) {
            LegacyRandomSource random = new LegacyRandomSource(seed);
            random.consumeCount(17292);
            this.noise = new SimplexNoise(random, true);
            this.minCellX = cell(TileGenerator.clampToWorld(minX)) - REACH;
            this.minCellZ = cell(TileGenerator.clampToWorld(minZ)) - REACH;
            this.width = cell(TileGenerator.clampToWorld(maxX)) + REACH - this.minCellX + 1;
            this.depth = cell(TileGenerator.clampToWorld(maxZ)) + REACH - this.minCellZ + 1;
            if ((long) this.width * this.depth > MAX_CELLS) {
                this.sizes = null;
                this.reach = COARSE_REACH;
                return;
            }
            this.reach = REACH;
            this.sizes = new byte[this.width * this.depth];
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    this.sizes[x + z * this.width] = (byte) this.islandSize(this.minCellX + x, this.minCellZ + z);
                }
            }
        }

        /** The 16-block cell vanilla uses for a block coordinate: block / 8 / 2, rounded towards zero like vanilla. */
        private static int cell(int block) {
            return block / 8 / 2;
        }

        /** Size of the island centered in a cell, 0 when there is none. */
        private int islandSize(long cellX, long cellZ) {
            if (cellX * cellX + cellZ * cellZ <= 4096L || this.noise.get(cellX, cellZ) >= -0.9F) {
                return 0;
            }
            return (int) ((Mth.abs((float) cellX) * 3439.0F + Mth.abs((float) cellZ) * 147.0F) % 13.0F + 9.0F);
        }

        /** The island part of the End's density at a column, between -0.84 (void) and 0.56. */
        float density(int blockX, int blockZ) {
            float main = Mth.clamp(100.0F - Mth.sqrt((float) blockX * blockX + (float) blockZ * blockZ), -100.0F, 80.0F);
            return (Math.max(main, this.outer(blockX / 8, blockZ / 8)) - 8.0F) / 128.0F;
        }

        private float outer(int sectionX, int sectionZ) {
            int chunkX = sectionX / 2;
            int chunkZ = sectionZ / 2;
            int subSectionX = sectionX % 2;
            int subSectionZ = sectionZ % 2;
            float doffs = -100.0F;
            for (int xo = -this.reach; xo <= this.reach; xo++) {
                for (int zo = -this.reach; zo <= this.reach; zo++) {
                    int size = this.size(chunkX + xo, chunkZ + zo);
                    if (size == 0) {
                        continue;
                    }
                    float xd = subSectionX - xo * 2;
                    float zd = subSectionZ - zo * 2;
                    float value = Mth.clamp(100.0F - Mth.sqrt(xd * xd + zd * zd) * size, -100.0F, 80.0F);
                    doffs = Math.max(doffs, value);
                }
            }
            return doffs;
        }

        private int size(int cellX, int cellZ) {
            if (this.sizes != null) {
                int x = cellX - this.minCellX;
                int z = cellZ - this.minCellZ;
                if (x >= 0 && z >= 0 && x < this.width && z < this.depth) {
                    return this.sizes[x + z * this.width];
                }
            }
            return this.islandSize(cellX, cellZ);
        }
    }

    /**
     * Finds the top of floating terrain. Columns far from islands are void without sampling anything. Other columns are sampled as a
     * whole every few blocks, which computes vanilla's island shape once instead of once per height, and the top is found between the
     * highest solid sample and the air above it, where the density crosses zero.
     */
    static final class FloatingSurface {
        static final int NONE = Integer.MIN_VALUE;
        /** The island shape is at least -0.84 (pure void); islands only appear well above that. */
        private static final float VOID_ISLANDS = -0.75F;
        private static final int STEP = 8;
        /** Below this height vanilla's End terrain keeps the island shape as it is; above it, it fades towards solid air. */
        private static final float TOP_SLIDE_START = 56.0F;
        private static final float TOP_SLIDE_LENGTH = 256.0F;
        private static final float TOP_SLIDE_TARGET = 23.4375F;

        /** Heights where the 3D noise is sampled, top down: near the top of islands, in their body and lower down. */
        private static final int[] LAYERS = {60, 48, 36};

        private final DensitySampler sampler;
        /** Vanilla's 3D noise of the End, null when a data pack removed it; then the island shape alone decides. */
        private final @Nullable DensitySampler noise;
        private final SamplerContext context = SamplerContext.builder().enableCaches().build();
        private final int minY;
        private final int maxY;
        private final int size;
        private final DensityBuffer buffer;

        FloatingSurface(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState, int levelMinY, int levelMaxY) {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            this.sampler = randomState.getSampler(settings.noiseRouter().finalDensity());
            this.noise = level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).get(NoiseRouterData.BASE_3D_NOISE_END)
                .map(function -> randomState.getSampler(function.value()))
                .orElse(null);
            this.minY = Math.max(levelMinY, settings.noiseSettings().minY());
            this.maxY = Math.min(levelMaxY, settings.noiseSettings().minY() + settings.noiseSettings().height() - 1);
            this.size = Math.max(1, (this.maxY - this.minY) / STEP + 1);
            // Vanilla samplers fill as many values as the buffer holds, so it has exactly the size of the column.
            this.buffer = DensityBuffer.createUnpooled(this.size);
        }

        /**
         * The top of a column from the island shape and a few samples of vanilla's 3D noise, without the island shape being computed
         * again for every height. Up to y 56 vanilla's End terrain is the island shape plus the noise; above it moves linearly towards
         * -23.4 over 256 blocks, so with the noise near the top taken as constant it meets zero at {@code 56 + 256 * v / (v + 23.4)}.
         * Columns solid only lower down get their top between the highest solid sample and the air above it.
         */
        int estimate(int x, int z, float islands) {
            if (this.maxY < this.minY || islands < VOID_ISLANDS) {
                return NONE;
            }
            float above = Float.NaN;
            for (int i = 0; i < LAYERS.length; i++) {
                int layer = LAYERS[i];
                float value = islands + (this.noise == null ? 0.0F : this.noise.sampleValue(this.context, x, layer, z));
                if (value > 0.0F) {
                    float top = i == 0
                        ? TOP_SLIDE_START + TOP_SLIDE_LENGTH * value / (value + TOP_SLIDE_TARGET)
                        : layer + (LAYERS[i - 1] - layer) * value / (value - above);
                    return Mth.clamp(Math.round(top), this.minY + 1, this.maxY + 1);
                }
                above = value;
            }
            return NONE;
        }

        /** The y just above the highest solid block, or {@link #NONE} when there is nothing in this column. */
        int top(int x, int z, float islands) {
            if (this.maxY < this.minY || islands < VOID_ISLANDS) {
                return NONE;
            }
            DensityVolume column = new DensityVolume(1, this.size, 1, x, this.minY, z, 1, STEP, 1);
            this.sampler.sampleVolume(this.context, this.buffer, column);
            for (int i = this.size - 1; i >= 0; i--) {
                float solid = this.buffer.get(column.indexUnchecked(0, i, 0));
                if (solid <= 0.0F) {
                    continue;
                }
                int y = column.blockY(i);
                if (i == this.size - 1) {
                    return y + 1;
                }
                float air = this.buffer.get(column.indexUnchecked(0, i + 1, 0));
                float crossing = solid / (solid - air);
                return Math.min(y + 1 + (int) (crossing * STEP), this.maxY + 1);
            }
            return NONE;
        }
    }
}
