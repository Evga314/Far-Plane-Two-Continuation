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
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.resources.Identifier;

/**
 * Blocks of a detailed tile, built from real chunks: for every cell up to {@link #MAX_RUNS} vertical runs of solid blocks, from the
 * surface down. A run is drawn as a box, which is enough to show trees, buildings and overhangs from a distance. Colors are map
 * color ids. Cells whose chunk has no data yet are marked missing and draw nothing, so the rough terrain shows there instead.
 */
public final class DetailData {
    public static final int CELLS = FarPlaneConstants.DETAIL_CELLS;
    public static final int CELL_COUNT = CELLS * CELLS;
    public static final int MAX_RUNS = 3;
    public static final int MAX_PALETTE = 256;
    public static final int MAX_COMPRESSED_SIZE = 512 * 1024;
    private static final int MAX_RAW_SIZE = 256 * 1024;
    private static final int MAX_ID_LENGTH = 256;

    public final byte[] runCounts;
    public final byte[] biomes;
    /** Indexed by {@code cell * MAX_RUNS + run}, the first run is the highest one. Tops are exclusive. */
    public final short[] tops;
    public final short[] bottoms;
    public final byte[] colors;
    /** Map color id for the sides of each run, taken a few blocks below its top. */
    public final byte[] sides;
    /** One bit per cell, set when its chunk had no data. */
    public final long[] missing;
    public final List<Identifier> palette;

    public DetailData(
        byte[] runCounts, byte[] biomes, short[] tops, short[] bottoms, byte[] colors, byte[] sides, long[] missing, List<Identifier> palette
    ) {
        if (runCounts.length != CELL_COUNT || biomes.length != CELL_COUNT
            || tops.length != CELL_COUNT * MAX_RUNS || bottoms.length != CELL_COUNT * MAX_RUNS || colors.length != CELL_COUNT * MAX_RUNS || sides.length != CELL_COUNT * MAX_RUNS
            || missing.length != CELL_COUNT / 64) {
            throw new IllegalArgumentException("Bad detail array size");
        }
        if (palette.isEmpty() || palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("Bad palette size " + palette.size());
        }
        for (int i = 0; i < CELL_COUNT; i++) {
            if (runCounts[i] < 0 || runCounts[i] > MAX_RUNS || (biomes[i] & 0xFF) >= palette.size()) {
                throw new IllegalArgumentException("Bad detail cell");
            }
        }
        this.runCounts = runCounts;
        this.biomes = biomes;
        this.tops = tops;
        this.bottoms = bottoms;
        this.colors = colors;
        this.sides = sides;
        this.missing = missing;
        this.palette = List.copyOf(palette);
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
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                out.writeShort(this.palette.size());
                for (Identifier id : this.palette) {
                    byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                }
                out.write(this.runCounts);
                out.write(this.biomes);
                for (long word : this.missing) {
                    out.writeLong(word);
                }
                for (int cell = 0; cell < CELL_COUNT; cell++) {
                    for (int run = 0; run < this.runCounts[cell]; run++) {
                        int index = cell * MAX_RUNS + run;
                        out.writeShort(this.tops[index]);
                        out.writeShort(this.bottoms[index]);
                        out.writeByte(this.colors[index]);
                        out.writeByte(this.sides[index]);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Strict decoder for data from the network. Both the compressed and the unpacked size are limited. */
    public static DetailData decode(byte[] compressed) throws IOException {
        if (compressed.length > MAX_COMPRESSED_SIZE) {
            throw new IOException("Detail data too large");
        }
        byte[] raw;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            raw = in.readNBytes(MAX_RAW_SIZE + 1);
        }
        if (raw.length > MAX_RAW_SIZE) {
            throw new IOException("Detail data unpacks too large");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
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
        byte[] runCounts = new byte[CELL_COUNT];
        in.readFully(runCounts);
        byte[] biomes = new byte[CELL_COUNT];
        in.readFully(biomes);
        long[] missing = new long[CELL_COUNT / 64];
        for (int i = 0; i < missing.length; i++) {
            missing[i] = in.readLong();
        }
        short[] tops = new short[CELL_COUNT * MAX_RUNS];
        short[] bottoms = new short[CELL_COUNT * MAX_RUNS];
        byte[] colors = new byte[CELL_COUNT * MAX_RUNS];
        byte[] sides = new byte[CELL_COUNT * MAX_RUNS];
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int runs = runCounts[cell];
            if (runs < 0 || runs > MAX_RUNS) {
                throw new IOException("Bad run count");
            }
            for (int run = 0; run < runs; run++) {
                int index = cell * MAX_RUNS + run;
                tops[index] = in.readShort();
                bottoms[index] = in.readShort();
                colors[index] = in.readByte();
                sides[index] = in.readByte();
            }
        }
        if (in.available() != 0) {
            throw new IOException("Trailing detail data");
        }
        try {
            return new DetailData(runCounts, biomes, tops, bottoms, colors, sides, missing, palette);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }
}
