package com.Evga314.farplanetwocontinuation.mixin.common;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the virtual chunk generator decorate a chunk that is not part of the world, step by step like vanilla. */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccess {
    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> farplane$featuresPerStep();

    @Accessor("generationSettingsGetter")
    Function<Holder<Biome>, BiomeGenerationSettings> farplane$generationSettingsGetter();
}
