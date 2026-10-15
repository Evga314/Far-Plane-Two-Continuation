package com.Evga314.farplanetwocontinuation.mixin.common;

import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The parts of terrain generation the virtual chunk generator needs: filling a chunk from noise and building its surface. Vanilla
 * runs them together with cave carvers, which are invisible from far away and expensive, so they are called one by one instead.
 * The fluid picker decides where the sea is, for noise chunks made without aquifers.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccess {
    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> farplane$globalFluidPicker();

    @Invoker("doFill")
    void farplane$doFill(NoiseChunk noiseChunk, ChunkAccess chunk);

    @Invoker("buildSurface")
    void farplane$buildSurface(
        ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager, Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule
    );
}
