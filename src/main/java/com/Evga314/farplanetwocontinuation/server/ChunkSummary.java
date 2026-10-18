package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.tile.DetailData;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * What a detailed tile needs from one chunk: for each of its 256 columns up to {@link DetailData#MAX_RUNS} runs of visible blocks
 * (the block on top, the block of the sides and how high the top block is), the plant standing on the ground and the biome at the
 * top. About 8 KB instead of the whole chunk, so chunks read from disk are summarized once and then shared by every tile and level
 * that touches them. Live chunks are summarized column by column, only where a tile actually looks.
 * <p>
 * Blocks are kept as raw registry ids, which only live as long as the server runs; on disk they are written by name.
 */
public final class ChunkSummary {
    /** How far below the surface runs are looked for. Deeper caves are never visible from far away. */
    static final int SCAN_DEPTH = 96;
    private static final byte NOT_SCANNED = -1;
    private static final int RUNS = DetailData.MAX_RUNS;
    /**
     * Depth below the top of a run where the color of its sides is taken. Cliffs are grass on top and stone below, so walls get the
     * color found a few blocks down; runs too short for that keep the top color.
     */
    private static final int SIDE_DEPTH = 5;

    /** Plants and other small things without collision are drawn on the ground instead of as blocks, up from this height. */
    private static final int PLANT_MIN_HEIGHT = 4;
    /** What a block state means for the summary, worked out once per state. */
    private static final Map<BlockState, Look> LOOKS = new ConcurrentHashMap<>();

    private final byte[] counts = new byte[256];
    private final short[] tops = new short[256 * RUNS];
    private final short[] bottoms = new short[256 * RUNS];
    private final short[] blocks = new short[256 * RUNS];
    private final short[] sides = new short[256 * RUNS];
    /** Height of the top block of a run in sixteenths: 16 for full blocks, less for slabs, snow layers and carpets. */
    private final byte[] heights = new byte[256 * RUNS];
    /** The plant on top of the highest run, 0 (air) for none. */
    private final short[] plants = new short[256];
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

    /** Every block the summary refers to, as raw ids, for building the palette of a file. */
    void collectBlocks(IntConsumer output) {
        for (int column = 0; column < 256; column++) {
            int count = Math.max(0, this.ensureCount(column));
            for (int run = 0; run < count; run++) {
                output.accept(Short.toUnsignedInt(this.blocks[column * RUNS + run]));
                output.accept(Short.toUnsignedInt(this.sides[column * RUNS + run]));
            }
            output.accept(Short.toUnsignedInt(this.plants[column]));
        }
    }

    /** Writes a fully scanned summary; biomes and blocks are written as indices into the palettes of the file. */
    void write(DataOutputStream out, Object2IntMap<Holder<Biome>> biomePalette, Int2IntMap blockPalette) throws IOException {
        for (int column = 0; column < 256; column++) {
            int count = Math.max(0, this.ensureCount(column));
            out.writeByte(count);
            for (int run = 0; run < count; run++) {
                int slot = column * RUNS + run;
                out.writeShort(this.tops[slot]);
                out.writeShort(this.bottoms[slot]);
                out.writeShort(blockPalette.get(Short.toUnsignedInt(this.blocks[slot])));
                out.writeShort(blockPalette.get(Short.toUnsignedInt(this.sides[slot])));
                out.writeByte(this.heights[slot]);
            }
            out.writeShort(blockPalette.get(Short.toUnsignedInt(this.plants[column])));
            Holder<Biome> biome = this.biomes[column];
            out.writeShort(biome == null ? -1 : biomePalette.getOrDefault(biome, -1));
        }
    }

    /** Reads what {@link #write} wrote, with blocks as raw ids of this server. Values are checked, since the file may be damaged. */
    static ChunkSummary read(DataInputStream in, List<Holder<Biome>> biomePalette, int[] blockPalette, int minY, int maxY) throws IOException {
        ChunkSummary summary = new ChunkSummary(minY, maxY);
        for (int column = 0; column < 256; column++) {
            int count = in.readUnsignedByte();
            if (count > RUNS) {
                throw new IOException("Bad run count " + count);
            }
            summary.counts[column] = (byte) count;
            for (int run = 0; run < count; run++) {
                int slot = column * RUNS + run;
                summary.tops[slot] = in.readShort();
                summary.bottoms[slot] = in.readShort();
                summary.blocks[slot] = (short) block(blockPalette, in.readUnsignedShort());
                summary.sides[slot] = (short) block(blockPalette, in.readUnsignedShort());
                int height = in.readByte();
                if (height < 1 || height > 16) {
                    throw new IOException("Bad block height " + height);
                }
                summary.heights[slot] = (byte) height;
            }
            summary.plants[column] = (short) block(blockPalette, in.readUnsignedShort());
            int biome = in.readShort();
            if (biome >= biomePalette.size()) {
                throw new IOException("Bad biome index " + biome);
            }
            summary.biomes[column] = biome < 0 ? null : biomePalette.get(biome);
        }
        return summary;
    }

    private static int block(int[] palette, int index) throws IOException {
        if (index >= palette.length) {
            throw new IOException("Bad block index " + index);
        }
        return palette[index];
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

    /** Copies the runs of a column, highest first, with blocks as raw ids. Returns how many there are. */
    int runs(int x, int z, short[] tops, short[] bottoms, short[] blocks, short[] sides, byte[] heights, int base) {
        int column = this.ensure(x, z);
        int count = this.counts[column];
        System.arraycopy(this.tops, column * RUNS, tops, base, count);
        System.arraycopy(this.bottoms, column * RUNS, bottoms, base, count);
        System.arraycopy(this.blocks, column * RUNS, blocks, base, count);
        System.arraycopy(this.sides, column * RUNS, sides, base, count);
        System.arraycopy(this.heights, column * RUNS, heights, base, count);
        return count;
    }

    /** Raw id of the plant on top of a column, 0 for none. */
    int plant(int x, int z) {
        return Short.toUnsignedInt(this.plants[this.ensure(x, z)]);
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
        // One spare slot: when there are more runs than are kept, the two closest ones are joined, gap and all. Leaves join the ground
        // under them, while a ship floating high above an island stays a ship instead of a pillar down to the ground.
        int[] runTops = new int[RUNS + 1];
        int[] runBottoms = new int[RUNS + 1];
        int[] runBlocks = new int[RUNS + 1];
        int[] runSides = new int[RUNS + 1];
        int[] runHeights = new int[RUNS + 1];
        boolean[] runClear = new boolean[RUNS + 1];
        int plant = 0;
        int plantY = Integer.MIN_VALUE;
        int runs = 0;
        while (y >= limit) {
            Look look = look(source.state(x, y, z));
            while (look.block < 0) {
                // The plant right on the ground is drawn on top of the highest run.
                if (runs == 0 && look.plant != 0) {
                    plant = look.plant;
                    plantY = y;
                }
                y--;
                if (y < limit) {
                    break;
                }
                look = look(source.state(x, y, z));
            }
            if (y < limit) {
                break;
            }
            int runTop = y + 1;
            int block = look.block;
            int side = block;
            int under = -1;
            int height = look.height;
            boolean clear = look.clear;
            while (y >= limit) {
                Look below = look(source.state(x, y, z));
                int depth = runTop - 1 - y;
                // A thin block on top (snow on a tree, leaf litter, a carpet) always belongs to what it lies on: it covers only the top,
                // the sides are that block, and the run can be seen through when that block can.
                boolean underThin = depth == 1 && height < 16 && below.block >= 0;
                // Water, leaves and other blocks that can be seen through are separate runs from the ground under them: it is seen too.
                if (below.block < 0 || below.clear != clear && !underThin) {
                    break;
                }
                if (underThin) {
                    clear = below.clear;
                }
                if (underThin || depth == SIDE_DEPTH && height == 16) {
                    side = below.block;
                }
                if (depth == 1) {
                    under = below.block;
                }
                y--;
            }
            // Only a plant standing right on the highest run counts; vines hanging in the air do not.
            if (runs == 0 && plantY != runTop) {
                plant = 0;
            }
            runTops[runs] = runTop;
            runBottoms[runs] = y + 1;
            runBlocks[runs] = block;
            // A run too short for the side depth still has its sides made of what lies under the top block: a chorus flower on its
            // stalk, a short grass step over dirt.
            runSides[runs] = side == block && under >= 0 ? under : side;
            runHeights[runs] = height;
            runClear[runs] = clear;
            runs++;
            if (runs > RUNS) {
                // A joined run keeps the look of the upper one, so water or leaves joined with the ground under them would hide that
                // ground, which is seen through them. That is never done while anything else can be joined; ground with water under it, a
                // flooded cave, is joined like any other run.
                int closest = 0;
                long closestCost = Long.MAX_VALUE;
                for (int i = 0; i < runs - 1; i++) {
                    long cost = runBottoms[i] - runTops[i + 1] + (runClear[i] && !runClear[i + 1] ? 1L << 32 : 0L);
                    if (cost < closestCost) {
                        closestCost = cost;
                        closest = i;
                    }
                }
                runBottoms[closest] = runBottoms[closest + 1];
                for (int i = closest + 1; i < runs - 1; i++) {
                    runTops[i] = runTops[i + 1];
                    runBottoms[i] = runBottoms[i + 1];
                    runBlocks[i] = runBlocks[i + 1];
                    runSides[i] = runSides[i + 1];
                    runHeights[i] = runHeights[i + 1];
                    runClear[i] = runClear[i + 1];
                }
                runs--;
            }
        }
        for (int run = 0; run < runs; run++) {
            this.tops[base + run] = (short) runTops[run];
            this.bottoms[base + run] = (short) runBottoms[run];
            this.blocks[base + run] = (short) runBlocks[run];
            this.sides[base + run] = (short) runSides[run];
            this.heights[base + run] = (byte) runHeights[run];
        }
        this.counts[column] = (byte) runs;
        this.plants[column] = (short) plant;
        this.biomes[column] = source.biome(x, (runs > 0 ? this.tops[base] : start) - 1, z);
    }

    /**
     * What a block state looks like from far away. Air and blocks invisible on maps (glass, barriers, light) are not drawn. Plants,
     * flowers, torches and other small things without collision are no blocks either, but are remembered as the plant on the ground;
     * those growing under water (kelp, seagrass) count as the water around them. Everything else is a block, as high as its shape.
     */
    private static Look look(BlockState state) {
        Look look = LOOKS.get(state);
        if (look == null) {
            look = computeLook(state);
            if (LOOKS.size() < 1 << 16) {
                LOOKS.put(state, look);
            }
        }
        return look;
    }

    private static Look computeLook(BlockState state) {
        if (state.isAir()) {
            return Look.NOTHING;
        }
        int id = BuiltInRegistries.BLOCK.getId(state.getBlock());
        int height = 16;
        boolean plant = false;
        try {
            VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!outline.isEmpty()) {
                height = Mth.clamp((int) Math.ceil(outline.max(Direction.Axis.Y) * 16.0 - 1.0E-4), 1, 16);
            }
            plant = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() && !outline.isEmpty() && height > PLANT_MIN_HEIGHT;
        } catch (RuntimeException e) {
            // Some modded blocks need a real world for their shape; they count as full blocks.
            height = 16;
        }
        FluidState fluid = state.getFluidState();
        if (plant) {
            if (!fluid.isEmpty()) {
                return new Look(BuiltInRegistries.BLOCK.getId(fluid.createLegacyBlock().getBlock()), 16, 0, true);
            }
            return new Look(-1, 16, id, false);
        }
        if (state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id == 0) {
            return Look.NOTHING;
        }
        boolean liquid = !fluid.isEmpty() && fluid.createLegacyBlock().getBlock() == state.getBlock();
        return new Look(id, height, 0, liquid || !state.canOcclude());
    }

    /**
     * {@code block}: raw id when drawn as a block, -1 when not; {@code plant}: raw id when it is a plant on the ground, else 0;
     * {@code clear}: seen through, so the ground under it stays a run of its own: water, lava, plants growing in them, leaves and
     * other blocks that do not hide what is behind them.
     */
    private record Look(int block, int height, int plant, boolean clear) {
        static final Look NOTHING = new Look(-1, 16, 0, false);
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
