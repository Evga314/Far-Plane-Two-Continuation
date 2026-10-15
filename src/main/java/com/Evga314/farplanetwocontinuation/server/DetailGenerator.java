package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
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
        byte[] colors = new byte[DetailData.CELL_COUNT * RUNS];
        byte[] sides = new byte[DetailData.CELL_COUNT * RUNS];
        long[] missing = new long[DetailData.CELL_COUNT / 64];
        TileGenerator.Palette palette = new TileGenerator.Palette();

        for (int cz = 0; cz < DetailData.CELLS; cz++) {
            for (int cx = 0; cx < DetailData.CELLS; cx++) {
                int index = cx + cz * DetailData.CELLS;
                Column column = context.column(cx, cz);
                if (column == null) {
                    missing[index >> 6] |= 1L << (index & 63);
                    continue;
                }
                runCounts[index] = (byte) column.summary.runs(column.x, column.z, tops, bottoms, colors, sides, index * RUNS);
                Holder<Biome> biome = column.summary.biome(column.x, column.z);
                if (biome != null) {
                    biomeIndices[index] = (byte) palette.index(biome);
                }
            }
        }
        if (palette.ids.isEmpty()) {
            palette.ids.add(FALLBACK_BIOME);
        }
        return new DetailData(runCounts, biomeIndices, tops, bottoms, colors, sides, missing, palette.ids);
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
                SerializableChunkData data = SerializableChunkData.parse(this.level, this.level.palettedContainerFactory(), tag.get());
                if (data == null || !data.chunkStatus().isOrAfter(ChunkStatus.FEATURES)) {
                    return null;
                }
                LevelChunkSection[] sections = new LevelChunkSection[this.level.getSectionsCount()];
                for (SerializableChunkData.SectionData section : data.sectionData()) {
                    int index = section.y() - this.level.getMinSectionY();
                    if (index >= 0 && index < sections.length && section.chunkSection() != null) {
                        sections[index] = section.chunkSection();
                    }
                }
                SimpleBitStorage heights = null;
                long[] raw = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
                if (raw != null) {
                    try {
                        heights = new SimpleBitStorage(Mth.ceillog2(this.level.getHeight() + 1), 256, raw);
                    } catch (RuntimeException e) {
                        heights = null;
                    }
                }
                return ChunkSummary.saved(sections, this.level.getMinSectionY(), heights, this.minY, this.maxY);
            } catch (RuntimeException e) {
                LOGGER.debug("Could not read chunk {} {} for a detailed tile", chunkX, chunkZ, e);
                return null;
            }
        }
    }
}
