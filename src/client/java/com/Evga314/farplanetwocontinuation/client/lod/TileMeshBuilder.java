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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link TileData} into a POSITION_COLOR vertex buffer: a 33x33 height grid plus a skirt around the border that hides cracks
 * between tiles of different detail. Positions are relative to the tile's minimum corner at y = 0.
 */
public final class TileMeshBuilder {
    public static final int VERTEX_SIZE = 16;
    private static final int S = FarPlaneConstants.TILE_SAMPLES;
    private static final int C = FarPlaneConstants.TILE_CELLS;
    public static final int GRID_VERTICES = S * S;
    public static final int VERTEX_COUNT = GRID_VERTICES + 4 * S;
    public static final int INDEX_COUNT = C * C * 6 + 4 * C * 6;

    /** Heights come from the server, keep them in a sane range so a bad tile can only look wrong locally. */
    private static final int MIN_HEIGHT = -4096;
    private static final int MAX_HEIGHT = 8192;

    static final float WATER_SHADE = 0.92F;
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

    private TileMeshBuilder() {
    }

    public record Built(
        long key, int epoch, @Nullable ByteBuffer vertices, float minY, float maxY, float terrainMinY, short @Nullable [] heights, int indexCount,
        boolean detail, long coverage, boolean complete, int @Nullable [] chunkQuads, int @Nullable [] chunkSurfaces, boolean textured,
        boolean hasWater, int @Nullable [] chunkPlants
    ) {
        /** A tile whose data could not be used; it is dropped quietly. */
        static Built failed(long key, int epoch) {
            return new Built(key, epoch, null, 0.0F, 0.0F, 0.0F, null, 0, false, 0L, false, null, null, false, false, null);
        }
    }

