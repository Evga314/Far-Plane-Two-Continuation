package com.Evga314.farplanetwocontinuation.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Picks a color for a LOD sample. The world generator noise only knows stone, water and lava, so the surface block is guessed from the
 * biome, the height and the slope, and painted the way detailed tiles paint it: the average color of its vanilla texture with the
 * biome tint, so resource packs and the blocks next to the distant terrain match.
 */
final class TileColors {
    private static final Identifier GRASS = block("grass_block");
    private static final Identifier SAND = block("sand");
    private static final Identifier RED_SAND = block("red_sand");
    private static final Identifier SNOW = block("snow_block");
    private static final Identifier STONE = block("stone");
    private static final Identifier GRAVEL = block("gravel");
    private static final Identifier MYCELIUM = block("mycelium");
    private static final Identifier PODZOL = block("podzol");
    private static final Identifier ICE = block("ice");
    private static final Identifier WATER = block("water");
    /** Open water is a little darker than its texture: the depth under it shows through vanilla's translucent water. */
    static final float WATER_DEPTH = 0.85F;
    private static final int DEFAULT_WATER = 0xFF3F76E4;
    private static final Identifier LAVA = block("lava");
    private static final Identifier END_STONE = block("end_stone");
    /** Bands of the badlands slopes, from the bottom up, repeated every {@link #BAND_HEIGHT} blocks each. */
    private static final Identifier[] TERRACOTTA_BANDS = {
        block("terracotta"), block("orange_terracotta"), block("terracotta"), block("yellow_terracotta"), block("brown_terracotta"),
        block("terracotta"), block("red_terracotta"), block("white_terracotta"), block("orange_terracotta"), block("light_gray_terracotta")
    };
    private static final int BAND_HEIGHT = 3;

    private static final Identifier DESERT = Identifier.withDefaultNamespace("desert");
    private static final Identifier MUSHROOM_FIELDS = Identifier.withDefaultNamespace("mushroom_fields");
    private static final Identifier STONY_SHORE = Identifier.withDefaultNamespace("stony_shore");
    private static final Identifier STONY_PEAKS = Identifier.withDefaultNamespace("stony_peaks");
    private static final Identifier GRAVELLY_HILLS = Identifier.withDefaultNamespace("windswept_gravelly_hills");
    private static final Identifier PINE_TAIGA = Identifier.withDefaultNamespace("old_growth_pine_taiga");
    private static final Identifier SPRUCE_TAIGA = Identifier.withDefaultNamespace("old_growth_spruce_taiga");

    private TileColors() {
    }

    private static Identifier block(String name) {
        return Identifier.withDefaultNamespace(name);
    }

    /**
     * The color of a sample before shading.
     *
     * @param sides share of the surface seen as the sides of blocks rather than their tops, from 0 on flat ground to nearly 1 on cliffs
     */
    static int color(
        @Nullable Holder<Biome> holder, int x, int y, int z, boolean water, boolean lava, float slope, float sides, int seaLevel,
        BlockPos.MutableBlockPos scratch
    ) {
        if (lava) {
            return blockColor(LAVA, holder, x, y, z, 0.0F);
        }
        if (holder == null) {
            return water ? waterColor(null) : blockColor(GRASS, null, x, y, z, sides);
        }
        if (holder.is(BiomeTags.IS_END)) {
            return blockColor(END_STONE, holder, x, y, z, sides);
        }
        Biome biome = holder.value();
        scratch.set(x, y, z);
        boolean snowy = biome.coldEnoughToSnow(scratch, seaLevel);
        if (water) {
            // Frozen rivers and oceans.
            return snowy ? blockColor(ICE, holder, x, y, z, 0.0F) : waterColor(holder);
        }
        return blockColor(surface(holder, y, slope, snowy, seaLevel), holder, x, y, z, sides);
    }

    /**
     * Open water as vanilla paints it: the average of its texture with the water color of the biome, which vanilla puts on fluids
     * itself rather than through the block colors.
     */
    static int waterColor(@Nullable Holder<Biome> biome) {
        int tint = biome == null ? DEFAULT_WATER : biome.value().getWaterColor();
        return ARGB.opaque(ARGB.scaleRGB(ARGB.multiply(BlockLooks.get(WATER).top().average(), tint), WATER_DEPTH));
    }

    /** The vanilla color of a block's top, with that of its sides mixed in as far as they are seen. */
    static int blockColor(Identifier block, @Nullable Holder<Biome> biome, int x, int y, int z, float sides) {
        BlockLooks.Look look = BlockLooks.get(block);
        int top = look.top().color(look.state(), biome, x, y, z);
        if (sides <= 0.01F) {
            return ARGB.opaque(top);
        }
        return ARGB.opaque(ARGB.srgbLerp(sides, top, look.side().color(look.state(), biome, x, y, z)));
    }

    /** The block vanilla most likely has on top here. */
    private static Identifier surface(Holder<Biome> holder, int y, float slope, boolean snowy, int seaLevel) {
        if (slope > 1.4F) {
            return snowy && slope < 2.2F ? SNOW : STONE;
        }
        if (snowy) {
            return SNOW;
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            return slope < 0.6F ? RED_SAND : TERRACOTTA_BANDS[Math.floorMod(Math.floorDiv(y, BAND_HEIGHT), TERRACOTTA_BANDS.length)];
        }
        if (holder.is(BiomeTags.IS_BEACH) || holder.is(DESERT)) {
            return SAND;
        }
        if (holder.is(MUSHROOM_FIELDS)) {
            return MYCELIUM;
        }
        if (holder.is(STONY_SHORE) || holder.is(STONY_PEAKS)) {
            return STONE;
        }
        if (holder.is(GRAVELLY_HILLS)) {
            return GRAVEL;
        }
        if (holder.is(PINE_TAIGA) || holder.is(SPRUCE_TAIGA)) {
            return PODZOL;
        }
        if (holder.is(BiomeTags.IS_MOUNTAIN) && y > seaLevel + 90) {
            return STONE;
        }
        return GRASS;
    }
}
