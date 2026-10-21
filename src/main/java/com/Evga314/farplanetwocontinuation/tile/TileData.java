package com.Evga314.farplanetwocontinuation.tile;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Rough surface of one tile: the height of the top non-air block, what kind of surface it is and the biome there.
 * All arrays are indexed by {@code x + z * TILE_SAMPLES}.
 */
public final class TileData {
    public static final int FLAG_WATER = 1;
    public static final int FLAG_LAVA = 2;
    /** Nothing at all below this sample (the void between the End islands); its height is the one of the land next to it. */
    public static final int FLAG_VOID = 4;
    /** One of vanilla's icebergs on a frozen ocean: land of packed ice standing out of the water. */
    public static final int FLAG_ICEBERG = 8;
    /** With {@link #FLAG_ICEBERG}: snow on top of the iceberg. */
    public static final int FLAG_ICEBERG_SNOW = 16;
    /** With {@link #FLAG_ICEBERG}: an iceberg of blue ice. */
    public static final int FLAG_BLUE_ICE = 32;

    public static final int MAX_PALETTE = 256;
    private static final int MAX_ID_LENGTH = 256;
    public static final int MAX_ENCODED_SIZE = 64 * 1024;
    /** Sub-samples per sample on filtered (coarse) tiles. */
    public static final int SUB_SAMPLES = 9;

    public final short[] heights;
    public final byte[] biomes;
    public final byte[] flags;
    public final List<Identifier> palette;
    /**
     * Present on coarse tiles only: the biome of each of the {@link #SUB_SAMPLES} sub-samples of every sample, so the client can
     * average their colors instead of showing one random point per cell.
     */
    public final byte @Nullable [] subBiomes;
    /** Bit i is set when sub-sample i is under water. Present together with {@link #subBiomes}. */
    public final short @Nullable [] subWater;
    /**
     * How deep the water is at each sample, in blocks from the sea level down to the ground, at most 255; 0 for land and where it is
     * not known. Coarse samples have the average of their water sub-samples. Null when the generator does not know it (the End).
     */
    public final byte @Nullable [] waterDepths;

    public TileData(short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette) {
        this(heights, biomes, flags, palette, null, null);
    }

    public TileData(short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette, byte @Nullable [] subBiomes, short @Nullable [] subWater) {
        this(heights, biomes, flags, palette, subBiomes, subWater, null);
    }

    public TileData(
        short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette, byte @Nullable [] subBiomes, short @Nullable [] subWater,
        byte @Nullable [] waterDepths
    ) {
        if (waterDepths != null && waterDepths.length != FarPlaneConstants.TILE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Bad water depth array");
        }
        this.waterDepths = waterDepths;
        if (heights.length != FarPlaneConstants.TILE_SAMPLE_COUNT
            || biomes.length != FarPlaneConstants.TILE_SAMPLE_COUNT
            || flags.length != FarPlaneConstants.TILE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Bad tile array size");
        }
        if ((subBiomes == null) != (subWater == null)
            || subBiomes != null && (subBiomes.length != FarPlaneConstants.TILE_SAMPLE_COUNT * SUB_SAMPLES || subWater.length != FarPlaneConstants.TILE_SAMPLE_COUNT)) {
            throw new IllegalArgumentException("Bad sub-sample arrays");
        }
        if (subBiomes != null) {
            for (byte biome : subBiomes) {
                if ((biome & 0xFF) >= palette.size()) {
                    throw new IllegalArgumentException("Biome index out of palette");
                }
            }
        }
        this.subBiomes = subBiomes;
        this.subWater = subWater;
        if (palette.isEmpty() || palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("Bad palette size " + palette.size());
        }
        for (byte biome : biomes) {
            if ((biome & 0xFF) >= palette.size()) {
                throw new IllegalArgumentException("Biome index out of palette");
            }
        }
        this.heights = heights;
        this.biomes = biomes;
        this.flags = flags;
        this.palette = List.copyOf(palette);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(FarPlaneConstants.TILE_SAMPLE_COUNT * 4 + 512);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(this.palette.size());
            for (Identifier id : this.palette) {
                byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
                out.writeShort(text.length);
                out.write(text);
            }
            for (short height : this.heights) {
                out.writeShort(height);
            }
            out.write(this.biomes);
            out.write(this.flags);
            out.writeBoolean(this.subBiomes != null);
            if (this.subBiomes != null) {
                out.write(this.subBiomes);
                for (short mask : this.subWater) {
                    out.writeShort(mask);
                }
            }
            out.writeBoolean(this.waterDepths != null);
            if (this.waterDepths != null) {
                out.write(this.waterDepths);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Strict decoder for data that came from the network or from disk. Anything malformed throws and is dropped by the caller.
     */
    public static TileData decode(byte[] data) throws IOException {
        if (data.length > MAX_ENCODED_SIZE) {
            throw new IOException("Tile data too large");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
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
        short[] heights = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = in.readShort();
        }
        byte[] biomes = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        in.readFully(biomes);
        byte[] flags = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
        in.readFully(flags);
        byte[] subBiomes = null;
        short[] subWater = null;
        if (in.readBoolean()) {
            subBiomes = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT * SUB_SAMPLES];
            in.readFully(subBiomes);
            subWater = new short[FarPlaneConstants.TILE_SAMPLE_COUNT];
            for (int i = 0; i < subWater.length; i++) {
                subWater[i] = in.readShort();
            }
        }
        byte[] waterDepths = null;
        if (in.readBoolean()) {
            waterDepths = new byte[FarPlaneConstants.TILE_SAMPLE_COUNT];
            in.readFully(waterDepths);
        }
        if (in.available() != 0) {
            throw new IOException("Trailing tile data");
        }
        try {
            return new TileData(heights, biomes, flags, palette, subBiomes, subWater, waterDepths);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }
}
