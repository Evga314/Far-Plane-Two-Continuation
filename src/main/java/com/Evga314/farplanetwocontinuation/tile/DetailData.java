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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Blocks of a detailed tile, built from real chunks: for every cell the segments of its column, a stack of blocks with their heights
 * from the top of the column down, and the biome at the top. The server leaves out the segments nobody can see from outside; the last
 * one sent goes on to the bottom of the world. Blocks are indices into the block palette of the tile, so the client can draw them with
 * their own colors and textures. Cells whose chunk has no data yet are marked missing and draw nothing, so the rough terrain shows
 * there.
 * <p>
 * Around the tile comes a ring one cell wide: the columns right outside its four edges, so the sides of the cells at the edge can be
 * drawn as exactly as those inside. Ring cells come after the cells of the tile, see {@link #cellIndex}; a ring cell without segments is
 * not known (its chunk had no data yet).
 */
public final class DetailData {
    public static final int CELLS = FarPlaneConstants.DETAIL_CELLS;
    public static final int CELL_COUNT = CELLS * CELLS;
    /** Cells of the ring around the tile: a row along each of the four edges, without the corners. */
    public static final int RING_CELLS = 4 * CELLS;
    /** Cells of the tile and of the ring around it. */
    public static final int TOTAL_CELLS = CELL_COUNT + RING_CELLS;
    /** Most segments one cell may carry, the same as one column of a chunk summary. */
    public static final int MAX_SEGMENTS = 64;
    /** Biomes, one byte per cell. */
    public static final int MAX_PALETTE = 256;
    /** Blocks take one byte per segment while a tile has at most 256 different ones, two bytes beyond that. */
    public static final int MAX_BLOCK_PALETTE = 65535;
    /**
     * Blocks under the top of a column that are always sent, even where no neighbour lies lower: the walls of the cells at the edge of
     * a tile, which go down further, show the layers under the top (grass, dirt, stone) instead of one block stretched down.
     */
    public static final int SIDE_DEPTH = 5;
    /** Fields of one source in {@link #edgeLights}. */
    public static final int EDGE_LIGHT_FIELDS = 5;
    /** Most sources in {@link #edgeLights}: a sea of lava (the Nether's) along all four edges. */
    public static final int MAX_EDGE_LIGHTS = 16384;
    /** How far out of the tile, in blocks, sources are sent in {@link #edgeLights}: as far as light goes. */
    public static final int EDGE_LIGHT_REACH = 15;
    /** {@link #edgeLights} ceiling of a source with nothing over it that stops light. */
    public static final int OPEN_CEILING = Short.MAX_VALUE;
    /** Kept below the vanilla limit for one packet, with room for the rest of it. */
    public static final int MAX_COMPRESSED_SIZE = 512 * 1024;
    private static final int MAX_RAW_SIZE = 1024 * 1024;
    private static final int MAX_ID_LENGTH = 256;
    /** Flags at the start of the data: block indices and segment heights that do not fit a byte take two. */
    private static final int WIDE_BLOCKS = 1;
    private static final int WIDE_HEIGHTS = 2;
    private static final Identifier AIR = BuiltInRegistries.BLOCK.getKey(Blocks.AIR);
    /**
     * Not a block: the last segment of a column that the server cut off under a cave it follows no further. It stands for the ground
     * there down to the bottom of the world, caves included, and is drawn dark, as the inside of a cave without light. Clients that do
     * not know it draw stone.
     */
    public static final Identifier CAVE_PLUG = Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "cave_plug");

    /** Bottom of the world: the last segment of every cell goes down to it. */
    public final int minY;
    /** Segments per cell of the tile and of the ring, 0 for missing cells, otherwise 1 to {@link #MAX_SEGMENTS}. */
    public final byte[] segCounts;
    /** Exclusive top of the segment stack of each cell and ring cell, air and plants included. */
    public final short[] stackTops;
    /** Height of the top block of each cell and ring cell in sixteenths of a block, 1 to 16: slabs, snow layers and carpets are lower. */
    public final byte[] fractions;
    /** Biome of each cell of the tile; ring cells have none. */
    public final byte[] biomes;
    /** Where the segments of each cell start; worked out from the counts, never sent. */
    public final int[] segOffsets;
    /** Block of every segment, as an index into {@link #blockPalette}. */
    public final short[] segBlocks;
    /** Height of every segment in blocks, at least 1. The heights of a cell add up to its stack top minus {@link #minY}. */
    public final short[] segHeights;
    /**
     * Light of blocks (torches, lava, sea pickles) at the top of every segment, 0 to 15: the higher of the light in its top block and in
     * the block over it. In chunks the world never lit (see {@link #lit}) the light the top block gives itself, as it really is.
     */
    public final byte[] segLights;
    /** One bit per cell, set when its chunk had no data. */
    public final long[] missing;
    /**
     * One bit per cell and ring cell, set when the world has lit its chunk: then {@link #segLights} is the world's light, closed caves
     * and walls included. Elsewhere (virtual chunks) the client works the light out from the blocks that give it.
     */
    public final long[] lit;
    /**
     * Blocks giving light outside the tile and its ring, up to {@link #EDGE_LIGHT_REACH} blocks out, for the cells of the tile not lit
     * by the world; empty when all are. {@link #EDGE_LIGHT_FIELDS} ints each: cell x and z (outside the tile), the exclusive top of its
     * segment, the bottom of the first block over it that stops light ({@link #OPEN_CEILING} for none) and its level, 1 to 15.
     */
    public final int[] edgeLights;
    public final List<Identifier> palette;
    public final List<Identifier> blockPalette;
    /** Whether each block of {@link #blockPalette} stands in water: a coral in the sea, a waterlogged slab. */
    public final boolean[] blockWet;

    /** {@link #blockPalette} as an array, made once when first needed (by the mesh builder) instead of for every build. */
    private Identifier @Nullable [] blockIds;
    /** Unpacked size of the last {@link #encode()}, for the performance log. */
    private int rawSize;

    public DetailData(
        int minY, byte[] segCounts, short[] stackTops, byte[] fractions, byte[] biomes, short[] segBlocks, short[] segHeights, byte[] segLights, long[] missing,
        long[] lit, int[] edgeLights, List<Identifier> palette, List<Identifier> blockPalette, boolean[] blockWet
    ) {
        if (segCounts.length != TOTAL_CELLS || stackTops.length != TOTAL_CELLS || fractions.length != TOTAL_CELLS || biomes.length != CELL_COUNT
            || segBlocks.length != segHeights.length || segLights.length != segBlocks.length || missing.length != CELL_COUNT / 64
            || lit.length != TOTAL_CELLS / 64 || edgeLights.length % EDGE_LIGHT_FIELDS != 0 || edgeLights.length > MAX_EDGE_LIGHTS * EDGE_LIGHT_FIELDS) {
            throw new IllegalArgumentException("Bad detail array size");
        }
        for (int i = 0; i < edgeLights.length; i += EDGE_LIGHT_FIELDS) {
            int level = edgeLights[i + 4];
            if (Math.abs(edgeLights[i]) > 2 * CELLS || Math.abs(edgeLights[i + 1]) > 2 * CELLS || level < 1 || level > 15) {
                throw new IllegalArgumentException("Bad edge light");
            }
        }
        if (palette.isEmpty() || palette.size() > MAX_PALETTE || blockPalette.isEmpty() || blockPalette.size() > MAX_BLOCK_PALETTE
            || blockWet.length != blockPalette.size()) {
            throw new IllegalArgumentException("Bad palette size");
        }
        int[] offsets = new int[TOTAL_CELLS];
        int total = 0;
        for (int i = 0; i < TOTAL_CELLS; i++) {
            int count = segCounts[i];
            if (count < 0 || count > MAX_SEGMENTS || i < CELL_COUNT && (biomes[i] & 0xFF) >= palette.size() || fractions[i] < 1 || fractions[i] > 16) {
                throw new IllegalArgumentException("Bad detail cell");
            }
            if (total + count > segBlocks.length) {
                throw new IllegalArgumentException("Bad detail segment total");
            }
            offsets[i] = total;
            int height = 0;
            for (int s = 0; s < count; s++, total++) {
                if (Short.toUnsignedInt(segBlocks[total]) >= blockPalette.size() || segHeights[total] < 1) {
                    throw new IllegalArgumentException("Bad detail segment");
                }
                height += segHeights[total];
            }
            if (count > 0 && height != stackTops[i] - minY) {
                throw new IllegalArgumentException("Bad detail column height");
            }
        }
        if (total != segBlocks.length) {
            throw new IllegalArgumentException("Bad detail segment total");
        }
        this.minY = minY;
        this.segCounts = segCounts;
        this.stackTops = stackTops;
        this.fractions = fractions;
        this.biomes = biomes;
        this.segOffsets = offsets;
        this.segBlocks = segBlocks;
        this.segHeights = segHeights;
        this.segLights = segLights;
        this.missing = missing;
        this.lit = lit;
        this.edgeLights = edgeLights;
        this.palette = List.copyOf(palette);
        this.blockPalette = List.copyOf(blockPalette);
        this.blockWet = blockWet;
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

    /**
     * Index of the cell at {@code cx, cz} of a tile: a cell of the tile, a cell of the ring around it (one outside an edge, after the
     * cells of the tile: the row above the north edge, then south, west and east), or -1 for the corners and anything further out.
     */
    public static int cellIndex(int cx, int cz) {
        boolean insideX = cx >= 0 && cx < CELLS;
        boolean insideZ = cz >= 0 && cz < CELLS;
        if (insideX && insideZ) {
            return cx + cz * CELLS;
        }
        if (insideX) {
            return cz == -1 ? CELL_COUNT + cx : cz == CELLS ? CELL_COUNT + CELLS + cx : -1;
        }
        if (insideZ) {
            return cx == -1 ? CELL_COUNT + 2 * CELLS + cz : cx == CELLS ? CELL_COUNT + 3 * CELLS + cz : -1;
        }
        return -1;
    }

    public boolean isMissing(int cell) {
        return (this.missing[cell >> 6] & 1L << (cell & 63)) != 0;
    }

    /** Whether the world has lit the chunk of a cell or ring cell, see {@link #lit}. */
    public boolean isLit(int cell) {
        return (this.lit[cell >> 6] & 1L << (cell & 63)) != 0;
    }

    public boolean isComplete() {
        for (long word : this.missing) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    /** Unpacked size of the data last encoded, in bytes. */
    public int rawSize() {
        return this.rawSize;
    }

    /**
     * Compressed data for the network. Should it not fit the limits, all cells lose their lowest segments alike, half of them at a time,
     * so what is cut first is always what lies deepest. Over the void of the End that never happens: its tiles are small.
     */
    public byte[] encode() {
        byte[] encoded = null;
        for (int cap = MAX_SEGMENTS; cap >= 1; cap >>= 1) {
            encoded = this.encode(cap);
            if (encoded.length <= MAX_COMPRESSED_SIZE && this.rawSize <= MAX_RAW_SIZE) {
                break;
            }
        }
        return encoded;
    }

    private byte[] encode(int cap) {
        int[] kept = new int[TOTAL_CELLS];
        boolean wideHeights = false;
        for (int cell = 0; cell < TOTAL_CELLS; cell++) {
            int count = this.segCounts[cell];
            int offset = this.segOffsets[cell];
            int keep = Math.min(count, cap);
            // The last segment sent goes on to the bottom of the world, so it must not be air: that would leave a hole.
            while (keep < count && keep > 1 && this.isAir(this.segBlocks[offset + keep - 1])) {
                keep--;
            }
            kept[cell] = keep;
            for (int s = 0; s + 1 < keep; s++) {
                wideHeights |= this.segHeights[offset + s] > 255;
            }
        }
        boolean wideBlocks = this.blockPalette.size() > 256;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
            DataOutputStream out = new DataOutputStream(Zip.compress(bytes));
            try (out) {
                out.writeByte((wideBlocks ? WIDE_BLOCKS : 0) | (wideHeights ? WIDE_HEIGHTS : 0));
                out.writeShort(this.minY);
                writePalette(out, this.palette);
                writePalette(out, this.blockPalette);
                for (boolean wet : this.blockWet) {
                    out.writeBoolean(wet);
                }
                for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                    out.writeByte(kept[cell]);
                }
                out.write(this.biomes);
                out.write(this.fractions);
                for (short top : this.stackTops) {
                    out.writeShort(top);
                }
                for (long word : this.missing) {
                    out.writeLong(word);
                }
                // All blocks first, then all heights: like with like packs better.
                for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                    int offset = this.segOffsets[cell];
                    for (int s = 0; s < kept[cell]; s++) {
                        if (wideBlocks) {
                            out.writeShort(this.segBlocks[offset + s]);
                        } else {
                            out.writeByte(this.segBlocks[offset + s]);
                        }
                    }
                }
                // The height of the last segment of a cell is left out: it is whatever remains down to the bottom of the world.
                for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                    int offset = this.segOffsets[cell];
                    for (int s = 0; s + 1 < kept[cell]; s++) {
                        if (wideHeights) {
                            out.writeShort(this.segHeights[offset + s]);
                        } else {
                            out.writeByte(this.segHeights[offset + s]);
                        }
                    }
                }
                // Mostly 0, which packs to almost nothing.
                for (int cell = 0; cell < TOTAL_CELLS; cell++) {
                    out.write(this.segLights, this.segOffsets[cell], kept[cell]);
                }
                for (long word : this.lit) {
                    out.writeLong(word);
                }
                out.writeShort(this.edgeLights.length / EDGE_LIGHT_FIELDS);
                for (int i = 0; i < this.edgeLights.length; i += EDGE_LIGHT_FIELDS) {
                    out.writeShort(this.edgeLights[i]);
                    out.writeShort(this.edgeLights[i + 1]);
                    out.writeShort(this.edgeLights[i + 2]);
                    out.writeShort(this.edgeLights[i + 3]);
                    out.writeByte(this.edgeLights[i + 4]);
                }
            }
            this.rawSize = out.size();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isAir(short block) {
        return this.blockIds()[Short.toUnsignedInt(block)].equals(AIR);
    }

    private static void writePalette(DataOutputStream out, List<Identifier> palette) throws IOException {
        out.writeShort(palette.size());
        for (Identifier id : palette) {
            byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
            out.writeShort(text.length);
            out.write(text);
        }
    }

    private static List<Identifier> readPalette(DataInputStream in, int max) throws IOException {
        int paletteSize = in.readUnsignedShort();
        if (paletteSize == 0 || paletteSize > max) {
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
        int flags = in.readUnsignedByte();
        boolean wideBlocks = (flags & WIDE_BLOCKS) != 0;
        boolean wideHeights = (flags & WIDE_HEIGHTS) != 0;
        int minY = in.readShort();
        List<Identifier> palette = readPalette(in, MAX_PALETTE);
        List<Identifier> blockPalette = readPalette(in, MAX_BLOCK_PALETTE);
        boolean[] blockWet = new boolean[blockPalette.size()];
        for (int i = 0; i < blockWet.length; i++) {
            blockWet[i] = in.readBoolean();
        }
        byte[] segCounts = new byte[TOTAL_CELLS];
        in.readFully(segCounts);
        byte[] biomes = new byte[CELL_COUNT];
        in.readFully(biomes);
        byte[] fractions = new byte[TOTAL_CELLS];
        in.readFully(fractions);
        short[] stackTops = new short[TOTAL_CELLS];
        for (int i = 0; i < TOTAL_CELLS; i++) {
            stackTops[i] = in.readShort();
        }
        long[] missing = new long[CELL_COUNT / 64];
        for (int i = 0; i < missing.length; i++) {
            missing[i] = in.readLong();
        }
        int total = 0;
        for (int cell = 0; cell < TOTAL_CELLS; cell++) {
            int count = segCounts[cell];
            if (count < 0 || count > MAX_SEGMENTS) {
                throw new IOException("Bad segment count");
            }
            total += count;
        }
        short[] segBlocks = new short[total];
        for (int i = 0; i < total; i++) {
            segBlocks[i] = wideBlocks ? in.readShort() : (short) in.readUnsignedByte();
        }
        short[] segHeights = new short[total];
        int index = 0;
        for (int cell = 0; cell < TOTAL_CELLS; cell++) {
            int count = segCounts[cell];
            if (count == 0) {
                continue;
            }
            int rest = stackTops[cell] - minY;
            for (int s = 0; s + 1 < count; s++, index++) {
                int height = wideHeights ? in.readUnsignedShort() : in.readUnsignedByte();
                segHeights[index] = (short) height;
                rest -= height;
            }
            if (rest < 1) {
                throw new IOException("Bad detail column height");
            }
            segHeights[index++] = (short) rest;
        }
        byte[] segLights = new byte[total];
        in.readFully(segLights);
        for (byte light : segLights) {
            if (light < 0 || light > 15) {
                throw new IOException("Bad block light");
            }
        }
        long[] lit = new long[TOTAL_CELLS / 64];
        for (int i = 0; i < lit.length; i++) {
            lit[i] = in.readLong();
        }
        int edgeCount = in.readUnsignedShort();
        if (edgeCount > MAX_EDGE_LIGHTS) {
            throw new IOException("Too many edge lights");
        }
        int[] edgeLights = new int[edgeCount * EDGE_LIGHT_FIELDS];
        for (int i = 0; i < edgeLights.length; i += EDGE_LIGHT_FIELDS) {
            edgeLights[i] = in.readShort();
            edgeLights[i + 1] = in.readShort();
            edgeLights[i + 2] = in.readShort();
            edgeLights[i + 3] = in.readShort();
            edgeLights[i + 4] = in.readUnsignedByte();
        }
        if (in.available() != 0) {
            throw new IOException("Trailing detail data");
        }
        try {
            return new DetailData(minY, segCounts, stackTops, fractions, biomes, segBlocks, segHeights, segLights, missing, lit, edgeLights, palette, blockPalette,
                blockWet);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }
}
