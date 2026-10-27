package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;

public final class GeneralMeshFactory {
    /** Flat colored detailed tiles: position and color. */
    public static final int VERTEX_SIZE = 16;
    /** Rough tiles: position, land color, second color and the colors beyond the borders on land and between waters. */
    public static final int ROUGH_VERTEX_SIZE = 28;
    /** Steps of softness a sample can have: 0 sharp, then wider and wider. */
    public static final int MAX_SOFTNESS = 5;
    /** Water and lava colours are multiplied by this, so they sit slightly darker than their plain colour. RU: цвета воды и лавы умножаются на это значение, поэтому они выглядят немного темнее чем их исходный цвет */
    static final float WATER_SHADE = 0.92F;

    /** Position relative to the tile corner, cut at the world border so edge tiles end exactly where the world ends. РУ - пощзиция относительно угла тайла, обрезанная по границе мира (чтобы тайлы у границы мира правильно обрезались */
    static float localCoordinate(long base, int sample, int spacing) {
        long world = Mth.clamp(base + (long) sample * spacing, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
        return (float) (world - base);
    }
/** метод, который проходит по списку биомов и для каждого имени достает объект биома или null*/
    public static Holder<Biome>[] resolveBiomes(Registry<Biome> registry, List<Identifier> palette) {
        @SuppressWarnings("unchecked")
        Holder<Biome>[] result = new Holder[palette.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = registry.get(ResourceKey.create(Registries.BIOME, palette.get(i))).orElse(null);
        }
        return result;
    }

    /**
     * How many times the values of each kind of border are evened out with their neighbours: more rounds the borders off further and
     * lets wider lone strips fade.
     */
    /** smoothing это сглаживание. coasts - берега. Количество проходов сгоаживания/смешивания для разных типов границ*/
    public record Smoothing(
        int coasts, int snow, int forests, int biomes, int water, int coastsBlur, int snowBlur, int forestsBlur, int biomesBlur,
        int waterBlur, int shading
    ) {
        public static final Smoothing DEFAULT = new Smoothing(4, 3, 3, 3, 3, 0, 0, 2, 3, 3, 1);
    }

    public record Built(
            long key, int epoch, @Nullable ByteBuffer vertices, float minY, float maxY, float terrainMinY, short @Nullable [] heights, int indexCount,
            boolean detail, long coverage, boolean complete, int @Nullable [] segments, int @Nullable [] chunkSurfaces, boolean textured,
            boolean hasWater, int @Nullable [] chunkPlants, boolean hasCutout,
            com.Evga314.farplanetwocontinuation.client.lod.surface.@Nullable SurfaceTile surface
    ) {
        /** A tile whose data could not be used; it is dropped quietly. */
        static Built failed(long key, int epoch) {
            return new Built(key, epoch, null, 0.0F, 0.0F, 0.0F, null, 0, false, 0L, false, null, null, false, false, null, false, null);
        }
    }
}
