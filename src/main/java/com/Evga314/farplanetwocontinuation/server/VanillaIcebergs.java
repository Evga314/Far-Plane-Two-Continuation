package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneDebug;
import com.Evga314.farplanetwocontinuation.mixin.common.ChunkGeneratorAccess;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.IcebergFeature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Vanilla's icebergs for the distant terrain. They are features, which the noise knows nothing about, but where they stand and how
 * they look depends only on the world seed and the chunk: every chunk seeds its decoration from them, and every feature of a step its
 * own random from that. So the same random is set up here for every chunk of an area, run through the iceberg's placement (how rare
 * it is, where in the chunk, in which biomes) and through {@link IcebergFeature}'s shape, rewritten to fill a small grid of its own
 * instead of the world. Its height above the sea is what the distant terrain needs.
 * <p>
 * What the grid does not know: the ground under the water (taken as water all the way down) and other features of the chunk. Under
 * water this can change the shape a little, and with it a notch cut out of some icebergs; above the water they are vanilla's.
 */
final class VanillaIcebergs {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Kinds of the top block of an iceberg column, see {@link Field#top}. */
    static final byte NONE = 0;
    static final byte PACKED = 1;
    static final byte BLUE = 2;
    static final byte SNOW = 3;

    /** An iceberg feature of the generator: its placement and where it is in the decoration order. */
    private record Kind(PlacedFeature feature, boolean blue, int step, int index) {
    }

    private static final Map<ChunkGenerator, List<Kind>> KINDS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Icebergs reach this far from where they are placed, and that is up to a chunk away from the chunk they belong to. */
    private static final int REACH = 24;

    private VanillaIcebergs() {
    }

    /** The heights of vanilla's icebergs above the water over a square of the world, column by column. */
    static final class Field {
        private final long minX;
        private final long minZ;
        private final int size;
        private final byte[] height;
        private final byte[] top;
        boolean any;

        Field(long minX, long minZ, int size) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.height = new byte[size * size];
            this.top = new byte[size * size];
        }

        /** How many blocks of iceberg stand above the water in a column, 0 without one. */
        int height(int x, int z) {
            long dx = x - this.minX;
            long dz = z - this.minZ;
            if (dx < 0 || dz < 0 || dx >= this.size || dz >= this.size) {
                return 0;
            }
            return this.height[(int) dx + (int) dz * this.size];
        }

