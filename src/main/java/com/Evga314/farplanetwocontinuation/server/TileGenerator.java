package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * Builds the rough surface of a tile straight from the world generator's noise. No chunks are generated or loaded, which is what
 * makes it possible to see terrain millions of blocks away. Trees, structures and player builds are not part of the result. Each kind
 * of dimension has its own generator; this class picks it and holds what they share.
 */
public final class TileGenerator {
    private static final Identifier FALLBACK_BIOME = Identifier.withDefaultNamespace("plains");

    private TileGenerator() {
    }

    public static boolean supports(ServerLevel level) {
        return DimensionKind.of(level).hasLod();
    }

    public static TileData generate(ServerLevel level, TilePos pos) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeLookup biomes = new BiomeLookup(generator.getBiomeSource().createUncachedResolver(randomState), pos.level() < 2);
        if (DimensionKind.of(level) == DimensionKind.END && generator instanceof NoiseBasedChunkGenerator noise) {
            return EndTileGenerator.generate(level, noise, randomState, biomes, pos);
        }
        return OverworldTileGenerator.generate(level, pos, generator, randomState, biomes);
    }

    /**
     * Biomes only change every 4 blocks (one quart). Fine tiles have several samples per quart, so the last answers are remembered
     * instead of running the multi-noise lookup again for each of them.
     */
    static final class BiomeLookup {
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
