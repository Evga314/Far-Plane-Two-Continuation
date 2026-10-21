package com.Evga314.farplanetwocontinuation.tile;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Blocks of a detailed tile, built from real chunks: for every cell up to {@link #MAX_RUNS} vertical runs of solid blocks, from the
 * surface down, and the plant standing on the highest one. A run is drawn as a box, which is enough to show trees, buildings and
 * overhangs from a distance. Blocks are indices into the block palette of the tile, so the client can draw them with their own
 * colors and textures. Cells whose chunk has no data yet are marked missing and draw nothing, so the rough terrain shows there.
 */
public final class DetailData {
    public static final int CELLS = FarPlaneConstants.DETAIL_CELLS;
    public static final int CELL_COUNT = CELLS * CELLS;
    public static final int MAX_RUNS = 3;
    public static final int MAX_PALETTE = 256;
    /** Plants are stored as palette index + 1, so they can only use the first 255 entries. */
    public static final int NO_PLANT = 0;
    public static final int MAX_COMPRESSED_SIZE = 512 * 1024;
    private static final int MAX_RAW_SIZE = 384 * 1024;
    private static final int MAX_ID_LENGTH = 256;

    public final byte[] runCounts;
    public final byte[] biomes;
    /** Indexed by {@code cell * MAX_RUNS + run}, the first run is the highest one. Tops are exclusive. */
    public final short[] tops;
    public final short[] bottoms;
    /** Block on top of each run, as an index into {@link #blockPalette}. */
    public final byte[] blocks;
    /** Block of the sides of each run, taken a few blocks below its top. */
    public final byte[] sides;
    /** Height of the top block of each run in sixteenths of a block, 1 to 16: slabs, snow layers and carpets are lower. */
    public final byte[] heights;
    /** Plant standing on the highest run of each cell: palette index + 1, or {@link #NO_PLANT}. */
    public final byte[] plants;
    /** One bit per cell, set when its chunk had no data. */
    public final long[] missing;
    public final List<Identifier> palette;
    public final List<Identifier> blockPalette;
    /** {@link #blockPalette} as an array, made once when first needed (by the mesh builder) instead of for every build. */
    private Identifier @Nullable [] blockIds;

    public DetailData(
        byte[] runCounts, byte[] biomes, short[] tops, short[] bottoms, byte[] blocks, byte[] sides, byte[] heights, byte[] plants, long[] missing,
        List<Identifier> palette, List<Identifier> blockPalette
    ) {
        int runSlots = CELL_COUNT * MAX_RUNS;
        if (runCounts.length != CELL_COUNT || biomes.length != CELL_COUNT || plants.length != CELL_COUNT
            || tops.length != runSlots || bottoms.length != runSlots || blocks.length != runSlots || sides.length != runSlots || heights.length != runSlots
            || missing.length != CELL_COUNT / 64) {
            throw new IllegalArgumentException("Bad detail array size");
        }
        if (palette.isEmpty() || palette.size() > MAX_PALETTE || blockPalette.isEmpty() || blockPalette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("Bad palette size");
        }
        for (int i = 0; i < CELL_COUNT; i++) {
            if (runCounts[i] < 0 || runCounts[i] > MAX_RUNS || (biomes[i] & 0xFF) >= palette.size() || (plants[i] & 0xFF) > blockPalette.size()) {
                throw new IllegalArgumentException("Bad detail cell");
            }
            for (int run = 0; run < runCounts[i]; run++) {
                int slot = i * MAX_RUNS + run;
                if ((blocks[slot] & 0xFF) >= blockPalette.size() || (sides[slot] & 0xFF) >= blockPalette.size() || heights[slot] < 1 || heights[slot] > 16) {
                    throw new IllegalArgumentException("Bad detail run");
                }
            }
        }
        this.runCounts = runCounts;
        this.biomes = biomes;
        this.tops = tops;
        this.bottoms = bottoms;
        this.blocks = blocks;
        this.sides = sides;
        this.heights = heights;
        this.plants = plants;
        this.missing = missing;
        this.palette = List.copyOf(palette);
        this.blockPalette = List.copyOf(blockPalette);
    }

    /** The block palette as an array; the same array every time, made on first use. Read-only for callers. */
    public Identifier[] blockIds() {
        Identifier[] ids = this.blockIds;
        if (ids == null) {
            ids = this.blockPalette.toArray(Identifier[]::new);
            this.blockIds = ids;
        }
        return ids;
    }

    public boolean isMissing(int cell) {
        return (this.missing[cell >> 6] & 1L << (cell & 63)) != 0;
    }

    public boolean isComplete() {
        for (long word : this.missing) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
            try (DataOutputStream out = new DataOutputStream(Zip.compress(bytes))) {
                writePalette(out, this.palette);
                writePalette(out, this.blockPalette);
                out.write(this.runCounts);
                out.write(this.biomes);
                out.write(this.plants);
                for (long word : this.missing) {
                    out.writeLong(word);
                }
                for (int cell = 0; cell < CELL_COUNT; cell++) {
                    for (int run = 0; run < this.runCounts[cell]; run++) {
                        int index = cell * MAX_RUNS + run;
                        out.writeShort(this.tops[index]);
                        out.writeShort(this.bottoms[index]);
                        out.writeByte(this.blocks[index]);
                        out.writeByte(this.sides[index]);
                        out.writeByte(this.heights[index]);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writePalette(DataOutputStream out, List<Identifier> palette) throws IOException {
        out.writeShort(palette.size());
        for (Identifier id : palette) {
            byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
            out.writeShort(text.length);
            out.write(text);
        }
    }

    private static List<Identifier> readPalette(DataInputStream in) throws IOException {
        int paletteSize = in.readUnsignedShort();
        if (paletteSize == 0 || paletteSize > MAX_PALETTE) {
            throw new IOException("Bad palette size " + paletteSize);
        }
        List<Identifier> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            int length = in.readUnsignedShort();
            if (length == 0 || length > MAX_ID_LENGTH) {
                throw new IOException("Bad identifier length");
            }
            byte[] text = new byte[length];
            in.readFully(text);
            Identifier id = Identifier.tryParse(new String(text, StandardCharsets.UTF_8));
            if (id == null) {
                throw new IOException("Bad identifier");
            }
            palette.add(id);
        }
        return palette;
    }

    /** Strict decoder for data from the network. Both the compressed and the unpacked size are limited. */
    public static DetailData decode(byte[] compressed) throws IOException {
        if (compressed.length > MAX_COMPRESSED_SIZE) {
            throw new IOException("Detail data too large");
        }
        byte[] raw;
        try (InputStream in = Zip.decompress(new ByteArrayInputStream(compressed))) {
            raw = in.readNBytes(MAX_RAW_SIZE + 1);
        }
        if (raw.length > MAX_RAW_SIZE) {
            throw new IOException("Detail data unpacks too large");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        List<Identifier> palette = readPalette(in);
        List<Identifier> blockPalette = readPalette(in);
        byte[] runCounts = new byte[CELL_COUNT];
        in.readFully(runCounts);
        byte[] biomes = new byte[CELL_COUNT];
        in.readFully(biomes);
        byte[] plants = new byte[CELL_COUNT];
        in.readFully(plants);
        long[] missing = new long[CELL_COUNT / 64];
        for (int i = 0; i < missing.length; i++) {
            missing[i] = in.readLong();
        }
        int runSlots = CELL_COUNT * MAX_RUNS;
        short[] tops = new short[runSlots];
        short[] bottoms = new short[runSlots];
        byte[] blocks = new byte[runSlots];
        byte[] sides = new byte[runSlots];
        byte[] heights = new byte[runSlots];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int runs = runCounts[cell];
            if (runs < 0 || runs > MAX_RUNS) {
                throw new IOException("Bad run count");
            }
            for (int run = 0; run < runs; run++) {
                int index = cell * MAX_RUNS + run;
                tops[index] = in.readShort();
                bottoms[index] = in.readShort();
                blocks[index] = in.readByte();
                sides[index] = in.readByte();
                heights[index] = in.readByte();
            }
        }
        if (in.available() != 0) {
            throw new IOException("Trailing detail data");
        }
        try {
            return new DetailData(runCounts, biomes, tops, bottoms, blocks, sides, heights, plants, missing, palette, blockPalette);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }
}