        /**
         * The highest iceberg in a square of columns from {@code x}, {@code z}, {@code side} blocks across, as height and kind of its
         * top (see {@link #NONE} and the others) in one int: height times 4 plus kind. A sample of the distant terrain stands for a
         * whole cell, and a small iceberg lying between samples would otherwise be lost or cut.
         */
        int highest(int x, int z, int side) {
            int best = 0;
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    int height = this.height(x + dx, z + dz);
                    if (height * 4 > best) {
                        best = height * 4 + this.top(x + dx, z + dz);
                    }
                }
            }
            return best;
        }

        /** The kind of block on top of a column, see {@link VanillaIcebergs#NONE} and the others. */
        byte top(int x, int z) {
            long dx = x - this.minX;
            long dz = z - this.minZ;
            if (dx < 0 || dz < 0 || dx >= this.size || dz >= this.size) {
                return NONE;
            }
            return this.top[(int) dx + (int) dz * this.size];
        }

        private void add(int x, int z, int columnHeight, byte kind) {
            long dx = x - this.minX;
            long dz = z - this.minZ;
            if (dx < 0 || dz < 0 || dx >= this.size || dz >= this.size || columnHeight <= this.height[(int) dx + (int) dz * this.size]) {
                return;
            }
            this.height[(int) dx + (int) dz * this.size] = (byte) columnHeight;
            this.top[(int) dx + (int) dz * this.size] = kind;
            this.any = true;
        }
    }

    /**
     * The icebergs over the square from {@code minX}, {@code minZ} of {@code size} blocks, or null when the generator places none in
     * a way this can follow.
     */
    static @Nullable Field collect(long seed, int minY, ChunkGenerator generator, TileGenerator.BiomeLookup biomes, long minX, long minZ, int size) {
        List<Kind> kinds = kinds(generator);
        if (kinds.isEmpty()) {
            return null;
        }
        Field field = new Field(minX, minZ, size);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        Grid grid = null;
        int minChunkX = (int) Math.floorDiv(minX - REACH, 16L);
        int minChunkZ = (int) Math.floorDiv(minZ - REACH, 16L);
        int maxChunkX = (int) Math.floorDiv(minX + size + REACH, 16L);
        int maxChunkZ = (int) Math.floorDiv(minZ + size + REACH, 16L);
        List<BlockPos> positions = new ArrayList<>(1);
        int placed = 0;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                long decoration = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
                for (Kind kind : kinds) {
                    random.setFeatureSeed(decoration, kind.index(), kind.step());
                    BlockPos position = place(kind, random, new BlockPos(chunkX * 16, minY, chunkZ * 16), generator, biomes, positions);
                    if (position != null) {
                        if (grid == null) {
                            grid = new Grid();
                        }
                        grid.iceberg(random, kind.blue());
                        grid.addTo(field, position.getX(), position.getZ(), kind.blue());
                        placed++;
                    }
                }
            }
        }
        if (FarPlaneDebug.ENABLED && placed > 0) {
            LOGGER.info("[FarPlane icebergs] {} placed around {} {} (size {}), {} above the water", placed, minX, minZ, size, field.any ? "some" : "none");
        }
        return field.any ? field : null;
    }

    /** Where the placement of an iceberg puts it in a chunk, as vanilla's would, or null where it puts none. */
    private static @Nullable BlockPos place(
        Kind kind, RandomSource random, BlockPos origin, ChunkGenerator generator, TileGenerator.BiomeLookup biomes, List<BlockPos> positions
    ) {
        BlockPos position = origin;
        for (PlacementModifier modifier : kind.feature().placement()) {
            if (modifier instanceof BiomeFilter) {
                Holder<Biome> biome = biomes.get(position.getX(), position.getY(), position.getZ());
                if (biome == null || !generator.getBiomeGenerationSettings(biome).hasFeature(kind.feature())) {
                    return null;
                }
                continue;
            }
            // Rarity and the spread over the chunk only use the random, see kinds.
            positions.clear();
            modifier.modify(null, random, position, positions::add);
            if (positions.isEmpty()) {
                return null;
            }
            position = positions.get(0);
        }
        return position;
    }

    /**
     * The iceberg features of a generator in the order of its decoration steps. Only those whose placement this can follow are taken:
     * how rare they are, the spread over the chunk and the biome, as vanilla's icebergs have.
     */
    private static List<Kind> kinds(ChunkGenerator generator) {
        synchronized (KINDS) {
            List<Kind> known = KINDS.get(generator);
            if (known != null) {
                return known;
            }
        }
        List<Kind> kinds = new ArrayList<>();
        try {
            List<FeatureSorter.StepFeatureData> steps = ((ChunkGeneratorAccess) (Object) generator).farplane$featuresPerStep().get();
            for (int step = 0; step < steps.size(); step++) {
                List<PlacedFeature> features = steps.get(step).features();
                for (int index = 0; index < features.size(); index++) {
                    PlacedFeature feature = features.get(index);
                    if (!(feature.feature().value() instanceof IcebergFeature iceberg) || !followable(feature)) {
                        continue;
                    }
                    kinds.add(new Kind(feature, iceberg.state().is(Blocks.BLUE_ICE), step, index));
                }
            }
        } catch (RuntimeException e) {
            kinds.clear();
        }
        List<Kind> result = List.copyOf(kinds);
        if (FarPlaneDebug.ENABLED) {
            LOGGER.info("[FarPlane icebergs] the generator has {} iceberg features to follow: {}", result.size(), result);
        }
        synchronized (KINDS) {
            KINDS.put(generator, result);
        }
        return result;
    }

    private static boolean followable(PlacedFeature feature) {
        for (PlacementModifier modifier : feature.placement()) {
            if (!(modifier instanceof RarityFilter || modifier instanceof InSquarePlacement || modifier instanceof BiomeFilter)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link IcebergFeature#place} on a grid of blocks around the iceberg instead of the world, the same steps and the same calls of
     * the random in the same order. The water level is at y = 0 of the grid, the sea level of the world.
     */
    private static final class Grid {
        private static final byte AIR = 0;
        private static final byte WATER = 1;
        private static final byte ICE = 2;
        private static final byte SNOW_BLOCK = 3;
        private static final int HALF = 24;
        private static final int SIDE = 2 * HALF;
        private static final int BOTTOM = -48;
        private static final int HEIGHT = 96;

        private final byte[] blocks = new byte[SIDE * SIDE * HEIGHT];
        private int highest;

        private int index(int x, int y, int z) {
            int gx = x + HALF;
            int gz = z + HALF;
            int gy = y - BOTTOM;
            if (gx < 0 || gz < 0 || gy < 0 || gx >= SIDE || gz >= SIDE || gy >= HEIGHT) {
                return -1;
            }
            return gx + gz * SIDE + gy * SIDE * SIDE;
        }

        private byte get(int x, int y, int z) {
            int index = this.index(x, y, z);
            return index < 0 ? (y < 0 ? WATER : AIR) : this.blocks[index];
        }

        private void set(int x, int y, int z, byte block) {
            int index = this.index(x, y, z);
            if (index >= 0) {
                this.blocks[index] = block;
            }
        }

        private static boolean isIceberg(byte block) {
            return block == ICE || block == SNOW_BLOCK;
        }

        void iceberg(RandomSource random, boolean blue) {
            for (int y = BOTTOM; y < BOTTOM + HEIGHT; y++) {
                java.util.Arrays.fill(this.blocks, (y - BOTTOM) * SIDE * SIDE, (y - BOTTOM + 1) * SIDE * SIDE, y < 0 ? WATER : AIR);
            }
            boolean snowOnTop = random.nextDouble() > 0.7;
            double shapeAngle = random.nextDouble() * 2.0 * Math.PI;
            int shapeEllipseA = 11 - random.nextInt(5);
            int shapeEllipseC = 3 + random.nextInt(3);
            boolean isEllipse = random.nextDouble() > 0.7;
            int overWaterHeight = isEllipse ? random.nextInt(6) + 6 : random.nextInt(15) + 3;
            if (!isEllipse && random.nextDouble() > 0.9) {
                overWaterHeight += random.nextInt(19) + 7;
            }

            int underWaterHeight = Math.min(overWaterHeight + random.nextInt(11), 18);
            int width = Math.min(overWaterHeight + random.nextInt(7) - random.nextInt(5), 11);
            int a = isEllipse ? shapeEllipseA : 11;

            for (int xo = -a; xo < a; xo++) {
                for (int zo = -a; zo < a; zo++) {
                    for (int yOff = 0; yOff < overWaterHeight; yOff++) {
                        int radius = isEllipse
                            ? heightDependentRadiusEllipse(yOff, overWaterHeight, width)
                            : heightDependentRadiusRound(random, yOff, overWaterHeight, width);
                        if (isEllipse || xo < radius) {
                            this.generateIcebergBlock(random, overWaterHeight, xo, yOff, zo, radius, a, isEllipse, shapeEllipseC, shapeAngle, snowOnTop);
                        }
                    }
                }
            }

            this.smooth(width, overWaterHeight, isEllipse, shapeEllipseA);

            for (int xo = -a; xo < a; xo++) {
                for (int zo = -a; zo < a; zo++) {
                    for (int yOff = -1; yOff > -underWaterHeight; yOff--) {
                        int newA = isEllipse ? Mth.ceil(a * (1.0F - (float) Math.pow(yOff, 2.0) / (underWaterHeight * 8.0F))) : a;
                        int radius = heightDependentRadiusSteep(random, -yOff, underWaterHeight, width);
                        if (xo < radius) {
                            this.generateIcebergBlock(random, underWaterHeight, xo, yOff, zo, radius, newA, isEllipse, shapeEllipseC, shapeAngle, snowOnTop);
                        }
                    }
                }
            }

            boolean doCutOut = isEllipse ? random.nextDouble() > 0.1 : random.nextDouble() > 0.7;
            if (doCutOut) {
                this.generateCutOut(random, width, overWaterHeight, isEllipse, shapeEllipseA, shapeAngle, shapeEllipseC);
            }
            this.highest = overWaterHeight + 1;
        }

        /** Adds the columns above the water to the field, the iceberg standing at {@code originX}, {@code originZ}. */
        void addTo(Field field, int originX, int originZ, boolean blue) {
            for (int z = -HALF; z < HALF; z++) {
                for (int x = -HALF; x < HALF; x++) {
                    for (int y = Math.min(this.highest, BOTTOM + HEIGHT - 1); y >= 0; y--) {
                        byte block = this.get(x, y, z);
                        if (isIceberg(block)) {
                            field.add(originX + x, originZ + z, y + 1, block == SNOW_BLOCK ? SNOW : blue ? BLUE : PACKED);
                            break;
                        }
                    }
                }
            }
        }

        private void generateCutOut(
            RandomSource random, int width, int height, boolean isEllipse, int shapeEllipseA, double shapeAngle, int shapeEllipseC
        ) {
            int randomSignX = random.nextBoolean() ? -1 : 1;
            int randomSignZ = random.nextBoolean() ? -1 : 1;
            int xOff = random.nextInt(Math.max(width / 2 - 2, 1));
            if (random.nextBoolean()) {
                xOff = width / 2 + 1 - random.nextInt(Math.max(width - width / 2 - 1, 1));
            }

            int zOff = random.nextInt(Math.max(width / 2 - 2, 1));
            if (random.nextBoolean()) {
                zOff = width / 2 + 1 - random.nextInt(Math.max(width - width / 2 - 1, 1));
            }

            if (isEllipse) {
                xOff = zOff = random.nextInt(Math.max(shapeEllipseA - 5, 1));
            }

            int localX = randomSignX * xOff;
            int localZ = randomSignZ * zOff;
            double angle = isEllipse ? shapeAngle + (Math.PI / 2) : random.nextDouble() * 2.0 * Math.PI;

            for (int yOff = 0; yOff < height - 3; yOff++) {
                int radius = heightDependentRadiusRound(random, yOff, height, width);
                this.carve(radius, yOff, false, angle, localX, localZ, shapeEllipseA, shapeEllipseC);
            }

            for (int yOff = -1; yOff > -height + random.nextInt(5); yOff--) {
                int radius = heightDependentRadiusSteep(random, -yOff, height, width);
                this.carve(radius, yOff, true, angle, localX, localZ, shapeEllipseA, shapeEllipseC);
            }
        }

        private void carve(int radius, int yOff, boolean underWater, double angle, int localX, int localZ, int shapeEllipseA, int shapeEllipseC) {
            int a = radius + 1 + shapeEllipseA / 3;
            int c = Math.min(radius - 3, 3) + shapeEllipseC / 2 - 1;

            for (int xo = -a; xo < a; xo++) {
                for (int zo = -a; zo < a; zo++) {
                    double signedDist = signedDistanceEllipse(xo, zo, localX, localZ, a, c, angle);
                    if (signedDist < 0.0) {
                        byte state = this.get(xo, yOff, zo);
                        if (isIceberg(state)) {
                            // Vanilla also takes away a snow layer above, which icebergs never have themselves.
                            this.set(xo, yOff, zo, underWater ? WATER : AIR);
                        }
                    }
                }
            }
        }

        private void generateIcebergBlock(
            RandomSource random, int height, int xo, int yOff, int zo, int radius, int a, boolean isEllipse, int shapeEllipseC,
            double shapeAngle, boolean snowOnTop
        ) {
            double signedDist = isEllipse
                ? signedDistanceEllipse(xo, zo, 0, 0, a, getEllipseC(yOff, height, shapeEllipseC), shapeAngle)
                : signedDistanceCircle(xo, zo, radius, random);
            if (signedDist < 0.0) {
                double compareVal = isEllipse ? -0.5 : -6 - random.nextInt(3);
                if (signedDist > compareVal && random.nextDouble() > 0.9) {
                    return;
                }

                this.setIcebergBlock(xo, yOff, zo, random, height - yOff, height, isEllipse, snowOnTop);
            }
        }

        private void setIcebergBlock(int x, int y, int z, RandomSource random, int hDiff, int height, boolean isEllipse, boolean snowOnTop) {
            byte state = this.get(x, y, z);
            // Vanilla also places over ice; there is none yet when icebergs are made.
            if (state == AIR || state == SNOW_BLOCK || state == WATER) {
                boolean randomness = !isEllipse || random.nextDouble() > 0.05;
                int divisor = isEllipse ? 3 : 2;
                if (snowOnTop && state != WATER && hDiff <= random.nextInt(Math.max(1, height / divisor)) + height * 0.6 && randomness) {
                    this.set(x, y, z, SNOW_BLOCK);
                } else {
                    this.set(x, y, z, ICE);
                }
            }
        }

        private void smooth(int width, int height, boolean isEllipse, int shapeEllipseA) {
            int a = isEllipse ? shapeEllipseA : width / 2;

            for (int x = -a; x <= a; x++) {
                for (int z = -a; z <= a; z++) {
                    for (int yOff = 0; yOff <= height; yOff++) {
                        byte state = this.get(x, yOff, z);
                        if (isIceberg(state)) {
                            if (this.get(x, yOff - 1, z) == AIR) {
                                this.set(x, yOff, z, AIR);
                                this.set(x, yOff + 1, z, AIR);
                            } else if (state == ICE || state == SNOW_BLOCK) {
                                int counter = 0;
                                counter += isIceberg(this.get(x - 1, yOff, z)) ? 0 : 1;
                                counter += isIceberg(this.get(x + 1, yOff, z)) ? 0 : 1;
                                counter += isIceberg(this.get(x, yOff, z - 1)) ? 0 : 1;
                                counter += isIceberg(this.get(x, yOff, z + 1)) ? 0 : 1;
                                if (counter >= 3) {
                                    this.set(x, yOff, z, AIR);
                                }
                            }
                        }
                    }
                }
            }
        }

        private static int getEllipseC(int yOff, int height, int shapeEllipseC) {
            int c = shapeEllipseC;
            if (yOff > 0 && height - yOff <= 3) {
                c -= 4 - (height - yOff);
            }
            return c;
        }

        private static double signedDistanceCircle(int xo, int zo, int radius, RandomSource random) {
            float off = 10.0F * Mth.clamp(random.nextFloat(), 0.2F, 0.8F) / radius;
            return off + Math.pow(xo, 2.0) + Math.pow(zo, 2.0) - Math.pow(radius, 2.0);
        }

        private static double signedDistanceEllipse(int xo, int zo, int originX, int originZ, int a, int c, double angle) {
            return Math.pow(((xo - originX) * Math.cos(angle) - (zo - originZ) * Math.sin(angle)) / a, 2.0)
                + Math.pow(((xo - originX) * Math.sin(angle) + (zo - originZ) * Math.cos(angle)) / c, 2.0)
                - 1.0;
        }

        private static int heightDependentRadiusRound(RandomSource random, int yOff, int height, int width) {
            float k = 3.5F - random.nextFloat();
            float scale = (1.0F - (float) Math.pow(yOff, 2.0) / (height * k)) * width;
            if (height > 15 + random.nextInt(5)) {
                int tempYOff = yOff < 3 + random.nextInt(6) ? yOff / 2 : yOff;
                scale = (1.0F - tempYOff / (height * k * 0.4F)) * width;
            }
            return Mth.ceil(scale / 2.0F);
        }

        private static int heightDependentRadiusEllipse(int yOff, int height, int width) {
            float scale = (1.0F - (float) Math.pow(yOff, 2.0) / (height * 1.0F)) * width;
            return Mth.ceil(scale / 2.0F);
        }

        private static int heightDependentRadiusSteep(RandomSource random, int yOff, int height, int width) {
            float k = 1.0F + random.nextFloat() / 2.0F;
            float scale = (1.0F - yOff / (height * k)) * width;
            return Mth.ceil(scale / 2.0F);
        }
    }
}
