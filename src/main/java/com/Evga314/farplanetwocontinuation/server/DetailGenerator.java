package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.tile.BlockLook;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.world.level.chunk.DataLayer;
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
    /** Blocks kept under the top of a column even with no lower neighbour, so its sides get the color of what lies under the top. */
    private static final int SIDE_KEEP = DetailData.SIDE_DEPTH + 1;
    /** Blocks kept under the lowest place a side or floor can be seen from. */
    private static final int WALL_MARGIN = 2;
    /** How far into a cave, in blocks from its mouth, its floor and walls are sent. Deeper in it is dark and cannot be seen from afar. */
    private static final int CAVE_REACH = 16;
    /**
     * How far around a tile, in blocks, the columns are looked at too, where their chunks are there already: caves are followed
     * across the edge and the sides of the cells at the edge know their neighbours.
     */
    private static final int HALO_BLOCKS = CAVE_REACH;
    private static final int[] DIRECTION_X = {1, -1, 0, 0};
    private static final int[] DIRECTION_Z = {0, 0, 1, -1};

    private DetailGenerator() {
    }

    static DetailData generate(
        ServerLevel level, TilePos pos, Long2ObjectMap<LevelChunk> loaded, @Nullable ChunkSummaryCache saved, @Nullable VirtualChunkStore virtual,
        ChunkSummary.Scan scan
    ) {
        long phase = ServerPerf.ON ? System.nanoTime() : 0L;
        Context context = new Context(level, pos, loaded, saved, virtual, scan);
        context.prepare(pos);
        if (ServerPerf.ON) {
            long now = System.nanoTime();
            ServerPerf.DETAIL_PREPARE.add(now - phase);
            phase = now;
        }
        int cells = DetailData.CELLS;
        // The cells of the tile with a margin around it, where what lies next to the tile is looked at too, see visibleSegments.
        int halo = Math.max(1, HALO_BLOCKS / context.cell);
        int side = cells + 2 * halo;
        int area = side * side;
        int[] counts = new int[area];
        int[] offsets = new int[area];
        short[] stackTops = new short[area];
        byte[] fractions = new byte[area];
        // Missing cells have no column; the data still wants a valid top block height for them.
        Arrays.fill(fractions, (byte) 16);
        byte[] biomeIndices = new byte[DetailData.CELL_COUNT];
        long[] missing = new long[DetailData.CELL_COUNT / 64];
        short[] rawBlocks = new short[16384];
        short[] rawHeights = new short[16384];
        byte[] rawLights = new byte[16384];
        boolean[] litColumns = new boolean[area];
        int total = 0;
        int haloKnown = 0;
        TileGenerator.Palette palette = new TileGenerator.Palette();

        for (int ez = 0; ez < side; ez++) {
            WorkThrottle.yieldToFrames();
            int cz = ez - halo;
            for (int ex = 0; ex < side; ex++) {
                int cx = ex - halo;
                boolean inside = cx >= 0 && cz >= 0 && cx < cells && cz < cells;
                int extended = ex + ez * side;
                Column column = context.column(cx, cz);
                if (column == null) {
                    if (inside) {
                        int index = cx + cz * cells;
                        missing[index >> 6] |= 1L << (index & 63);
                    }
                    continue;
                }
                haloKnown += inside ? 0 : 1;
                int count = column.summary.segmentCount(column.x, column.z);
                if (total + count > rawBlocks.length) {
                    int capacity = Math.max(total + count, rawBlocks.length * 2);
                    rawBlocks = Arrays.copyOf(rawBlocks, capacity);
                    rawHeights = Arrays.copyOf(rawHeights, capacity);
                    rawLights = Arrays.copyOf(rawLights, capacity);
                }
                column.summary.segments(column.x, column.z, rawBlocks, rawHeights, rawLights, total);
                counts[extended] = count;
                offsets[extended] = total;
                litColumns[extended] = column.summary.lit();
                total += count;
                stackTops[extended] = (short) column.summary.stackTop(column.x, column.z);
                fractions[extended] = (byte) column.summary.topFraction(column.x, column.z);
                if (inside) {
                    Holder<Biome> biome = column.summary.biome(column.x, column.z);
                    if (biome != null) {
                        biomeIndices[cx + cz * cells] = (byte) palette.index(biome);
                    }
                }
            }
        }

        long trimStart = ServerPerf.ON ? System.nanoTime() : 0L;
        if (ServerPerf.ON) {
            ServerPerf.DETAIL_COLLECT.add(trimStart - phase);
        }
        // Over the void nothing is left out: the last segment sent goes on to the bottom of the world, and there it must be air.
        int[] kept = scan.voidBelow() ? counts : visibleSegments(counts, offsets, rawBlocks, rawHeights, stackTops, side, context.cell);
        if (ServerPerf.ON) {
            phase = System.nanoTime();
            ServerPerf.DETAIL_TRIM.add(phase - trimStart);
        }

        // Where every cell sent comes from in the grid with the margin: the cells of the tile, then the ring around it.
        int[] source = new int[DetailData.TOTAL_CELLS];
        for (int cz = -1; cz <= cells; cz++) {
            for (int cx = -1; cx <= cells; cx++) {
                int index = DetailData.cellIndex(cx, cz);
                if (index >= 0) {
                    source[index] = cx + halo + (cz + halo) * side;
                }
            }
        }
        // Columns cut off under a cave: what the last segment kept would take over holds air, the part of the cave not followed. That
        // part goes as a segment of its own, marked, so it is drawn dark instead of as lit ground.
        boolean[] plugged = new boolean[kept.length];
        Int2BooleanOpenHashMap solidBlocks = new Int2BooleanOpenHashMap();
        for (int from = 0; from < kept.length; from++) {
            int keep = kept[from];
            if (keep == 0 || keep >= counts[from] || keep >= DetailData.MAX_SEGMENTS) {
                continue;
            }
            for (int s = keep; s < counts[from]; s++) {
                if (!solid(solidBlocks, rawBlocks[offsets[from] + s])) {
                    plugged[from] = true;
                    break;
                }
            }
        }
        long[] lit = new long[DetailData.TOTAL_CELLS / 64];
        boolean allLit = true;
        for (int index = 0; index < DetailData.TOTAL_CELLS; index++) {
            if (litColumns[source[index]]) {
                lit[index >> 6] |= 1L << (index & 63);
            } else if (index < DetailData.CELL_COUNT && counts[source[index]] > 0) {
                allLit = false;
            }
        }
        // The light from outside the ring only matters where the client works it out itself, see DetailData#edgeLights.
        int[] edgeLights = allLit ? new int[0] : edgeLights(counts, offsets, kept, rawBlocks, rawHeights, rawLights, stackTops, side, halo, context.cell);
        int sent = 0;
        int sentInside = 0;
        int scannedInside = 0;
        int withData = 0;
        for (int index = 0; index < DetailData.TOTAL_CELLS; index++) {
            int keep = kept[source[index]] + (plugged[source[index]] ? 1 : 0);
            sent += keep;
            if (index < DetailData.CELL_COUNT) {
                sentInside += keep;
                scannedInside += counts[source[index]];
                withData += keep > 0 ? 1 : 0;
            }
        }
        short[] segBlocks = new short[sent];
        short[] segHeights = new short[sent];
        byte[] segLights = new byte[sent];
        byte[] segCounts = new byte[DetailData.TOTAL_CELLS];
        short[] sentTops = new short[DetailData.TOTAL_CELLS];
        byte[] sentFractions = new byte[DetailData.TOTAL_CELLS];
        BlockPalette blockPalette = new BlockPalette();
        int at = 0;
        for (int index = 0; index < DetailData.TOTAL_CELLS; index++) {
            int from = source[index];
            int keep = kept[from];
            sentFractions[index] = fractions[from];
            if (keep == 0) {
                continue;
            }
            sentTops[index] = stackTops[from];
            int offset = offsets[from];
            for (int s = 0; s < keep; s++) {
                segBlocks[at + s] = (short) blockPalette.index(Short.toUnsignedInt(rawBlocks[offset + s]));
                segHeights[at + s] = rawHeights[offset + s];
                segLights[at + s] = rawLights[offset + s];
            }
            // The last segment sent takes over everything under it, or the cave plug does.
            int below = 0;
            for (int s = keep; s < counts[from]; s++) {
                below += rawHeights[offset + s];
            }
            if (plugged[from]) {
                segBlocks[at + keep] = (short) blockPalette.plug();
                segHeights[at + keep] = (short) below;
                keep++;
            } else {
                segHeights[at + keep - 1] += (short) below;
            }
            segCounts[index] = (byte) keep;
            at += keep;
        }
        if (ServerPerf.ON) {
            ServerPerf.DETAIL_CELLS.addAndGet(withData);
            ServerPerf.DETAIL_SEGMENTS_SCANNED.addAndGet(scannedInside);
            ServerPerf.DETAIL_SEGMENTS_SENT.addAndGet(sentInside);
            ServerPerf.DETAIL_HALO_CELLS.addAndGet(area - DetailData.CELL_COUNT);
            ServerPerf.DETAIL_HALO_KNOWN.addAndGet(haloKnown);
            ServerPerf.DETAIL_BUILD.add(System.nanoTime() - phase);
        }
        if (palette.ids.isEmpty()) {
            palette.ids.add(FALLBACK_BIOME);
        }
        if (blockPalette.ids.isEmpty()) {
            blockPalette.index(BuiltInRegistries.BLOCK.getId(Blocks.STONE));
        }
        return new DetailData(
            level.getMinY(), segCounts, sentTops, sentFractions, biomeIndices, segBlocks, segHeights, segLights, missing, lit, edgeLights, palette.ids,
            blockPalette.ids, blockPalette.wet.toBooleanArray()
        );
    }

    /**
     * How many segments of each cell, from the top, can be seen from outside; the rest stays in the ground and is not sent. A side of
     * a column is seen down to the ground of its lowest neighbour. Caves are followed from where they open to the side, from cell to
     * cell through the air pockets that touch, up to {@link #CAVE_REACH} blocks deep: their floors and the walls around them are kept.
     * Caves closed all around are left out. The last segment kept is always a block that hides what is under it, since it is drawn
     * down to the bottom of the world. Works on a square grid {@code side} cells wide; cells without a column do not count.
     */
    private static int[] visibleSegments(int[] counts, int[] offsets, short[] blocks, short[] heights, short[] stackTops, int side, int cellSize) {
        int cells = side * side;
        Int2BooleanOpenHashMap solidBlocks = new Int2BooleanOpenHashMap();
        // Top of the first block of each column that hides what is under it; Integer.MIN_VALUE for missing cells.
        int[] ground = new int[cells];
        // Air pockets under the ground of each column, which may be the inside of a cave: their bottom, top and cell.
        IntArrayList pocketBottoms = new IntArrayList();
        IntArrayList pocketTops = new IntArrayList();
        IntArrayList pocketCells = new IntArrayList();
        int[] firstPocket = new int[cells + 1];
        for (int index = 0; index < cells; index++) {
            firstPocket[index] = pocketBottoms.size();
            int count = counts[index];
            ground[index] = Integer.MIN_VALUE;
            if (count == 0) {
                continue;
            }
            int offset = offsets[index];
            int top = stackTops[index];
            int pocketTop = Integer.MIN_VALUE;
            for (int s = 0; s < count; s++) {
                int bottom = top - heights[offset + s];
                boolean solid = solid(solidBlocks, blocks[offset + s]);
                if (ground[index] == Integer.MIN_VALUE) {
                    if (solid) {
                        ground[index] = top;
                    }
                } else if (!solid) {
                    if (pocketTop == Integer.MIN_VALUE) {
                        pocketTop = top;
                    }
                } else if (pocketTop != Integer.MIN_VALUE) {
                    pocketBottoms.add(top);
                    pocketTops.add(pocketTop);
                    pocketCells.add(index);
                    pocketTop = Integer.MIN_VALUE;
                }
                top = bottom;
            }
            if (ground[index] == Integer.MIN_VALUE) {
                // Nothing in the column hides what is under it (deep water): it is all sent anyway.
                ground[index] = top;
            }
        }
        firstPocket[cells] = pocketBottoms.size();

        // Lowest height each column is sent down to.
        int[] keepTo = new int[cells];
        for (int index = 0; index < cells; index++) {
            keepTo[index] = ground[index] - SIDE_KEEP;
            for (int direction = 0; direction < 4; direction++) {
                int neighbour = neighbour(index, direction, side);
                if (neighbour >= 0 && ground[neighbour] != Integer.MIN_VALUE) {
                    keepTo[index] = Math.min(keepTo[index], ground[neighbour] - WALL_MARGIN);
                }
            }
        }

        // Caves, followed from their mouths: a pocket reaching above the ground of a neighbour opens to the side.
        int pockets = pocketBottoms.size();
        if (pockets > 0) {
            int maxSteps = Math.max(1, CAVE_REACH / cellSize);
            int[] steps = new int[pockets];
            Arrays.fill(steps, -1);
            IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
            for (int pocket = 0; pocket < pockets; pocket++) {
                int index = pocketCells.getInt(pocket);
                for (int direction = 0; direction < 4; direction++) {
                    int neighbour = neighbour(index, direction, side);
                    if (neighbour >= 0 && ground[neighbour] != Integer.MIN_VALUE && pocketTops.getInt(pocket) > ground[neighbour]) {
                        steps[pocket] = 0;
                        queue.enqueue(pocket);
                        break;
                    }
                }
            }
            while (!queue.isEmpty()) {
                int pocket = queue.dequeueInt();
                int index = pocketCells.getInt(pocket);
                int bottom = pocketBottoms.getInt(pocket);
                int top = pocketTops.getInt(pocket);
                keepTo[index] = Math.min(keepTo[index], bottom - WALL_MARGIN);
                for (int direction = 0; direction < 4; direction++) {
                    int neighbour = neighbour(index, direction, side);
                    if (neighbour < 0 || ground[neighbour] == Integer.MIN_VALUE) {
                        continue;
                    }
                    // The wall of the neighbour facing the pocket can be seen.
                    keepTo[neighbour] = Math.min(keepTo[neighbour], bottom - WALL_MARGIN);
                    if (steps[pocket] + 1 >= maxSteps) {
                        continue;
                    }
                    for (int next = firstPocket[neighbour]; next < firstPocket[neighbour + 1]; next++) {
                        if (steps[next] < 0 && pocketBottoms.getInt(next) < top && bottom < pocketTops.getInt(next)) {
                            steps[next] = steps[pocket] + 1;
                            queue.enqueue(next);
                        }
                    }
                }
            }
        }

        int[] kept = new int[cells];
        for (int index = 0; index < cells; index++) {
            int count = counts[index];
            int offset = offsets[index];
            int keep = count;
            int top = stackTops[index];
            for (int s = 0; s < count; s++) {
                top -= heights[offset + s];
                if (top <= keepTo[index] && solid(solidBlocks, blocks[offset + s])) {
                    keep = s + 1;
                    break;
                }
            }
            kept[index] = keep;
        }
        return kept;
    }

    /** Whether a block hides what is under and behind it: not air, a plant, a liquid or a block seen through. */
    private static boolean solid(Int2BooleanOpenHashMap cache, short block) {
        int id = Short.toUnsignedInt(block);
        if (id == 0) {
            return false;
        }
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        // Standing in water or not, a block hides what is under it by its shape.
        BlockLook.Look look = BlockLook.look(BuiltInRegistries.BLOCK.byId(BlockLook.segmentBlock(id)).defaultBlockState());
        boolean solid = look.opaque();
        cache.put(id, solid);
        return solid;
    }

    /**
     * The blocks giving light in the columns around the tile beyond its ring, as far out as their light reaches into the tile, see
     * DetailData#edgeLights. Only what is sent of a column counts, as for the cells of the tile: closed caves stay dark.
     */
    private static int[] edgeLights(
        int[] counts, int[] offsets, int[] kept, short[] blocks, short[] heights, byte[] lights, short[] stackTops, int side, int halo, int cellSize
    ) {
        int cells = DetailData.CELLS;
        IntArrayList out = new IntArrayList();
        Int2IntOpenHashMap emissions = new Int2IntOpenHashMap();
        emissions.defaultReturnValue(-1);
        Int2BooleanOpenHashMap solidBlocks = new Int2BooleanOpenHashMap();
        for (int ez = 0; ez < side; ez++) {
            int cz = ez - halo;
            for (int ex = 0; ex < side; ex++) {
                int cx = ex - halo;
                int index = ex + ez * side;
                if (counts[index] == 0 || DetailData.cellIndex(cx, cz) >= 0) {
                    continue;
                }
                // Blocks from the near side of the cell to the tile.
                int dx = cx < 0 ? -cx - 1 : Math.max(0, cx - cells);
                int dz = cz < 0 ? -cz - 1 : Math.max(0, cz - cells);
                int distance = (dx + dz) * cellSize;
                if (distance >= DetailData.EDGE_LIGHT_REACH) {
                    continue;
                }
                int offset = offsets[index];
                int top = stackTops[index];
                int ceiling = DetailData.OPEN_CEILING;
                for (int s = offset; s < offset + kept[index]; s++) {
                    int id = Short.toUnsignedInt(blocks[s]);
                    int emission = emissions.get(id);
                    if (emission < 0) {
                        emission = id == 0 ? 0 : BuiltInRegistries.BLOCK.byId(BlockLook.segmentBlock(id)).defaultBlockState().getLightEmission();
                        emissions.put(id, emission);
                    }
                    int level = Math.min(15, Math.max(emission, lights[s]));
                    if (emission > 0 && level > distance && out.size() < DetailData.MAX_EDGE_LIGHTS * DetailData.EDGE_LIGHT_FIELDS) {
                        out.add(cx);
                        out.add(cz);
                        out.add(top);
                        out.add(ceiling);
                        out.add(level);
                    }
                    if (solid(solidBlocks, blocks[s])) {
                        ceiling = top - heights[s];
                    }
                    top -= heights[s];
                }
            }
        }
        return out.toIntArray();
    }

    /** The cell next to a cell of a square grid in one of four directions, or -1 outside it. */
    private static int neighbour(int index, int direction, int side) {
        int nx = index % side + DIRECTION_X[direction];
        int nz = index / side + DIRECTION_Z[direction];
        return nx < 0 || nz < 0 || nx >= side || nz >= side ? -1 : nx + nz * side;
    }

    /** Blocks of one tile, by name. A tile with more different blocks than fit (tens of thousands) shows the rest as its first block. */
    private static final class BlockPalette {
        /** Key of {@link DetailData#CAVE_PLUG} among the raw block ids, which are never negative. */
        private static final int PLUG_KEY = -2;
        final List<Identifier> ids = new ArrayList<>();
        /** Whether each block stands in water, see BlockLook#segment. */
        final BooleanArrayList wet = new BooleanArrayList();
        private final Int2IntOpenHashMap indices = new Int2IntOpenHashMap();

        BlockPalette() {
            this.indices.defaultReturnValue(-1);
        }

        /** The index of {@link DetailData#CAVE_PLUG}. */
        int plug() {
            int index = this.indices.get(PLUG_KEY);
            if (index >= 0) {
                return index;
            }
            if (this.ids.size() >= DetailData.MAX_BLOCK_PALETTE) {
                return 0;
            }
            index = this.ids.size();
            this.ids.add(DetailData.CAVE_PLUG);
            this.wet.add(false);
            this.indices.put(PLUG_KEY, index);
            return index;
        }

        int index(int rawId) {
            int index = this.indices.get(rawId);
            if (index >= 0) {
                return index;
            }
            if (this.ids.size() >= DetailData.MAX_BLOCK_PALETTE) {
                return 0;
            }
            index = this.ids.size();
            this.ids.add(BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(BlockLook.segmentBlock(rawId))));
            this.wet.add(BlockLook.segmentWet(rawId));
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
        private final ChunkSummary.Scan scan;

        Context(
            ServerLevel level, TilePos pos, Long2ObjectMap<LevelChunk> loaded, @Nullable ChunkSummaryCache saved, @Nullable VirtualChunkStore virtual,
            ChunkSummary.Scan scan
        ) {
            this.level = level;
            this.scan = scan;
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
                long start = ServerPerf.ON ? System.nanoTime() : 0L;
                summary = ChunkSummary.live(chunk, this.scan, this.minY, this.maxY);
                if (ServerPerf.ON) {
                    ServerPerf.DETAIL_LIVE.add(System.nanoTime() - start);
                }
            } else if (this.saved != null) {
                ChunkSummaryCache.Lookup cached = this.saved.get(this.dimension, key, this.scan);
                if (cached != null) {
                    summary = cached.summary();
                } else {
                    long start = ServerPerf.ON ? System.nanoTime() : 0L;
                    summary = this.readSaved(chunkX, chunkZ);
                    if (ServerPerf.ON) {
                        ServerPerf.DETAIL_SAVED.add(System.nanoTime() - start);
                    }
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
                    long chunk = ChunkPos.pack(minChunkX + dx, minChunkZ + dz);
                    if (this.summary(minChunkX + dx, minChunkZ + dz) == null) {
                        missing.add(chunk);
                    } else if (ServerPerf.ON && this.virtual != null && this.virtual.madeInMemory(this.dimension, chunk, this.scan)) {
                        // A real chunk that a virtual one was made for: wasted work, see VirtualChunkStore.
                        ServerPerf.noteVirtualButReal(chunk);
                    }
                }
            }
            if (this.virtual != null && !missing.isEmpty()) {
                try {
                    long start = ServerPerf.ON ? System.nanoTime() : 0L;
                    this.summaries.putAll(this.virtual.chunks(this.level, missing, this.scan));
                    if (ServerPerf.ON) {
                        ServerPerf.DETAIL_VIRTUAL.add(System.nanoTime() - start);
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("Could not make virtual chunks for {}", pos, e);
                }
            }
            // The chunks around the tile, only as far as they exist already: nothing is generated for them.
            int haloChunks = Math.ceilDiv(HALO_BLOCKS, 16);
            LongArrayList around = new LongArrayList();
            for (int dz = -haloChunks; dz < count + haloChunks; dz++) {
                for (int dx = -haloChunks; dx < count + haloChunks; dx++) {
                    if (dx >= 0 && dz >= 0 && dx < count && dz < count) {
                        continue;
                    }
                    if (this.summary(minChunkX + dx, minChunkZ + dz) == null) {
                        around.add(ChunkPos.pack(minChunkX + dx, minChunkZ + dz));
                    }
                }
            }
            if (this.virtual != null && !around.isEmpty()) {
                try {
                    this.summaries.putAll(this.virtual.stored(this.level, around, this.scan));
                } catch (RuntimeException e) {
                    LOGGER.debug("Could not read virtual chunks around {}", pos, e);
                }
            }
        }

        /**
         * Only what the summary needs is decoded: the surface heightmap, the blocks of the sections from the top of the chunk down to
         * how deep columns are scanned, and the biomes where the tops of the columns are. Light, entities, ticks, structures and
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
            int lowestBlocks = SectionPos.blockToSectionCoord(lowestTop - this.scan.depth());
            int highestBlocks = SectionPos.blockToSectionCoord(highestTop);
            int lowestBiomes = SectionPos.blockToSectionCoord(lowestTop - 1);
            PalettedContainerFactory factory = this.level.palettedContainerFactory();
            LevelChunkSection[] sections = new LevelChunkSection[this.level.getSectionsCount()];
            DataLayer[] lights = new DataLayer[sections.length];
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
                // The light of blocks the world worked out for the section, if it has: torches, lava, sea pickles.
                byte[] light = section.getByteArray("BlockLight").orElse(null);
                if (light != null && light.length == DataLayer.SIZE) {
                    lights[y - minSection] = new DataLayer(light);
                }
            }
            // A chunk saved before the world lit it has no light worth using.
            boolean lit = tag.getBooleanOr("isLightOn", false);
            return ChunkSummary.saved(sections, minSection, heights, lit ? lights : null, this.scan, this.minY, this.maxY);
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
