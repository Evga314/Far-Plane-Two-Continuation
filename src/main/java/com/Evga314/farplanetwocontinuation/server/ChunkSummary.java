package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.tile.DetailData;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * What a detailed tile needs from one chunk: for each of its 256 columns up to {@link DetailData#MAX_RUNS} runs of visible blocks
 * and the biome at the top. About 6 KB instead of the whole chunk, so chunks read from disk are summarized once and then shared by
 * every tile and level that touches them. Live chunks are summarized column by column, only where a tile actually looks.
 */
public final class ChunkSummary {
    /** How far below the surface runs are looked for. Deeper caves are never visible from far away. */
    private static final int SCAN_DEPTH = 96;
    private static final byte NOT_SCANNED = -1;
    private static final int RUNS = DetailData.MAX_RUNS;
    /**
     * Depth below the top of a run where the color of its sides is taken. Cliffs are grass on top and stone below, so walls get the
     * color found a few blocks down; runs too short for that keep the top color.
     */
    private static final int SIDE_DEPTH = 5;

    private final byte[] counts = new byte[256];
    private final short[] tops = new short[256 * RUNS];
    private final short[] bottoms = new short[256 * RUNS];
    private final byte[] colors = new byte[256 * RUNS];
    private final byte[] sides = new byte[256 * RUNS];
    @SuppressWarnings("unchecked")
    private final @Nullable Holder<Biome>[] biomes = new Holder[256];
    private final int minY;
    private final int maxY;
    /** Block access while columns are still being scanned; dropped once a saved chunk is fully summarized. */
    private @Nullable Source source;

    private ChunkSummary(Source source, int minY, int maxY) {
        this.source = source;
        this.minY = minY;
        this.maxY = maxY;
        Arrays.fill(this.counts, NOT_SCANNED);
    }

    /** Decoded from disk: every column is known and there is nothing left to scan. */
    private ChunkSummary(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    /** A live chunk, read lazily by one tile; never shared, because the world keeps changing it. */
    static ChunkSummary live(LevelChunk chunk, int minY, int maxY) {
        return new ChunkSummary(new Source(chunk.getSections(), chunk.getMinSectionY(), null, chunk, minY), minY, maxY);
    }

    /** A chunk parsed from its region file, summarized at once so the parsed sections can be dropped. */
    public static ChunkSummary saved(LevelChunkSection[] sections, int minSectionY, @Nullable SimpleBitStorage heights, int minY, int maxY) {
        ChunkSummary summary = new ChunkSummary(new Source(sections, minSectionY, heights, null, minY), minY, maxY);
        for (int i = 0; i < 256; i++) {
            summary.scan(i);
        }
        summary.source = null;
        return summary;
    }

    /** Every biome the summary refers to, for building the palette of a file. */
    void collectBiomes(Consumer<Holder<Biome>> output) {
        for (Holder<Biome> biome : this.biomes) {
            if (biome != null) {
                output.accept(biome);
            }
        }
    }

    /** Writes a fully scanned summary; biomes are written as indices into the palette of the file. */
    void write(DataOutputStream out, Object2IntMap<Holder<Biome>> palette) throws IOException {
        for (int column = 0; column < 256; column++) {
            int count = Math.max(0, this.ensureCount(column));
            out.writeByte(count);
            for (int run = 0; run < count; run++) {
                out.writeShort(this.tops[column * RUNS + run]);
                out.writeShort(this.bottoms[column * RUNS + run]);
                out.writeByte(this.colors[column * RUNS + run]);
                out.writeByte(this.sides[column * RUNS + run]);
            }
            Holder<Biome> biome = this.biomes[column];
            out.writeShort(biome == null ? -1 : palette.getOrDefault(biome, -1));
        }
    }

    /** Reads what {@link #write} wrote. Values are checked, since the file may be damaged. */
    static ChunkSummary read(DataInputStream in, List<Holder<Biome>> palette, int minY, int maxY) throws IOException {
        ChunkSummary summary = new ChunkSummary(minY, maxY);
        for (int column = 0; column < 256; column++) {
            int count = in.readUnsignedByte();
            if (count > RUNS) {
                throw new IOException("Bad run count " + count);
            }
            summary.counts[column] = (byte) count;
            for (int run = 0; run < count; run++) {
                summary.tops[column * RUNS + run] = in.readShort();
                summary.bottoms[column * RUNS + run] = in.readShort();
                summary.colors[column * RUNS + run] = in.readByte();
                summary.sides[column * RUNS + run] = in.readByte();
            }
            int biome = in.readShort();
            if (biome >= palette.size()) {
                throw new IOException("Bad biome index " + biome);
            }
            summary.biomes[column] = biome < 0 ? null : palette.get(biome);
        }
        return summary;
    }

    private int ensureCount(int column) {
        if (this.counts[column] == NOT_SCANNED) {
            this.scan(column);
        }
        return this.counts[column];
    }

    /** Exclusive top of the highest visible block, or the bottom of the world for an empty column. */
    int top(int x, int z) {
        int column = this.ensure(x, z);
        return this.counts[column] > 0 ? this.tops[column * RUNS] : this.minY;
    }

    /** Copies the runs of a column, highest first. Returns how many there are. */
    int runs(int x, int z, short[] tops, short[] bottoms, byte @Nullable [] colors, byte @Nullable [] sides, int base) {
        int column = this.ensure(x, z);
        int count = this.counts[column];
        System.arraycopy(this.tops, column * RUNS, tops, base, count);
        System.arraycopy(this.bottoms, column * RUNS, bottoms, base, count);
        if (colors != null) {
            System.arraycopy(this.colors, column * RUNS, colors, base, count);
        }
        if (sides != null) {
            System.arraycopy(this.sides, column * RUNS, sides, base, count);
        }
        return count;
    }

    @Nullable Holder<Biome> biome(int x, int z) {
        return this.biomes[this.ensure(x, z)];
    }

    private int ensure(int x, int z) {
        int column = x + z * 16;
        if (this.counts[column] == NOT_SCANNED) {
            this.scan(column);
        }
        return column;
    }

    private void scan(int column) {
        Source source = this.source;
        if (source == null) {
            this.counts[column] = 0;
            return;
        }
        int x = column & 15;
        int z = column >> 4;
        int start = source.top(x, z);
        int limit = Math.max(this.minY, start - SCAN_DEPTH);
        int y = Math.min(start, this.maxY + 1) - 1;
        int base = column * RUNS;
        int runs = 0;
        while (runs < RUNS && y >= limit) {
            while (y >= limit && mapColor(source.state(x, y, z)) == 0) {
                y--;
            }
            if (y < limit) {
                break;
            }
            int runTop = y + 1;
            int color = mapColor(source.state(x, y, z));
            int side = color;
            // Only a few runs are kept. The last one reaches all the way down, so whatever lies below it (more leaves, an overhang,
            // the ground) still counts as solid; otherwise there would be a hole under it, open to the sky from the side.
            boolean last = runs == RUNS - 1;
            while (y >= limit) {
                int below = mapColor(source.state(x, y, z));
                if (below == 0 && !last) {
                    break;
                }
                if (runTop - 1 - y == SIDE_DEPTH && below != 0) {
                    side = below;
                }
                y--;
            }
            this.tops[base + runs] = (short) runTop;
            this.bottoms[base + runs] = (short) (y + 1);
            this.colors[base + runs] = (byte) color;
            this.sides[base + runs] = (byte) side;
            runs++;
        }
        this.counts[column] = (byte) runs;
        this.biomes[column] = source.biome(x, (runs > 0 ? this.tops[base] : start) - 1, z);
    }

    /** Map color id of a block, 0 for blocks that are invisible on maps (air, glass, light and similar). */
    private static int mapColor(BlockState state) {
        return state.isAir() ? 0 : state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id;
    }

    /** Read-only access to the sections of one chunk, either a live chunk or one parsed from disk. */
    private record Source(LevelChunkSection[] sections, int minSectionY, @Nullable SimpleBitStorage heights, @Nullable LevelChunk chunk, int minY) {
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
