package com.Evga314.farplanetwocontinuation.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Picks a flat color for a LOD sample. The world generator noise only knows stone, water and lava, so the surface material is
 * guessed from the biome, the height and the slope.
 */
final class TileColors {
    private static final int SAND = 0xFFD9CF9C;
    private static final int RED_SAND = 0xFFB4622E;
    private static final int SNOW = 0xFFF2F6FA;
    private static final int STONE = 0xFF7F7F7F;
    private static final int LAVA = 0xFFD8661C;
    private static final int MUSHROOM = 0xFF8C7484;
    private static final int DEFAULT_GRASS = 0xFF79C05A;
    private static final int DEFAULT_WATER = 0xFF3F76E4;

    private static final Identifier DESERT = Identifier.withDefaultNamespace("desert");
    private static final Identifier MUSHROOM_FIELDS = Identifier.withDefaultNamespace("mushroom_fields");
    private static final Identifier STONY_SHORE = Identifier.withDefaultNamespace("stony_shore");

    private TileColors() {
    }

    static int color(@Nullable Holder<Biome> holder, int x, int y, int z, boolean water, boolean lava, float slope, int seaLevel, BlockPos.MutableBlockPos scratch) {
        if (lava) {
            return LAVA;
        }
        if (holder == null) {
            return water ? DEFAULT_WATER : DEFAULT_GRASS;
        }
        Biome biome = holder.value();
        if (water) {
            return ARGB.opaque(ARGB.scaleRGB(biome.getWaterColor(), 0.85F));
        }
        scratch.set(x, y, z);
        boolean snowy = biome.coldEnoughToSnow(scratch, seaLevel);
        if (slope > 1.4F) {
            return snowy && slope < 2.2F ? SNOW : STONE;
        }
        if (snowy) {
            return SNOW;
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            return RED_SAND;
        }
        if (holder.is(BiomeTags.IS_BEACH) || holder.is(DESERT)) {
            return SAND;
        }
        if (holder.is(MUSHROOM_FIELDS)) {
            return MUSHROOM;
        }
        if (holder.is(STONY_SHORE)) {
            return STONE;
        }
        if (holder.is(BiomeTags.IS_FOREST) || holder.is(BiomeTags.IS_JUNGLE) || holder.is(BiomeTags.IS_TAIGA)) {
            return ARGB.opaque(ARGB.scaleRGB(biome.getFoliageColor(), 0.8F));
        }
        if (holder.is(BiomeTags.IS_MOUNTAIN) && y > seaLevel + 90) {
            return STONE;
        }
        return ARGB.opaque(biome.getGrassColor(x, z));
    }
}