    /**
     * @param floating islands over the void (the End): the skirts that close the gaps between tiles of different detail would hang
     *                 under every island edge like curtains there, so they are left out
     */
    public static Built build(long key, int epoch, TileData data, List<@Nullable Holder<Biome>> biomes, int seaLevel, boolean floating) {
        TilePos pos = TilePos.unpack(key);
        int spacing = 1 << pos.level();
        long baseX = pos.minX();
        long baseZ = pos.minZ();
        ByteBuffer buffer = ByteBuffer.allocateDirect(VERTEX_COUNT * VERTEX_SIZE).order(ByteOrder.nativeOrder());
        int[] colors = new int[GRID_VERTICES];
        int[] alphas = new int[GRID_VERTICES];
        // Surface heights with the tree tops on them.
        float[] tops = new float[GRID_VERTICES];
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

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
                if (data.subBiomes != null) {
                    colors[index] = averagedColor(data, index, biomes, worldX, (int) height, worldZ, slope, sides, landShade, seaLevel, scratch);
                } else {
                    int color = TileColors.color(biome, worldX, (int) height, worldZ, water, lava, water ? 0.0F : slope, sides, seaLevel, scratch);
                    colors[index] = ARGB.scaleRGB(color, water || lava ? WATER_SHADE : landShade);
                }
                Canopy.Kind trees = lava || alphas[index] == 0 || slope > 1.4F || biome == null ? null : Canopy.of(biome);
                if (trees != null && (!water || trees.overWater())) {
                    float canopy = Canopy.height(trees, worldX, worldZ, spacing);
                    if (canopy > 0.5F) {
                        scratch.set(worldX, (int) (height + canopy), worldZ);
                        boolean snowy = biome.value().coldEnoughToSnow(scratch, seaLevel);
                        int leaves = ARGB.scaleRGB(Canopy.color(trees, biome, worldX, (int) (height + canopy), worldZ, snowy), landShade);
                        // Coarse samples stand for trees and the ground between them.
                        colors[index] = spacing >= 16 ? ARGB.srgbLerp(trees.density(), colors[index], leaves) : leaves;
                        canopy = Math.min(canopy, MAX_CANOPY);
                        height += canopy;
                        alphas[index] = 255 - Math.round(canopy * CANOPY_UNITS);
                    }
                }
                tops[index] = height;
                minY = Math.min(minY, height);
                maxY = Math.max(maxY, height);
                putVertex(buffer, localCoordinate(baseX, x, spacing), height, localCoordinate(baseZ, z, spacing), colors[index], alphas[index]);
            }
        }

        float skirtDepth = floating ? 0.0F : Mth.clamp(spacing * 4.0F, 8.0F, 512.0F);
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < S; i++) {
                int x = edgeX(edge, i);
                int z = edgeZ(edge, i);
                int index = x + z * S;
                putVertex(buffer, localCoordinate(baseX, x, spacing), tops[index] - skirtDepth, localCoordinate(baseZ, z, spacing), colors[index],
                    floating ? 0 : alphas[index]);
            }
        }
        buffer.flip();
        return new Built(key, epoch, buffer, minY - skirtDepth, maxY, minY, data.heights.clone(), INDEX_COUNT, false, 0L, true, null, null, false, false, null);
    }

    /** Box filter over the sub-samples of a coarse cell, so a cell that is 30% islands looks like a slightly greener sea. */
    private static int averagedColor(
        TileData data, int index, List<@Nullable Holder<Biome>> biomes, int x, int height, int z, float slope, float sides, float landShade,
        int seaLevel, BlockPos.MutableBlockPos scratch
    ) {
        int mask = data.subWater[index];
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int i = 0; i < TileData.SUB_SAMPLES; i++) {
            boolean water = (mask & 1 << i) != 0;
            Holder<Biome> biome = biomes.get(data.subBiomes[index * TileData.SUB_SAMPLES + i] & 0xFF);
            int color = TileColors.color(biome, x, height, z, water, false, water ? 0.0F : slope, water ? 0.0F : sides, seaLevel, scratch);
            color = ARGB.scaleRGB(color, water ? WATER_SHADE : landShade);
            red += ARGB.red(color);
            green += ARGB.green(color);
            blue += ARGB.blue(color);
        }
        return ARGB.color(255, red / TileData.SUB_SAMPLES, green / TileData.SUB_SAMPLES, blue / TileData.SUB_SAMPLES);
    }

    private static int edgeX(int edge, int i) {
        return switch (edge) {
            case 0, 1 -> i;
            case 2 -> 0;
            default -> C;
        };
    }

    private static int edgeZ(int edge, int i) {
        return switch (edge) {
            case 0 -> 0;
            case 1 -> C;
            case 2, 3 -> i;
            default -> 0;
        };
    }

    /** Position relative to the tile corner, cut at the world border so edge tiles end exactly where the world ends. */
    static float localCoordinate(long base, int sample, int spacing) {
        long world = Mth.clamp(base + (long) sample * spacing, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
        return (float) (world - base);
    }

    private static float sample(TileData data, int x, int z) {
        return height(data, Mth.clamp(x, 0, C) + Mth.clamp(z, 0, C) * S);
    }

    private static float height(TileData data, int index) {
        return Mth.clamp(data.heights[index], MIN_HEIGHT, MAX_HEIGHT);
    }

    /** Alpha 0 marks the void; the shader drops everything closer to a void sample than to land. */
    private static void putVertex(ByteBuffer buffer, float x, float y, float z, int argb, int alpha) {
        buffer.putFloat(x).putFloat(y).putFloat(z);
        buffer.put((byte) ARGB.red(argb)).put((byte) ARGB.green(argb)).put((byte) ARGB.blue(argb)).put((byte) alpha);
    }

    /** The same index list is shared by every tile because all tiles have the same topology. */
    public static ByteBuffer buildIndices() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(INDEX_COUNT * 2).order(ByteOrder.nativeOrder());
        for (int z = 0; z < C; z++) {
            for (int x = 0; x < C; x++) {
                int v00 = x + z * S;
                quad(buffer, v00, v00 + 1, v00 + S, v00 + S + 1);
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            int skirtBase = GRID_VERTICES + edge * S;
            for (int i = 0; i < C; i++) {
                int top0 = edgeX(edge, i) + edgeZ(edge, i) * S;
                int top1 = edgeX(edge, i + 1) + edgeZ(edge, i + 1) * S;
                quad(buffer, top0, top1, skirtBase + i, skirtBase + i + 1);
            }
        }
        buffer.flip();
        return buffer;
    }

    private static void quad(ByteBuffer buffer, int a, int b, int c, int d) {
        buffer.putShort((short) a).putShort((short) c).putShort((short) b);
        buffer.putShort((short) b).putShort((short) c).putShort((short) d);
    }

    public static Holder<Biome>[] resolveBiomes(Registry<Biome> registry, List<Identifier> palette) {
        @SuppressWarnings("unchecked")
        Holder<Biome>[] result = new Holder[palette.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = registry.get(ResourceKey.create(Registries.BIOME, palette.get(i))).orElse(null);
        }
        return result;
    }
}
