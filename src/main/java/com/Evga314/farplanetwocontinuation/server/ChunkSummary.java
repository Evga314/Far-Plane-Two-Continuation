package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.tile.BlockLook;
import com.Evga314.farplanetwocontinuation.tile.BlockLook.Look;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * What a detailed tile needs from one chunk: for each of its 256 columns the segments of blocks from the top of the column down (a
 * block and how many of it are stacked), how high the top block is and the biome at the top. Only {@link Scan#depth} blocks are
 * looked at; below that the last segment goes on to the bottom of the world, or air does over the void of the End. Chunks read from
 * disk are summarized once and then shared by every tile and level that touches them. Live chunks are summarized column by column,
 * only where a tile actually looks.
 * <p>
 * Blocks are kept as raw registry ids, which only live as long as the server runs; on disk they are written by name. Raw id 0 is air,
 * which also stands for blocks not drawn at all.
 */
public final class ChunkSummary {
    /** Most segments one column may hold. */
    static final int SEGMENT_CAP = DetailData.MAX_SEGMENTS;

    private static final byte NOT_SCANNED = -1;

    /**
     * How a column is scanned: how many blocks down from its top, and whether the void is under the scanned part (the End), so it
     * goes on as air instead of as the last block. Summaries made with another scan are never mixed in.
     */
    /** {@code oreVeins}: chunks generated in memory have the world's ore veins, see VirtualTerrain#surfaceRule. */
    record Scan(int depth, boolean voidBelow, boolean oreVeins) {
    }

    private final Scan scan;

    /** How many segments each column has; NOT_SCANNED until the column is scanned. */
    private final byte[] segCounts = new byte[256];

    /** Where each column's segment list starts in the flat segment arrays. */
    private final short[] segOffsets = new short[256];

    /** Height of the top block of a column in sixteenths: 16 for full blocks, less for slabs, snow layers and carpets. */
    private final byte[] topFraction = new byte[256];

    /** Flat segment storage for the whole chunk: raw block ids, appended by the scan, grows by doubling. */
    private short[] segBlocks = new short[512];

    /** Segment heights in blocks, parallel to segBlocks. */
    private short[] segHeights = new short[512];

    /**
     * Light of blocks (torches, lava, sea pickles) at the top of every segment, 0 to 15, parallel to segBlocks: the higher of the light
     * in its top block and in the block over it. Chunks the world has not lit (virtual ones) have the light their top block gives
     * itself, as it really is (four sea pickles in a cluster, glow lichen on a wall), for the client to work out the light around.
     */
    private byte[] segLights = new byte[512];

    /** Whether the world has lit the chunk, so {@link #segLights} is its light; virtual chunks never are. */
    private boolean lit;

    /** How many segments are stored so far. */
    private int segTotal;

    @SuppressWarnings("unchecked")
    private final @Nullable Holder<Biome>[] biomes = new Holder[256];
    private final int minY;
    private final int maxY;
    /** Block access while columns are still being scanned; dropped once a saved chunk is fully summarized. */
    private @Nullable Source source;

    private ChunkSummary(Source source, Scan scan, int minY, int maxY) {
        this.source = source;
        this.scan = scan;
        this.minY = minY;
        this.maxY = maxY;
        Arrays.fill(this.segCounts, NOT_SCANNED);
    }

    /** Decoded from disk: every column is known and there is nothing left to scan. */
    private ChunkSummary(Scan scan, int minY, int maxY) {
        this.scan = scan;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** A live chunk, read lazily by one tile; never shared, because the world keeps changing it. */
    static ChunkSummary live(LevelChunk chunk, Scan scan, int minY, int maxY) {
        DataLayer[] lights = new DataLayer[chunk.getSections().length];
        var engine = chunk.getLevel().getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int i = 0; i < lights.length; i++) {
            lights[i] = engine.getDataLayerData(SectionPos.of(chunk.getPos(), chunk.getMinSectionY() + i));
        }
        ChunkSummary summary = new ChunkSummary(new Source(chunk.getSections(), chunk.getMinSectionY(), null, chunk, minY, lights), scan, minY, maxY);
        // A chunk is lit before it is loaded into the world.
        summary.lit = true;
        return summary;
    }

    /**
     * A chunk parsed from its region file, summarized at once so the parsed sections can be dropped. {@code lights}: the light of
     * blocks per section, null for a chunk the world has not lit.
     */
    static ChunkSummary saved(
        LevelChunkSection[] sections, int minSectionY, @Nullable SimpleBitStorage heights, @Nullable DataLayer @Nullable [] lights, Scan scan, int minY, int maxY
    ) {
        ChunkSummary summary = new ChunkSummary(new Source(sections, minSectionY, heights, null, minY, lights), scan, minY, maxY);
        summary.lit = lights != null;
        for (int i = 0; i < 256; i++) {
            summary.scan(i);
        }
        summary.source = null;
        return summary;
    }

    Scan scan() {
        return this.scan;
    }

    /** Whether the world has lit the chunk, see {@link #segLights}. */
    boolean lit() {
        return this.lit;
    }

    /** Every biome the summary refers to, for building the palette of a file. */
    void collectBiomes(Consumer<Holder<Biome>> output) {
        for (Holder<Biome> biome : this.biomes) {
            if (biome != null) {
                output.accept(biome);
            }
        }
    }

    /** Every block the summary refers to, as segments (raw ids, maybe marked wet, see BlockLook#segment), for building the palette of a file. */
    void collectBlocks(IntConsumer output) {
        for (int column = 0; column < 256; column++) {
            this.ensureCount(column);
        }
        for (int i = 0; i < this.segTotal; i++) {
            output.accept(Short.toUnsignedInt(this.segBlocks[i]));
        }
    }

    /** Writes a fully scanned summary; biomes and blocks are written as indices into the palettes of the file. */
    void write(DataOutputStream out, Object2IntMap<Holder<Biome>> biomePalette, Int2IntMap blockPalette) throws IOException {
        for (int column = 0; column < 256; column++) {
            this.ensureCount(column);
        }
        out.writeInt(this.segTotal);
        for (int column = 0; column < 256; column++) {
            out.writeByte(Math.max(0, this.segCounts[column]));
            out.writeShort(this.segOffsets[column]);
            out.writeByte(this.topFraction[column]);
            Holder<Biome> biome = this.biomes[column];
            out.writeShort(biome == null ? -1 : biomePalette.getOrDefault(biome, -1));
        }
        for (int i = 0; i < this.segTotal; i++) {
            out.writeShort(blockPalette.get(Short.toUnsignedInt(this.segBlocks[i])));
            out.writeShort(this.segHeights[i]);
            out.writeByte(this.segLights[i]);
        }
    }

    /** Reads what {@link #write} wrote, with blocks as raw ids of this server. Values are checked, since the file may be damaged. */
    static ChunkSummary read(DataInputStream in, List<Holder<Biome>> biomePalette, int[] blockPalette, Scan scan, int minY, int maxY) throws IOException {
        ChunkSummary summary = new ChunkSummary(scan, minY, maxY);
        int total = in.readInt();
        if (total < 0 || total > 256 * SEGMENT_CAP) {
            throw new IOException("Bad segment total " + total);
        }
        summary.segBlocks = new short[Math.max(total, 1)];
        summary.segHeights = new short[Math.max(total, 1)];
        summary.segLights = new byte[Math.max(total, 1)];
        int sum = 0;
        for (int column = 0; column < 256; column++) {
            int count = in.readUnsignedByte();
            if (count > SEGMENT_CAP) {
                throw new IOException("Bad segment count " + count);
            }
            summary.segCounts[column] = (byte) count;
            sum += count;
            int offset = in.readUnsignedShort();
            if (offset > total) {
                throw new IOException("Bad segment offset " + offset);
            }
            summary.segOffsets[column] = (short) offset;
            int fraction = in.readByte();
            if (fraction < 1 || fraction > 16) {
                throw new IOException("Bad top fraction " + fraction);
            }
            summary.topFraction[column] = (byte) fraction;
            int biome = in.readShort();
            if (biome >= biomePalette.size()) {
                throw new IOException("Bad biome index " + biome);
            }
            summary.biomes[column] = biome < 0 ? null : biomePalette.get(biome);
        }
        if (sum != total) {
            throw new IOException("Segment counts " + sum + " do not add up to total " + total);
        }
        for (int i = 0; i < total; i++) {
            summary.segBlocks[i] = (short) block(blockPalette, in.readUnsignedShort());
            int height = in.readUnsignedShort();
            if (height < 1) {
                throw new IOException("Bad segment height " + height);
            }
            summary.segHeights[i] = (short) height;
            int light = in.readUnsignedByte();
            if (light > 15) {
                throw new IOException("Bad segment light " + light);
            }
            summary.segLights[i] = (byte) light;
        }
        summary.segTotal = total;
        return summary;
    }

    private static int block(int[] palette, int index) throws IOException {
        if (index >= palette.length) {
            throw new IOException("Bad block index " + index);
        }
        return palette[index];
    }

    private int ensureCount(int column) {
        if (this.segCounts[column] == NOT_SCANNED) {
            this.scan(column);
        }
        return this.segCounts[column];
    }

    /** Exclusive top of the highest visible block, or the bottom of the world for an empty column. */
    int top(int x, int z) {
        int column = this.ensure(x, z);
        int count = this.segCounts[column];
        int offset = this.segOffsets[column];
        int top = this.minY;
        for (int i = 0; i < count; i++) {
            top += this.segHeights[offset + i];
        }
        for (int i = 0; i < count; i++) {
            int segment = Short.toUnsignedInt(this.segBlocks[offset + i]);
            if (segment == 0) {
                top -= this.segHeights[offset + i];
                continue;
            }
            // A plant in water stands for the water it is in.
            Look look = BlockLook.look(BuiltInRegistries.BLOCK.byId(BlockLook.segmentBlock(segment)).defaultBlockState());
            if (look.plant() != 0 && !BlockLook.segmentWet(segment)) {
                top -= this.segHeights[offset + i];
                continue;
            }
            return top;
        }
        return this.minY;
    }

    /** How many segments a column has. */
    int segmentCount(int x, int z) {
        return this.segCounts[this.ensure(x, z)];
    }

    /**
     * Copies the segments of a column, highest first, into the caller's arrays from {@code base} on: raw block ids, heights and the
     * light of blocks at their tops.
     */
    int segments(int x, int z, short[] blocks, short[] heights, byte[] lights, int base) {
        int column = this.ensure(x, z);
        int count = this.segCounts[column];
        int offset = this.segOffsets[column];
        System.arraycopy(this.segBlocks, offset, blocks, base, count);
        System.arraycopy(this.segHeights, offset, heights, base, count);
        System.arraycopy(this.segLights, offset, lights, base, count);
        return count;
    }

    /** Exclusive top of the whole segment stack of a column, air and plants included. */
    int stackTop(int x, int z) {
        int column = this.ensure(x, z);
        int count = this.segCounts[column];
        int offset = this.segOffsets[column];
        int top = this.minY;
        for (int i = 0; i < count; i++) {
            top += this.segHeights[offset + i];
        }
        return top;
    }

    /** Height of the top block of a column in sixteenths. */
    int topFraction(int x, int z) {
        return this.topFraction[this.ensure(x, z)];
    }

    @Nullable Holder<Biome> biome(int x, int z) {
        return this.biomes[this.ensure(x, z)];
    }

    private int ensure(int x, int z) {
        int column = x + z * 16;
        if (this.segCounts[column] == NOT_SCANNED) {
            this.scan(column);
        }
        return column;
    }

    private void scan(int column) {
        Source source = this.source;
        if (source == null) {
            this.segCounts[column] = 0;
            this.topFraction[column] = 16;
            return;
        }
        long started = ServerPerf.ON ? System.nanoTime() : 0L;
        int x = column & 15;
        int z = column >> 4;
        int start = source.top(x, z);
        int limit = Math.max(this.minY, start - this.scan.depth());
        int topY = Math.min(start, this.maxY + 1) - 1;
        boolean voidBelow = this.scan.voidBelow();
        // Over the void one segment is kept free for the air under the scanned part.
        int cap = voidBelow ? SEGMENT_CAP - 1 : SEGMENT_CAP;
        int y = topY;

        this.segOffsets[column] = (short) this.segTotal;
        this.topFraction[column] = 16;
        boolean topFound = false;
        int surfaceY = topY;
        int segments = 0;
        int openBlock = -1;   // block of the segment being grown; -1 = no segment open yet
        int openHeight = 0;
        int openLight = 0;
        boolean capped = false;
        while (y >= limit) {
            BlockState state = source.state(x, y, z);
            Look look = BlockLook.look(state);
            int block = BlockLook.segment(look);
            if (!topFound && look.block() != -1) {
                this.topFraction[column] = (byte) look.height();
                surfaceY = y;
                topFound = true;
            }
            if (block == openBlock) {
                openHeight++;
            } else {
                if (openBlock != -1) {
                    this.addSegment(openBlock, openHeight, openLight);
                    segments++;
                    if (segments == cap) {
                        // Budget spent: this block and everything below are left to what goes on under the column.
                        capped = true;
                        break;
                    }
                }
                openBlock = block;
                openHeight = 1;
                openLight = source.lights() != null
                    ? Math.max(source.blockLight(x, y, z), source.blockLight(x, y + 1, z))
                    : state.getLightEmission();
            }
            y--;
        }
        if (!capped && openBlock != -1) {
            this.addSegment(openBlock, openHeight, openLight);
            segments++;
        }
        // Blocks under the scanned part, down to the bottom of the world.
        int rest = capped ? y - this.minY + 1 : limit - this.minY;
        if (segments == 0) {
            // An empty column: air down to the bottom of the world, at least one block of it so the column has a height.
            this.addSegment(0, Math.max(1, topY + 1 - this.minY), 0);
            segments = 1;
        } else if (rest > 0) {
            if (voidBelow && this.segBlocks[this.segTotal - 1] != 0) {
                this.addSegment(0, rest, 0);
                segments++;
            } else {
                this.segHeights[this.segTotal - 1] += (short) rest;
            }
        }
        this.segCounts[column] = (byte) segments;
        this.biomes[column] = source.biome(x, surfaceY, z);
        if (ServerPerf.ON) {
            ServerPerf.SCAN_NANOS.addAndGet(System.nanoTime() - started);
            ServerPerf.SCAN_COLUMNS.incrementAndGet();
        }
    }

    /** Appends one segment to the flat arrays, growing them by doubling when full. */
    private void addSegment(int block, int height, int light) {
        if (this.segTotal == this.segBlocks.length) {
            this.segBlocks = java.util.Arrays.copyOf(this.segBlocks, this.segBlocks.length * 2);
            this.segHeights = java.util.Arrays.copyOf(this.segHeights, this.segHeights.length * 2);
            this.segLights = java.util.Arrays.copyOf(this.segLights, this.segLights.length * 2);
        }
        this.segBlocks[this.segTotal] = (short) block;
        this.segHeights[this.segTotal] = (short) height;
        this.segLights[this.segTotal] = (byte) light;
        this.segTotal++;
    }

    /** Read-only access to the sections of one chunk, either a live chunk or one parsed from disk. */
    private record Source(
        LevelChunkSection[] sections, int minSectionY, @Nullable SimpleBitStorage heights, @Nullable LevelChunk chunk, int minY,
        @Nullable DataLayer @Nullable [] lights
    ) {
        /** The light of blocks at a place, 0 where the chunk has none. */
        int blockLight(int x, int y, int z) {
            int index = (y >> 4) - this.minSectionY;
            if (this.lights == null || index < 0 || index >= this.lights.length) {
                return 0;
            }
            DataLayer layer = this.lights[index];
            return layer == null ? 0 : layer.get(x, y & 15, z);
        }

        int top(int x, int z) {
            if (this.chunk != null) {
                try {
                    return this.chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
                } catch (RuntimeException e) {
                    // The server may be changing this chunk at the same moment; a wrong height for one column is harmless.
                    return this.minY;
                }
            }
            if (this.heights != null) {
                return this.heights.get(x + z * 16) + this.minY;
            }
            for (int i = this.sections.length - 1; i >= 0; i--) {
                LevelChunkSection section = this.sections[i];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                for (int y = 15; y >= 0; y--) {
                    if (!section.getBlockState(x, y, z).isAir()) {
                        return (i + this.minSectionY) * 16 + y + 1;
                    }
                }
            }
            return this.minY;
        }

        BlockState state(int x, int y, int z) {
            LevelChunkSection section = this.section(y);
            if (section == null) {
                return Blocks.AIR.defaultBlockState();
            }
            try {
                return section.getBlockState(x, y & 15, z);
            } catch (RuntimeException e) {
                // Live chunks are read while the server may change them; a palette being resized can throw for a moment.
                return Blocks.AIR.defaultBlockState();
            }
        }

        @Nullable Holder<Biome> biome(int x, int y, int z) {
            LevelChunkSection section = this.section(y);
            if (section == null) {
                return null;
            }
            try {
                return section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private @Nullable LevelChunkSection section(int y) {
            int index = (y >> 4) - this.minSectionY;
            return index >= 0 && index < this.sections.length ? this.sections[index] : null;
        }
    }
}
