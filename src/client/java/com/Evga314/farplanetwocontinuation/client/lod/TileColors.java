package com.Evga314.farplanetwocontinuation.client.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import com.Evga314.farplanetwocontinuation.tile.TileData;
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
    private static final Identifier PACKED_ICE = block("packed_ice");
    private static final Identifier BLUE_ICE = block("blue_ice");
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
    private static final Identifier FROZEN_PEAKS = Identifier.withDefaultNamespace("frozen_peaks");
    private static final Identifier FROZEN_OCEAN = Identifier.withDefaultNamespace("frozen_ocean");
    private static final Identifier DEEP_FROZEN_OCEAN = Identifier.withDefaultNamespace("deep_frozen_ocean");
    private static final Identifier JAGGED_PEAKS = Identifier.withDefaultNamespace("jagged_peaks");
    private static final Identifier SNOWY_SLOPES = Identifier.withDefaultNamespace("snowy_slopes");
    /** Steeper than this (blocks up per block across), ground is steep the way vanilla's surface rules mean it: about 54 degrees. */
    private static final float STEEP = 1.4F;
    /** From this slope on cliffs of grassy ground show more and more stone between the grass, up to this share at the next one. */
    private static final float ROCKY = 2.2F;
    private static final float ROCKIEST = 3.5F;
    private static final float MOST_ROCK = 0.6F;
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
        boolean snowy = coldEnoughToSnow(biome, x, y, z, seaLevel, scratch);
        if (water) {
            // Frozen rivers and oceans.
            return snowy ? blockColor(ICE, holder, x, y, z, 0.0F) : waterColor(holder);
        }
        return landColor(holder, x, y, z, slope, sides, seaLevel, snowy);
    }

    /** Whether a biome is a frozen ocean, where land is icebergs: on coarse tiles they are only told by the cell, see TileData.FLAG_ICEBERG. */
    static boolean isFrozenOcean(Holder<Biome> holder) {
        return holder.is(FROZEN_OCEAN) || holder.is(DEEP_FROZEN_OCEAN);
    }

    /** The color of an iceberg on a frozen ocean, see TileData.FLAG_ICEBERG: packed or blue ice, or snow on top. */
    static int icebergColor(@Nullable Holder<Biome> holder, int x, int y, int z, float sides, int flags) {
        Identifier ice = (flags & TileData.FLAG_BLUE_ICE) != 0 ? BLUE_ICE : PACKED_ICE;
        if ((flags & TileData.FLAG_ICEBERG_SNOW) != 0) {
            // Snow lies on the top; the sides of steep icebergs stay ice.
            return ARGB.opaque(ARGB.srgbLerp(sides, blockColor(SNOW, holder, x, y, z, 0.0F), blockColor(ice, holder, x, y, z, 0.0F)));
        }
        return blockColor(ice, holder, x, y, z, sides);
    }

    /** The color of land before shading, with or without snow on it whatever the biome says, see {@link #color}. */
    static int landColor(
        @Nullable Holder<Biome> holder, int x, int y, int z, float slope, float sides, int seaLevel, boolean snowy
    ) {
        if (holder == null) {
            return blockColor(GRASS, null, x, y, z, sides);
        }
        if (holder.is(BiomeTags.IS_END)) {
            return blockColor(END_STONE, holder, x, y, z, sides);
        }
        Identifier surface = surface(holder, y, slope, snowy, seaLevel);
        int color = blockColor(surface, holder, x, y, z, sides);
        // Grass stays on the steps of steep grassy slopes, showing the dirt of their sides; only on cliffs does the stone under the
        // thin layer of dirt come out between them.
        if ((surface == GRASS || surface == PODZOL) && slope > ROCKY) {
            float rock = Mth.clamp((slope - ROCKY) / (ROCKIEST - ROCKY), 0.0F, 1.0F) * MOST_ROCK;
            color = ARGB.opaque(ARGB.srgbLerp(rock, color, blockColor(STONE, holder, x, y, z, sides)));
        }
        return color;
    }

    /** Whether water here is frozen over: vanilla freezes the top of water where it is cold enough to snow. */
    static boolean isIcy(@Nullable Holder<Biome> holder, int x, int y, int z, int seaLevel, BlockPos.MutableBlockPos scratch) {
        if (holder == null || holder.is(BiomeTags.IS_END)) {
            return false;
        }
        return coldEnoughToSnow(holder.value(), x, y, z, seaLevel, scratch);
    }

    /**
     * Vanilla's {@link Biome#coldEnoughToSnow}, asked thousands of times per tile. Up to a little over the sea level the temperature of
     * a biome without a temperature modifier is the same everywhere, its base temperature, so there no position has to be looked at.
     * Higher up, and for frozen oceans, vanilla decides.
     */
    static boolean coldEnoughToSnow(Biome biome, int x, int y, int z, int seaLevel, BlockPos.MutableBlockPos scratch) {
        if (y <= seaLevel + EVEN_TEMPERATURE_ABOVE_SEA && EVEN_TEMPERATURE.computeIfAbsent(biome, TileColors::evenTemperature)) {
            return biome.getBaseTemperature() < SNOW_TEMPERATURE;
        }
        scratch.set(x, y, z);
        return biome.coldEnoughToSnow(scratch, seaLevel);
    }

    /** Whether a biome has no temperature modifier, read once: the climate settings of a biome are not public. */
    private static boolean evenTemperature(Biome biome) {
        try {
            java.lang.reflect.Field field = Biome.class.getDeclaredField("climateSettings");
            field.setAccessible(true);
            Object climate = field.get(biome);
            java.lang.reflect.Method modifier = climate.getClass().getDeclaredMethod("temperatureModifier");
            modifier.setAccessible(true);
            return modifier.invoke(climate) == Biome.TemperatureModifier.NONE;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Unknown: vanilla decides every time.
            return false;
        }
    }

    private static final Map<Biome, Boolean> EVEN_TEMPERATURE = new ConcurrentHashMap<>();

    /** Vanilla's limits: up to this height over the sea level temperature does not change with height, and below this it snows. */
    private static final int EVEN_TEMPERATURE_ABOVE_SEA = 17;
    private static final float SNOW_TEMPERATURE = 0.15F;

    /** The color of ice on frozen water. */
    static int iceColor(@Nullable Holder<Biome> holder, int x, int y, int z) {
        return blockColor(ICE, holder, x, y, z, 0.0F);
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
        boolean steep = slope > STEEP;
        // Which family of surface rules a biome follows is classified once per biome (see classify); per sample only the answers
        // that depend on the slope, the snow and the height are worked out, in the same order as the rules were checked before.
        SurfaceKind kind = SURFACE_KINDS.computeIfAbsent(holder, TileColors::classify);
        // The peaks keep vanilla's own rules for steep ground: packed ice on frozen peaks, stone on jagged peaks and snowy slopes.
        if (kind == SurfaceKind.FROZEN_PEAKS) {
            return steep ? PACKED_ICE : SNOW;
        }
        if (kind == SurfaceKind.JAGGED_PEAKS) {
            return steep ? STONE : SNOW;
        }
        if (kind == SurfaceKind.STONY) {
            return STONE;
        }
        if (snowy) {
            return slope > ROCKY ? STONE : SNOW;
        }
        return switch (kind) {
            case BADLANDS -> slope < 0.6F ? RED_SAND : TERRACOTTA_BANDS[Math.floorMod(Math.floorDiv(y, BAND_HEIGHT), TERRACOTTA_BANDS.length)];
            case BEACH -> SAND;
            case MUSHROOM -> MYCELIUM;
            case GRAVELLY -> steep ? STONE : GRAVEL;
            case TAIGA -> PODZOL;
            default -> GRASS;
        };
    }

    /** The family of surface rules of a biome, from the same checks in the same order as {@link #surface} used to run per sample. */
    private static SurfaceKind classify(Holder<Biome> holder) {
        if (holder.is(FROZEN_PEAKS)) {
            return SurfaceKind.FROZEN_PEAKS;
        }
        if (holder.is(JAGGED_PEAKS) || holder.is(SNOWY_SLOPES)) {
            return SurfaceKind.JAGGED_PEAKS;
        }
        if (holder.is(STONY_SHORE) || holder.is(STONY_PEAKS)) {
            return SurfaceKind.STONY;
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            return SurfaceKind.BADLANDS;
        }
        if (holder.is(BiomeTags.IS_BEACH) || holder.is(DESERT)) {
            return SurfaceKind.BEACH;
        }
        if (holder.is(MUSHROOM_FIELDS)) {
            return SurfaceKind.MUSHROOM;
        }
        if (holder.is(GRAVELLY_HILLS)) {
            return SurfaceKind.GRAVELLY;
        }
        if (holder.is(PINE_TAIGA) || holder.is(SPRUCE_TAIGA)) {
            return SurfaceKind.TAIGA;
        }
        return SurfaceKind.PLAIN;
    }

    /** The surface rule families of a biome, see {@link #classify}. */
    private enum SurfaceKind {
        FROZEN_PEAKS, JAGGED_PEAKS, STONY, BADLANDS, BEACH, MUSHROOM, GRAVELLY, TAIGA, PLAIN
    }

    /** Biomes already classified, one entry each; a reloaded biome is a new holder and is classified again. */
    private static final Map<Holder<Biome>, SurfaceKind> SURFACE_KINDS = new ConcurrentHashMap<>();
}
