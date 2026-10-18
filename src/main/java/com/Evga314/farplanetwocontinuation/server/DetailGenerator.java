package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Builds detailed tiles from blocks. Chunks loaded in memory are read directly, chunks saved on disk are read from the region files
 * without loading them into the world and kept as small summaries. Chunks that were never generated are made in memory from the world
 * generator when that is allowed, otherwise their cells are marked missing and the client shows the rough terrain there. Nothing is
 * ever written to the world.
 */
final class DetailGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier FALLBACK_BIOME = Identifier.withDefaultNamespace("plains");
    private static final Semaphore DISK_READS = new Semaphore(4);
    private static final int RUNS = DetailData.MAX_RUNS;

    private DetailGenerator() {
    }

    static DetailData generate(
        ServerLevel level, TilePos pos, Long2ObjectMap<LevelChunk> loaded, @Nullable ChunkSummaryCache saved, @Nullable VirtualChunkStore virtual
    ) {
        Context context = new Context(level, pos, loaded, saved, virtual);
        context.prepare(pos);
        byte[] runCounts = new byte[DetailData.CELL_COUNT];
        byte[] biomeIndices = new byte[DetailData.CELL_COUNT];
        short[] tops = new short[DetailData.CELL_COUNT * RUNS];
        short[] bottoms = new short[DetailData.CELL_COUNT * RUNS];
        short[] rawBlocks = new short[DetailData.CELL_COUNT * RUNS];
        short[] rawSides = new short[DetailData.CELL_COUNT * RUNS];
        byte[] blocks = new byte[DetailData.CELL_COUNT * RUNS];
        byte[] sides = new byte[DetailData.CELL_COUNT * RUNS];
        byte[] heights = new byte[DetailData.CELL_COUNT * RUNS];
        byte[] plants = new byte[DetailData.CELL_COUNT];
        long[] missing = new long[DetailData.CELL_COUNT / 64];
        TileGenerator.Palette palette = new TileGenerator.Palette();
        BlockPalette blockPalette = new BlockPalette();

        for (int cz = 0; cz < DetailData.CELLS; cz++) {
            WorkThrottle.yieldToFrames();
            for (int cx = 0; cx < DetailData.CELLS; cx++) {
                int index = cx + cz * DetailData.CELLS;
                Column column = context.column(cx, cz);
                if (column == null) {
                    missing[index >> 6] |= 1L << (index & 63);
                    continue;
                }
                int base = index * RUNS;
                int runs = column.summary.runs(column.x, column.z, tops, bottoms, rawBlocks, rawSides, heights, base);
                runCounts[index] = (byte) runs;
                for (int run = 0; run < runs; run++) {
                    blocks[base + run] = (byte) blockPalette.index(Short.toUnsignedInt(rawBlocks[base + run]));
                    sides[base + run] = (byte) blockPalette.index(Short.toUnsignedInt(rawSides[base + run]));
                }
                int plant = column.summary.plant(column.x, column.z);
                if (plant != 0 && runs > 0) {
                    int plantIndex = blockPalette.index(plant);
                    plants[index] = plantIndex < DetailData.MAX_PALETTE - 1 ? (byte) (plantIndex + 1) : (byte) DetailData.NO_PLANT;
                }
                Holder<Biome> biome = column.summary.biome(column.x, column.z);
                if (biome != null) {
                    biomeIndices[index] = (byte) palette.index(biome);
                }
            }
        }
        if (palette.ids.isEmpty()) {
            palette.ids.add(FALLBACK_BIOME);
        }
        if (blockPalette.ids.isEmpty()) {
            blockPalette.index(BuiltInRegistries.BLOCK.getId(Blocks.STONE));
        }
        return new DetailData(runCounts, biomeIndices, tops, bottoms, blocks, sides, heights, plants, missing, palette.ids, blockPalette.ids);
    }

    /** Blocks of one tile, by name. A tile with more different blocks than fit shows the rest as its first block. */
    private static final class BlockPalette {
        final List<Identifier> ids = new ArrayList<>();
        private final Int2IntOpenHashMap indices = new Int2IntOpenHashMap();

        BlockPalette() {
            this.indices.defaultReturnValue(-1);
        }

        int index(int rawId) {
            int index = this.indices.get(rawId);
            if (index >= 0) {
                return index;
            }
            if (this.ids.size() >= DetailData.MAX_PALETTE) {
                return 0;
            }
            index = this.ids.size();
            this.ids.add(BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(rawId)));
            this.indices.put(rawId, index);
            return index;
        }
    }

    /** One block column inside a chunk summary; x and z are local to the chunk. */
    private record Column(ChunkSummary summary, int x, int z, int top) {
    }

    private static final class Context {
        private final ServerLevel level;
        private final Identifier dimension;
        private final Long2ObjectMap<LevelChunk> loaded;
        private final @Nullable ChunkSummaryCache saved;
        private final @Nullable VirtualChunkStore virtual;
        private final Long2ObjectOpenHashMap<ChunkSummary> summaries = new Long2ObjectOpenHashMap<>();
        private final long baseX;
        private final long baseZ;
        private final int cell;
        private final int probes;
        private final int probeStep;
        private final int minY;
        private final int maxY;

        Context(ServerLevel level, TilePos pos, Long2ObjectMap<LevelChunk> loaded, @Nullable ChunkSummaryCache saved, @Nullable VirtualChunkStore virtual) {
            this.level = level;
            this.dimension = level.dimension().identifier();
            this.loaded = loaded;
            this.saved = saved;
            this.virtual = virtual;
            this.cell = 1 << (pos.level() - 1);
            this.baseX = pos.minX();
            this.baseZ = pos.minZ();
            this.probes = this.cell == 1 ? 1 : 2;
            this.probeStep = this.cell / this.probes;
            this.minY = level.getMinY();
            this.maxY = level.getMaxY();
        }

        /** The highest column of a cell. Several columns are probed per coarse cell so trees and buildings stay visible. */
        @Nullable Column column(int cx, int cz) {
            long cellX = this.baseX + (long) cx * this.cell;
            long cellZ = this.baseZ + (long) cz * this.cell;
            Column best = null;
            for (int pz = 0; pz < this.probes; pz++) {
                for (int px = 0; px < this.probes; px++) {
                    int x = TileGenerator.clampToWorld(cellX + (long) px * this.probeStep + this.probeStep / 2);
                    int z = TileGenerator.clampToWorld(cellZ + (long) pz * this.probeStep + this.probeStep / 2);
                    ChunkSummary summary = this.summary(x >> 4, z >> 4);
                    if (summary == null) {
                        continue;
                    }
                    int top = summary.top(x & 15, z & 15);
                    if (best == null || top > best.top) {
                        best = new Column(summary, x & 15, z & 15, top);
                    }
                }
            }
            return best;
        }


        private @Nullable ChunkSummary summary(int chunkX, int chunkZ) {
            long key = ChunkPos.pack(chunkX, chunkZ);
            if (this.summaries.containsKey(key)) {
                return this.summaries.get(key);
            }
            ChunkSummary summary;
            LevelChunk chunk = this.loaded.get(key);
            if (chunk != null) {
                summary = ChunkSummary.live(chunk, this.minY, this.maxY);
            } else if (this.saved != null) {
                ChunkSummaryCache.Lookup cached = this.saved.get(this.dimension, key);
                if (cached != null) {
                    summary = cached.summary();
                } else {
                    summary = this.readSaved(chunkX, chunkZ);
                    this.saved.put(this.dimension, key, summary);
                }
            } else {
                summary = null;
            }
            this.summaries.put(key, summary);
            return summary;
        }

        /**
         * Looks up every chunk of the tile, then has all chunks without real data made in memory from the world generator in one
         * go. Without that the rough terrain shows there.
         */
        void prepare(TilePos pos) {
            int minChunkX = (int) Math.floorDiv(pos.minX(), 16L);
            int minChunkZ = (int) Math.floorDiv(pos.minZ(), 16L);
            int count = Math.max(1, pos.size() / 16);
            LongArrayList missing = new LongArrayList();
            for (int dz = 0; dz < count; dz++) {
                for (int dx = 0; dx < count; dx++) {
                    if (this.summary(minChunkX + dx, minChunkZ + dz) == null) {
                        missing.add(ChunkPos.pack(minChunkX + dx, minChunkZ + dz));
                    }
                }
            }
            if (this.virtual == null || missing.isEmpty()) {
                return;
            }
            try {
                this.summaries.putAll(this.virtual.chunks(this.level, missing));
            } catch (RuntimeException e) {
                LOGGER.debug("Could not make virtual chunks for {}", pos, e);
            }
        }

        /**
         * Only what the summary needs is decoded: the surface heightmap, the blocks of the sections from the top of the chunk down to
         * how deep runs are looked for, and the biomes where the tops of the columns are. Light, entities, ticks, structures and
         * deep sections, most of a saved chunk, are never touched.
         */
        private @Nullable ChunkSummary summarize(CompoundTag tag) {
            if (!tag.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY).isOrAfter(ChunkStatus.FEATURES)) {
                return null;
            }
            SimpleBitStorage heights = null;
            long[] raw = tag.getCompound("Heightmaps").flatMap(maps -> maps.getLongArray(Heightmap.Types.WORLD_SURFACE.getSerializationKey())).orElse(null);
            if (raw != null) {
                try {
                    heights = new SimpleBitStorage(Mth.ceillog2(this.level.getHeight() + 1), 256, raw);
                } catch (RuntimeException e) {
                    heights = null;
                }
            }
            int lowestTop = this.minY;
            int highestTop = this.maxY + 1;
            if (heights != null) {
                lowestTop = Integer.MAX_VALUE;
                highestTop = Integer.MIN_VALUE;
                for (int column = 0; column < 256; column++) {
                    int top = heights.get(column) + this.minY;
                    lowestTop = Math.min(lowestTop, top);
                    highestTop = Math.max(highestTop, top);
                }
            }
            int minSection = this.level.getMinSectionY();
            int lowestBlocks = SectionPos.blockToSectionCoord(lowestTop - ChunkSummary.SCAN_DEPTH);
            int highestBlocks = SectionPos.blockToSectionCoord(highestTop);
            int lowestBiomes = SectionPos.blockToSectionCoord(lowestTop - 1);
            PalettedContainerFactory factory = this.level.palettedContainerFactory();
            LevelChunkSection[] sections = new LevelChunkSection[this.level.getSectionsCount()];
            for (CompoundTag section : tag.getListOrEmpty("sections").compoundStream().toList()) {
                int y = section.getByteOr("Y", (byte) 0);
                if (y < lowestBlocks || y > highestBlocks || y - minSection < 0 || y - minSection >= sections.length) {
                    continue;
                }
                Optional<CompoundTag> blockTag = section.getCompound("block_states");
                if (blockTag.isEmpty()) {
                    continue;
                }
                PalettedContainer<BlockState> blocks = factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, blockTag.get()).getPartialOrThrow();
                PalettedContainerRO<Holder<Biome>> biomes = y >= lowestBiomes
                    ? section.getCompound("biomes").map(biomeTag -> factory.biomeContainerCodec().parse(NbtOps.INSTANCE, biomeTag).getPartialOrThrow())
                        .orElseGet(factory::createForBiomes)
                    : factory.createForBiomes();
                sections[y - minSection] = new LevelChunkSection(blocks, biomes);
            }
            return ChunkSummary.saved(sections, minSection, heights, this.minY, this.maxY);
        }

        /** Reads a chunk from its region file. Chunks of another data version, not generated far enough or broken are skipped. */
        private @Nullable ChunkSummary readSaved(int chunkX, int chunkZ) {
            if (!RegionFiles.mayContain(this.level, chunkX, chunkZ)) {
                return null;
            }
            Optional<CompoundTag> tag;
            // The region file reader is shared with the server's own chunk loading. Only a few reads at a time are allowed, so the
            // server never waits on LOD work for the chunks players actually need.
            DISK_READS.acquireUninterruptibly();
            try {
                tag = this.level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ)).join();
            } catch (RuntimeException e) {
                LOGGER.debug("Could not read chunk {} {} for a detailed tile", chunkX, chunkZ, e);
                return null;
            } finally {
                DISK_READS.release();
            }
            try {
                if (tag.isEmpty() || tag.get().getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()) {
                    return null;
                }
                long start = ServerPerf.ON ? System.nanoTime() : 0L;
                ChunkSummary summary = this.summarize(tag.get());
                if (ServerPerf.ON) {
                    ServerPerf.SAVED_CHUNKS.add(System.nanoTime() - start);
                }
                return summary;
            } catch (RuntimeException e) {
                LOGGER.debug("Could not read chunk {} {} for a detailed tile", chunkX, chunkZ, e);
                return null;
            }
        }
    }
}
