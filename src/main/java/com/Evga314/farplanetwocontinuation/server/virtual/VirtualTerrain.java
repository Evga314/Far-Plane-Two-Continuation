package com.Evga314.farplanetwocontinuation.server.virtual;

import com.Evga314.farplanetwocontinuation.mixin.common.ChunkGeneratorAccess;
import com.Evga314.farplanetwocontinuation.mixin.common.NoiseBasedChunkGeneratorAccess;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.FossilFeature;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.LargeDripstoneFeature;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.SculkPatchFeature;
import net.minecraft.world.level.levelgen.feature.UnderwaterMagmaFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Generates chunks nobody has visited yet, only in memory, with vanilla's own world generation: the same terrain, surface blocks,
 * trees, plants and structures the player will find there later. Nothing is lit, saved or added to the world, and the steps that
 * cannot be seen from above (ores, caves, underground structures and decoration) are skipped.
 *
 * <p>Chunks are made in regions of {@link #REGION} x {@link #REGION}. Like in vanilla, a chunk is only finished once all of its
 * neighbours are decorated too, because trees and buildings reach over chunk borders, so every region is generated with a margin.
 */
public final class VirtualTerrain {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Chunks are stored in regions the size of the coarsest detailed tile. Only the chunks of a region that are needed are made. */
    public static final int REGION = 16;
    /** Biomes are needed one chunk further out than terrain, terrain one further than decoration, decoration one further than the result. */
    private static final int MARGIN = 3;
    /** How far structure starts reach, in chunks; the same as vanilla's structure reference range. */
    private static final int STRUCTURE_RANGE = 8;
    private static final int START_CACHE_SIZE = 32768;
    /**
     * Only a band around the surface is filled from noise: from this far below the lowest estimated surface of a chunk to
     * {@link #BAND_ABOVE} above the highest. Everything else is never seen from above, and filling the whole height of the world is
     * most of the cost of a chunk.
     */
    private static final int BAND_BELOW = 24;
    /** Surface rules only run this deep under the top of a column; deeper blocks keep the stone they were filled with. */
    public static final int SURFACE_RULE_DEPTH = 8;
    public static final int BADLANDS_SURFACE_RULE_DEPTH = 40;
    /**
     * Features in the visible steps that only ever place blocks underground (glow lichen, geodes, dripstone, sculk, fossils, magma
     * under water). They are a good share of decoration time and can never be seen from above.
     */
    private static final Set<Class<?>> HIDDEN_FEATURES = Set.of(
        MultifaceGrowthFeature.class, GeodeFeature.class, LargeDripstoneFeature.class, SculkPatchFeature.class, FossilFeature.class,
        UnderwaterMagmaFeature.class
    );
    private static final ThreadLocal<Boolean> BUILDING_SURFACE = ThreadLocal.withInitial(() -> false);
    private static final int BAND_ABOVE = 80;
    /**
     * Terrain of recently built chunks. Every region also builds the terrain of a margin around it, which is the inside of its
     * neighbours, so without this most chunks would be built two or three times. About 30 KB per chunk.
     */
    private static final int TERRAIN_CACHE_SIZE = 800;
    private static final EnumSet<Heightmap.Types> RESTORED_HEIGHTMAPS = EnumSet.of(
        Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.MOTION_BLOCKING,
        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE
    );
    private static final Set<GenerationStep.Decoration> VISIBLE_STEPS = EnumSet.of(
        GenerationStep.Decoration.RAW_GENERATION,
        GenerationStep.Decoration.LAKES,
        GenerationStep.Decoration.LOCAL_MODIFICATIONS,
        GenerationStep.Decoration.SURFACE_STRUCTURES,
        GenerationStep.Decoration.VEGETAL_DECORATION,
        GenerationStep.Decoration.TOP_LAYER_MODIFICATION
    );

    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    private final NoiseGeneratorSettings settings;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState structureState;
    private final StructureTemplateManager templates;
    private final boolean structures;
    private final DensitySampler surfaceEstimate;
    /**
     * The generator settings without aquifers. Aquifers decide where water and lava are in caves, which is never visible from above;
     * without them the sea simply fills everything below sea level, which is what the surface looks like anyway.
     */
    private final NoiseGeneratorSettings surfaceSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final ChunkStep terrainStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.TERRAIN);
    private final ChunkStep featureStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
    private final Map<Integer, List<Structure>> structuresByStep = new LinkedHashMap<>();
    /** Structure starts per chunk; computing them is cheap for most chunks, but every region needs them in a wide area around it. */
    private final Map<Long, Map<Structure, StructureStart>> starts = new LinkedHashMap<>(4096, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<Structure, StructureStart>> eldest) {
            return this.size() > START_CACHE_SIZE;
        }
    };

    private final Map<Long, LevelChunkSection[]> terrainCache = new LinkedHashMap<>(1024, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LevelChunkSection[]> eldest) {
            return this.size() > TERRAIN_CACHE_SIZE;
        }
    };

    private VirtualTerrain(ServerLevel level, NoiseBasedChunkGenerator generator) {
        this.level = level;
        this.generator = generator;
        this.settings = generator.generatorSettings().value();
        this.randomState = level.getChunkSource().randomState();
        this.surfaceEstimate = this.randomState.getSampler(this.settings.noiseRouter().chunkSurfaceLevel());
        NoiseGeneratorSettings s = this.settings;
        this.surfaceSettings = new NoiseGeneratorSettings(
            s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), s.noiseRouter(), s.materialRule(), s.spawnTarget(), s.seaLevel(),
            s.disableMobGeneration(), Optional.empty(), s.useLegacyRandomSource(), s.debugFunctions()
        );
        this.fluidPicker = ((NoiseBasedChunkGeneratorAccess) (Object) generator).farplane$globalFluidPicker().get();
        this.structureState = level.getChunkSource().getGeneratorState();
        this.templates = level.getServer().getStructureTemplateManager();
        this.structures = level.getServer().getWorldGenSettings().options().generateStructures();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        registry.stream().forEach(structure -> this.structuresByStep.computeIfAbsent(structure.step().ordinal(), step -> new ArrayList<>()).add(structure));
    }

    /** True while surface rules run for a virtual chunk, see {@code MaterialSystemMixin}. */
    public static boolean isBuildingSurface() {
        return BUILDING_SURFACE.get();
    }

    /** Only levels with the standard noise generator are supported; flat and custom generators are left to the rough terrain. */
    public static @Nullable VirtualTerrain create(ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise ? new VirtualTerrain(level, noise) : null;
    }

    /**
     * Generates a rectangle of chunks and returns them row by row ({@code chunks[x + z * width]}), finished as far as they can be
     * seen from above. Chunks keep only their blocks and biomes.
     */
    public ProtoChunk[] generate(int minX, int minZ, int width, int depth) {
        int sizeX = width + MARGIN * 2;
        int sizeZ = depth + MARGIN * 2;
        int originX = minX - MARGIN;
        int originZ = minZ - MARGIN;
        ProtoChunk[] area = new ProtoChunk[sizeX * sizeZ];
        int[] bands = this.surfaceBands(originX, originZ, sizeX, sizeZ);
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int index = x + z * sizeX;
                ChunkPos pos = new ChunkPos(originX + x, originZ + z);
                ProtoChunk chunk = this.restoreTerrain(pos);
                if (chunk == null) {
                    chunk = this.newChunk(pos);
                    this.fillBiomes(chunk, bands[index * 2], bands[index * 2 + 1]);
                    chunk.setPersistedStatus(ChunkStatus.BIOMES);
                }
                chunk.setAllStarts(this.startsAt(pos.x(), pos.z()));
                area[index] = chunk;
            }
        }
        Area chunks = new Area(this, originX, originZ, sizeX, sizeZ, area);

        for (int z = 1; z < sizeZ - 1; z++) {
            for (int x = 1; x < sizeX - 1; x++) {
                int index = x + z * sizeX;
                ProtoChunk chunk = area[index];
                this.addReferences(chunk);
                if (!chunk.getPersistedStatus().isOrAfter(ChunkStatus.TERRAIN)) {
                    this.buildTerrain(chunks, chunk, bands[index * 2], bands[index * 2 + 1]);
                    this.storeTerrain(chunk);
                }
            }
        }
        for (int z = 2; z < sizeZ - 2; z++) {
            for (int x = 2; x < sizeX - 2; x++) {
                this.decorate(chunks, area[x + z * sizeX]);
            }
        }

        ProtoChunk[] result = new ProtoChunk[width * depth];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                result[x + z * width] = area[(x + MARGIN) + (z + MARGIN) * sizeX];
            }
        }
        return result;
    }

    /**
     * The band of heights filled for every chunk of an area, as pairs of bottom and top (exclusive), aligned to sections. The
     * generator's own 2D surface estimate is sampled every 8 blocks; it ignores overhangs and jagged peaks, which the margins cover.
     */
    private int[] surfaceBands(int originX, int originZ, int sizeX, int sizeZ) {
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        int grid = sizeX * 2 + 1;
        int[] estimates = new int[grid * (sizeZ * 2 + 1)];
        for (int gz = 0; gz <= sizeZ * 2; gz++) {
            for (int gx = 0; gx < grid; gx++) {
                float value = this.surfaceEstimate.sampleValue(context, originX * 16 + gx * 8, 0, originZ * 16 + gz * 8);
                estimates[gx + gz * grid] = Float.isFinite(value) ? Mth.floor(value) : this.settings.seaLevel();
            }
        }
        int worldMin = this.level.getMinY();
        int worldTop = this.level.getMaxY() + 1;
        int[] bands = new int[sizeX * sizeZ * 2];
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                for (int dz = 0; dz <= 2; dz++) {
                    for (int dx = 0; dx <= 2; dx++) {
                        int estimate = estimates[(x * 2 + dx) + (z * 2 + dz) * grid];
                        low = Math.min(low, estimate);
                        high = Math.max(high, estimate);
                    }
                }
                // Water reaches up to sea level even where the ground is far below it.
                high = Math.max(high, this.settings.seaLevel());
                int bottom = Mth.clamp(Math.floorDiv(low - BAND_BELOW, 16) * 16, worldMin, worldTop);
                int top = Mth.clamp(Math.floorDiv(high + BAND_ABOVE + 15, 16) * 16, bottom, worldTop);
                bands[(x + z * sizeX) * 2] = bottom;
                bands[(x + z * sizeX) * 2 + 1] = top;
            }
        }
        return bands;
    }

    /** Vanilla's biome step, for the sections of the band only. */
    private void fillBiomes(ProtoChunk chunk, int bottom, int top) {
        if (top <= bottom) {
            return;
        }
        DensityBufferPool pool = this.randomState.acquireDensityBufferPool();
        try {
            Climate.Sampler sampler = this.randomState.createClimateSampler(SamplerContext.builder().enableCaches().useBufferArena(pool).build());
            ChunkPos pos = chunk.getPos();
            int quartX = QuartPos.fromBlock(pos.getMinBlockX());
            int quartZ = QuartPos.fromBlock(pos.getMinBlockZ());
            BiomeResolver resolver = this.generator.getBiomeSource()
                .createResolverForChunk(sampler, quartX, QuartPos.fromBlock(bottom), quartZ, 4, QuartPos.fromBlock(top - bottom), 4);
            for (int sectionY = SectionPos.blockToSectionCoord(bottom); sectionY < SectionPos.blockToSectionCoord(top); sectionY++) {
                chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY)).fillBiomesFromNoise(resolver, quartX, QuartPos.fromSection(sectionY), quartZ);
            }
        } finally {
            this.randomState.releaseDensityBufferPool(pool);
        }
    }

    /** A copy of a chunk whose terrain was built before, ready to be decorated again; null if it is not cached. */
    private @Nullable ProtoChunk restoreTerrain(ChunkPos pos) {
        LevelChunkSection[] cached;
        synchronized (this.terrainCache) {
            cached = this.terrainCache.get(pos.pack());
        }
        if (cached == null) {
            return null;
        }
        LevelChunkSection[] sections = new LevelChunkSection[cached.length];
        for (int i = 0; i < cached.length; i++) {
            sections[i] = cached[i].copy();
        }
        ProtoChunk chunk = new ProtoChunk(
            pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), this.level, this.level.palettedContainerFactory(), null
        );
        Heightmap.primeHeightmaps(chunk, RESTORED_HEIGHTMAPS);
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        return chunk;
    }

    /** Keeps a copy of freshly built terrain, before decoration changes it. */
    private void storeTerrain(ProtoChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        LevelChunkSection[] copy = new LevelChunkSection[sections.length];
        for (int i = 0; i < sections.length; i++) {
            copy[i] = sections[i].copy();
        }
        synchronized (this.terrainCache) {
            this.terrainCache.put(chunk.getPos().pack(), copy);
        }
    }

    private ProtoChunk newChunk(ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.palettedContainerFactory(), null);
    }

    /** Structure starts in one chunk, worked out like vanilla does, without touching the world. */
    private Map<Structure, StructureStart> startsAt(int chunkX, int chunkZ) {
        if (!this.structures) {
            return Map.of();
        }
        long key = ChunkPos.pack(chunkX, chunkZ);
        synchronized (this.starts) {
            Map<Structure, StructureStart> cached = this.starts.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Map<Structure, StructureStart> found = Map.of();
        boolean candidate = false;
        for (Holder<StructureSet> set : this.structureState.possibleStructureSets()) {
            if (set.value().placement().isStructureChunk(this.structureState, chunkX, chunkZ)) {
                candidate = true;
                break;
            }
        }
        if (candidate) {
            ProtoChunk chunk = this.newChunk(new ChunkPos(chunkX, chunkZ));
            try {
                this.generator.createStructures(
                    this.level.registryAccess(), this.structureState, this.level.structureManager(), chunk, this.templates, this.level.dimension()
                );
                Map<Structure, StructureStart> all = chunk.getAllStarts();
                found = all.isEmpty() ? Map.of() : Map.copyOf(all);
            } catch (RuntimeException e) {
                LOGGER.debug("Could not work out structures in virtual chunk {} {}", chunkX, chunkZ, e);
            }
        }
        synchronized (this.starts) {
            this.starts.put(key, found);
        }
        return found;
    }

    /** The same references vanilla adds in its structure references step: every nearby start whose bounds reach this chunk. */
    private void addReferences(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (int sourceZ = pos.z() - STRUCTURE_RANGE; sourceZ <= pos.z() + STRUCTURE_RANGE; sourceZ++) {
            for (int sourceX = pos.x() - STRUCTURE_RANGE; sourceX <= pos.x() + STRUCTURE_RANGE; sourceX++) {
                for (StructureStart start : this.startsAt(sourceX, sourceZ).values()) {
                    if (start.isValid() && start.getBoundingBox().intersects(minX, minZ, minX + 15, minZ + 15)) {
                        chunk.addReferenceForStructure(start.getStructure(), ChunkPos.pack(sourceX, sourceZ));
                    }
                }
            }
        }
    }

    private void buildTerrain(Area chunks, ProtoChunk chunk, int bottom, int top) {
        VirtualRegion region = chunks.region(this.terrainStep, chunk);
        StructureManager structureManager = this.level.structureManager().forWorldGenRegion(region);
        NoiseSettings full = this.settings.noiseSettings();
        int bandMin = Math.max(full.minY(), bottom);
        int bandTop = Math.min(full.minY() + full.height(), top);
        NoiseSettings noiseSettings = new NoiseSettings(bandMin, Math.max(0, bandTop - bandMin)).clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        if (noiseSettings.height() > 0) {
            NoiseBasedChunkGeneratorAccess access = (NoiseBasedChunkGeneratorAccess) (Object) this.generator;
            ChunkPos pos = chunk.getPos();
            DensityVolume volume = new DensityVolume(16, noiseSettings.height(), 16, pos.getMinBlockX(), noiseSettings.minY(), pos.getMinBlockZ());
            Beardifier beardifier = Beardifier.forStructuresInChunk(structureManager, pos);
            try (NoiseChunk noiseChunk = new NoiseChunk(this.randomState, beardifier, this.surfaceSettings, this.fluidPicker, Blender.empty(), volume)) {
                access.farplane$doFill(noiseChunk, chunk);
                BUILDING_SURFACE.set(true);
                try {
                    access.farplane$buildSurface(
                        chunk, noiseChunk, this.randomState, region.getBiomeManager(), possibleBiomes(region, pos), this.settings.materialRule().value()
                    );
                } finally {
                    BUILDING_SURFACE.set(false);
                }
            }
        }
        Heightmap.primeHeightmaps(
            chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE)
        );
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
    }

    private static Set<Holder<Biome>> possibleBiomes(VirtualRegion region, ChunkPos center) {
        Set<Holder<Biome>> biomes = new ObjectArraySet<>();
        for (int z = center.z() - 1; z <= center.z() + 1; z++) {
            for (int x = center.x() - 1; x <= center.x() + 1; x++) {
                region.getChunk(x, z).collectBiomesInPalette(biomes);
            }
        }
        return biomes;
    }

    /**
     * Vanilla's biome decoration, limited to the steps that change what is visible from above. Every feature and structure gets the
     * same random seed as in vanilla, so trees and buildings end up exactly where they will be once the chunk is really generated.
     */
    private void decorate(Area chunks, ProtoChunk chunk) {
        VirtualRegion region = chunks.region(this.featureStep, chunk);
        StructureManager structureManager = this.level.structureManager().forWorldGenRegion(region);
        ChunkPos centerPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(centerPos, region.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        ChunkGeneratorAccess access = (ChunkGeneratorAccess) (Object) this.generator;
        List<FeatureSorter.StepFeatureData> featureList = access.farplane$featuresPerStep().get();
        Function<Holder<Biome>, BiomeGenerationSettings> settingsGetter = access.farplane$generationSettingsGetter();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(region.getSeed(), origin.getX(), origin.getZ());
        Set<Holder<Biome>> possibleBiomes = possibleBiomes(region, centerPos);
        possibleBiomes.retainAll(this.generator.getBiomeSource().possibleBiomes());
        FeaturePlacer placer = new FeaturePlacer(region, this.generator);
        BoundingBox writableArea = writableArea(chunk);
        GenerationStep.Decoration[] steps = GenerationStep.Decoration.values();
        int stepCount = Math.max(steps.length, featureList.size());

        for (int step = 0; step < stepCount; step++) {
            if (step < steps.length && !VISIBLE_STEPS.contains(steps[step])) {
                continue;
            }
            int index = 0;
            if (this.structures) {
                for (Structure structure : this.structuresByStep.getOrDefault(step, List.of())) {
                    random.setFeatureSeed(decorationSeed, index, step);
                    try {
                        for (StructureStart start : structureManager.startsForStructure(sectionPos.x(), sectionPos.z(), structure)) {
                            start.placeInChunk(region, structureManager, this.generator, random, writableArea, centerPos);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.debug("Could not place structure in virtual chunk {}", centerPos, e);
                    }
                    index++;
                }
            }
            if (step >= featureList.size()) {
                continue;
            }
            FeatureSorter.StepFeatureData stepData = featureList.get(step);
            IntSet possibleFeatures = new IntArraySet();
            for (Holder<Biome> biome : possibleBiomes) {
                List<HolderSet<PlacedFeature>> features = settingsGetter.apply(biome).features();
                if (step < features.size()) {
                    for (Holder<PlacedFeature> feature : features.get(step)) {
                        possibleFeatures.add(stepData.indexMapping().applyAsInt(feature.value()));
                    }
                }
            }
            int[] indices = possibleFeatures.toIntArray();
            Arrays.sort(indices);
            for (int globalIndex : indices) {
                PlacedFeature feature = stepData.features().get(globalIndex);
                if (HIDDEN_FEATURES.contains(feature.feature().value().getClass())) {
                    continue;
                }
                random.setFeatureSeed(decorationSeed, globalIndex, step);
                try {
                    placer.placeWithBiomeCheck(feature, random, origin);
                } catch (RuntimeException e) {
                    LOGGER.debug("Could not place feature in virtual chunk {}", centerPos, e);
                }
            }
        }
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }

    private static BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getHeightAccessorForGeneration().getMinY() + 1;
        int maxY = chunk.getHeightAccessorForGeneration().getMaxY();
        return new BoundingBox(pos.getMinBlockX(), minY, pos.getMinBlockZ(), pos.getMinBlockX() + 15, maxY, pos.getMinBlockZ() + 15);
    }

    /**
     * The chunks of one region being generated, plus the structure starts around it. Regions over them see chunks of the area as they
     * are, and further out only the structure starts that vanilla would have there. Holders are made once per position and shared by
     * all regions of the batch.
     */
    private static final class Area {
        private final VirtualTerrain terrain;
        private final int originX;
        private final int originZ;
        private final int sizeX;
        private final int sizeZ;
        private final ProtoChunk[] chunks;
        private final Long2ObjectOpenHashMap<VirtualHolder> holders = new Long2ObjectOpenHashMap<>();
        /** Stands in for every chunk outside the area without structure starts: nothing is read from those but their starts. */
        private final ProtoChunk empty;

        Area(VirtualTerrain terrain, int originX, int originZ, int sizeX, int sizeZ, ProtoChunk[] chunks) {
            this.terrain = terrain;
            this.originX = originX;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.chunks = chunks;
            this.empty = terrain.newChunk(new ChunkPos(originX - STRUCTURE_RANGE - 1, originZ - STRUCTURE_RANGE - 1));
            this.empty.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
        }

        VirtualRegion region(ChunkStep step, ProtoChunk center) {
            ChunkPos centerPos = center.getPos();
            int range = step.directDependencies().size() - 1;
            StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(centerPos.x(), centerPos.z(), range, this::holder);
            return new VirtualRegion(this.terrain.level, cache, step, center);
        }

        private VirtualHolder holder(int x, int z) {
            long key = ChunkPos.pack(x, z);
            VirtualHolder holder = this.holders.get(key);
            if (holder == null) {
                holder = new VirtualHolder(new ChunkPos(x, z), this.chunkAt(x, z));
                this.holders.put(key, holder);
            }
            return holder;
        }

        private ChunkAccess chunkAt(int x, int z) {
            int localX = x - this.originX;
            int localZ = z - this.originZ;
            if (localX >= 0 && localZ >= 0 && localX < this.sizeX && localZ < this.sizeZ) {
                return this.chunks[localX + localZ * this.sizeX];
            }
            Map<Structure, StructureStart> starts = this.terrain.startsAt(x, z);
            if (starts.isEmpty()) {
                return this.empty;
            }
            ProtoChunk chunk = this.terrain.newChunk(new ChunkPos(x, z));
            chunk.setAllStarts(starts);
            chunk.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
            return chunk;
        }
    }
}
