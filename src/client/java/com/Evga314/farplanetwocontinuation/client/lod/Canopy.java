package com.Evga314.farplanetwocontinuation.client.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Tree tops for the rough terrain. The world generator noise has no trees, so wooded biomes would be bare ground from the edge of the
 * detailed terrain on. Their surface is raised to where the leaves would be, in patches as dense as the trees of the biome, and
 * painted in the color of the leaves. Everything depends only on the world position, so neighbouring tiles and levels agree.
 */
final class Canopy {
    /**
     * @param height  how high the leaves are above the ground, in blocks
     * @param density share of the ground under trees, 0 to 1
     * @param leaves  the leaves of the trees, painted like vanilla's
     * @param overWater the trees stand in water (mangroves), so the leaves cover water too
     */
    record Kind(float height, float density, Identifier leaves, boolean overWater) {
    }

    private static final String OAK = "oak_leaves";
    private static final String SPRUCE = "spruce_leaves";
    private static final String BIRCH = "birch_leaves";
    private static final String JUNGLE = "jungle_leaves";
    private static final String ACACIA = "acacia_leaves";
    private static final Kind FALLBACK = new Kind(7.0F, 0.7F, Identifier.withDefaultNamespace(OAK), false);
    private static final Identifier SNOW = Identifier.withDefaultNamespace("snow_block");
    private static final Map<Identifier, Kind> KINDS = Map.ofEntries(
        kind("jungle", 15.0F, 1.0F, JUNGLE),
        kind("bamboo_jungle", 11.0F, 0.9F, JUNGLE),
        kind("sparse_jungle", 10.0F, 0.45F, JUNGLE),
        kind("dark_forest", 9.0F, 1.0F, "dark_oak_leaves"),
        kind("pale_garden", 9.0F, 1.0F, "pale_oak_leaves"),
        kind("forest", 6.5F, 0.85F, OAK),
        kind("flower_forest", 6.5F, 0.65F, OAK),
        kind("birch_forest", 7.0F, 0.85F, BIRCH),
        kind("old_growth_birch_forest", 10.0F, 0.85F, BIRCH),
        kind("taiga", 9.0F, 0.85F, SPRUCE),
        kind("snowy_taiga", 8.0F, 0.8F, SPRUCE),
        kind("old_growth_pine_taiga", 14.0F, 0.8F, SPRUCE),
        kind("old_growth_spruce_taiga", 16.0F, 0.85F, SPRUCE),
        kind("grove", 8.0F, 0.6F, SPRUCE),
        kind("windswept_forest", 7.0F, 0.5F, SPRUCE),
        kind("cherry_grove", 6.5F, 0.6F, "cherry_leaves"),
        kind("savanna", 6.0F, 0.25F, ACACIA),
        kind("savanna_plateau", 6.0F, 0.3F, ACACIA),
        kind("windswept_savanna", 6.0F, 0.15F, ACACIA),
        kind("wooded_badlands", 5.0F, 0.3F, OAK),
        kind("swamp", 6.0F, 0.35F, OAK),
        Map.entry(Identifier.withDefaultNamespace("mangrove_swamp"), new Kind(7.0F, 0.85F, Identifier.withDefaultNamespace("mangrove_leaves"), true))
    );
    /** Patches of trees are about this many blocks across. */
    private static final int PATCH = 6;
    /** Over this share of the noise range a patch goes from bare ground to full height, so the edges of patches are round. */
    private static final float EDGE = 0.15F;
    private static final Map<Holder<Biome>, Kind> CACHE = new ConcurrentHashMap<>();
    private static final Kind NONE = new Kind(0.0F, 0.0F, Identifier.withDefaultNamespace(OAK), false);

    private Canopy() {
    }

    private static Map.Entry<Identifier, Kind> kind(String biome, float height, float density, String leaves) {
        return Map.entry(Identifier.withDefaultNamespace(biome), new Kind(height, density, Identifier.withDefaultNamespace(leaves), false));
    }

    /** The trees of a biome, or null for a biome without trees. */
    static @Nullable Kind of(@Nullable Holder<Biome> holder) {
        if (holder == null) {
            return null;
        }
        Kind kind = CACHE.computeIfAbsent(holder, biome -> {
            Kind known = biome.unwrapKey().map(key -> KINDS.get(key.identifier())).orElse(null);
            if (known != null) {
                return known;
            }
            // Wooded biomes of other mods.
            return biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA) ? FALLBACK : NONE;
        });
        return kind == NONE ? null : kind;
    }

    /**
     * Height of the leaves above the ground at a sample. Samples of coarse tiles stand for many trees, so there the patches are
     * smoothed into an even layer as high as the trees times their density.
     */
    static float height(Kind kind, int x, int z, int spacing) {
        float even = kind.height() * kind.density();
        if (spacing >= 16) {
            return even;
        }
        float noise = noise(x, z);
        float patch = Mth.clamp((kind.density() - noise) / EDGE + 0.5F, 0.0F, 1.0F);
        // Trees differ a little in height.
        float tree = kind.height() * (0.85F + 0.3F * hash(x * 31 + 7, z * 17 + 3));
        float detailed = patch * tree;
        float weight = spacing <= 4 ? 1.0F : (16.0F - spacing) / 12.0F;
        return Mth.lerp(weight, even, detailed);
    }

    /**
     * Color of the leaves as vanilla paints them, a little darker where the gaps between leaves show the inside of the crown and
     * varying from tree to tree; white on top where it snows.
     */
    static int color(Kind kind, Holder<Biome> biome, int x, int y, int z, boolean snowy) {
        int leaves = TileColors.blockColor(kind.leaves(), biome, x, y, z, 0.0F);
        int color = ARGB.scaleRGB(leaves, 0.8F + 0.15F * hash(x, z));
        return snowy ? ARGB.srgbLerp(0.55F, color, TileColors.blockColor(SNOW, biome, x, y, z, 0.0F)) : color;
    }

    /** Smooth value noise from 0 to 1 over patches of {@link #PATCH} blocks. */
    private static float noise(int x, int z) {
        int cellX = Math.floorDiv(x, PATCH);
        int cellZ = Math.floorDiv(z, PATCH);
        float fx = smooth((x - cellX * PATCH) / (float) PATCH);
        float fz = smooth((z - cellZ * PATCH) / (float) PATCH);
        float top = Mth.lerp(fx, hash(cellX, cellZ), hash(cellX + 1, cellZ));
        float bottom = Mth.lerp(fx, hash(cellX, cellZ + 1), hash(cellX + 1, cellZ + 1));
        return Mth.lerp(fz, top, bottom);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private static float hash(int x, int z) {
        int n = x * 73856093 ^ z * 19349663;
        n = (n ^ n >>> 13) * 1274126177;
        n ^= n >>> 16;
        return (n & 0xFFFF) / 65535.0F;
    }
}
