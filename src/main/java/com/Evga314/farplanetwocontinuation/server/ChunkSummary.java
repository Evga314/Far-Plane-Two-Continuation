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
import java.util.Set;
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
import net.minecraft.world.level.block.Block;
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
    /** Most segments one column may hold in this pass; profiles will tune it later. */
    private static final int SEGMENT_CAP = 64;

    private static final byte NOT_SCANNED = -1;
    private static final int RUNS = DetailData.MAX_RUNS;
    /**
     * Depth below the top of a run where the color of its sides is taken. Cliffs are grass on top and stone below, so walls get the
     * color found a few blocks down; runs too short for that keep the top color.
     */
    private static final int SIDE_DEPTH = 5;

    /** Plants and other small things without collision are drawn on the ground instead of as blocks, up from this height. */
    private static final int PLANT_MIN_HEIGHT = 4;
    /**
     * Blocks with no collision that are not plants. Snow (layers and powder) is cover. What grows in a stack or hangs in a
     * curtain - sugar cane, the three vines, the stem of a big dripleaf - would lose everything past its lowest block as the
     * plant of a column, which is drawn one block high standing on the highest run, and hanging vines are dropped entirely;
     * as blocks they form runs of their real height. Named, not by shape: in 26.3 kelp plants and double plants have a
     * full-block outline but are plants.
     */
    private static final Set<Block> NOT_PLANTS = Set.of(Blocks.SNOW, Blocks.POWDER_SNOW, Blocks.SUGAR_CANE,
        Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT,
        Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.BIG_DRIPLEAF_STEM);
    /** Thickest a run floating on a liquid can be (ice, lily pads), see scan; ground over a flooded cave is thicker. */
    private static final int FLOATING_THICKNESS = 2;
    /**
     * A run at least this thick that is neither liquid nor seen through counts as the ground when runs are joined: structure floors,
     * roofs and canopies are thinner.
     */
    private static final int GROUND_THICKNESS = 6;
    /** The runs of the column being scanned, kept per thread instead of made for each of the many columns. */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        final int[] tops = new int[RUNS + 1];
        final int[] bottoms = new int[RUNS + 1];
        final int[] blocks = new int[RUNS + 1];
        final int[] sides = new int[RUNS + 1];
        final int[] heights = new int[RUNS + 1];
        final boolean[] clear = new boolean[RUNS + 1];
        final boolean[] liquid = new boolean[RUNS + 1];
    }

    /** What a block state means for the summary, worked out once per state. */
    private static final Map<BlockState, Look> LOOKS = new ConcurrentHashMap<>();
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

    /** How many segments are stored so far. */
    private int segTotal;

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
        Arrays.fill(this.segCounts, NOT_SCANNED);
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
        int x = column & 15;
        int z = column >> 4;
        int start = source.top(x, z);
        int limit = Math.max(this.minY, start - SCAN_DEPTH);
        int topY = Math.min(start, this.maxY + 1) - 1;
        int y = topY;

        this.segOffsets[column] = (short) this.segTotal;
        this.topFraction[column] = 16;
        boolean topFound = false;
        int surfaceY = topY;
        int segments = 0;
        int openBlock = -1;   // block of the segment being grown; -1 = no segment open yet / блок открытого сегмента, -1 = открытого пока нет
        int openHeight = 0;
        while (y >= limit) {
            Look look = look(source.state(x, y, z));
            int block = look.block >= 0 ? look.block : look.plant;
            if (!topFound && look.block != -1) {
                this.topFraction[column] = (byte) look.height;
                surfaceY = y;
                topFound = true;
            }
            if (block == openBlock) {
                openHeight++;
            } else {
                if (openBlock != -1) {
                    addSegment(openBlock, openHeight);
                    segments++;
                }
                if (segments == SEGMENT_CAP) {
                    // Budget spent: this block and everything below folds into the last segment. / Бюджет кончился: этот блок и всё под ним уходит в последний сегмент.
                    this.segHeights[this.segTotal - 1] += (short) (y - this.minY + 1);
                    y = this.minY - 1;
                    break;
                }
                openBlock = block;
                openHeight = 1;
            }
            y--;
        }
        if (openBlock != -1 && y >= this.minY - 1 && segments < SEGMENT_CAP) {
            addSegment(openBlock, openHeight);
            segments++;
        }
        if (segments == 0) {
            addSegment(0, topY + 1 - this.minY);
            segments = 1;
        } else if (limit > this.minY && segments < SEGMENT_CAP) {
            // The scan window ended above the world bottom: the last segment stretches down. / Окно скана кончилось выше дна мира: последний сегмент тянется вниз.
            this.segHeights[this.segTotal - 1] += (short) (limit - this.minY);
        }
        this.segCounts[column] = (byte) segments;
        this.biomes[column] = source.biome(x, surfaceY, z);
    }

    /** Appends one segment to the flat arrays, growing them by doubling when full. / Дописывает один сегмент в плоские массивы, удваивая их при заполнении. */
    private void addSegment(int block, int height) {
        if (this.segTotal == this.segBlocks.length) {
            this.segBlocks = java.util.Arrays.copyOf(this.segBlocks, this.segBlocks.length * 2);
            this.segHeights = java.util.Arrays.copyOf(this.segHeights, this.segHeights.length * 2);
        }
        this.segBlocks[this.segTotal] = (short) block;
        this.segHeights[this.segTotal] = (short) height;
        this.segTotal++;
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
        // Blocks that show nothing but their liquid (a bubble column over magma) are that liquid: as blocks of their own they would
        // stand as pillars in the water.
        if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE && !state.getFluidState().isEmpty()) {
            state = state.getFluidState().createLegacyBlock();
        }
        int id = BuiltInRegistries.BLOCK.getId(state.getBlock());
        int height = 16;
        boolean plant = false;
        try {
            VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!outline.isEmpty()) {
                height = Mth.clamp((int) Math.ceil(outline.max(Direction.Axis.Y) * 16.0 - 1.0E-4), 1, 16);
            }
            // What has no collision is a plant, except the blocks in NOT_PLANTS: snow layers from three up pass the
            // height test below and would be drawn as crossed quads instead of the box they are.
            plant = !NOT_PLANTS.contains(state.getBlock())
                && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() && !outline.isEmpty() && height > PLANT_MIN_HEIGHT;
        } catch (RuntimeException e) {
            // Some modded blocks need a real world for their shape; they count as full blocks.
            height = 16;
        }
        FluidState fluid = state.getFluidState();
        if (plant) {
            if (!fluid.isEmpty()) {
                return new Look(BuiltInRegistries.BLOCK.getId(fluid.createLegacyBlock().getBlock()), 16, 0, true, true);
            }
            return new Look(-1, 16, id, false, false);
        }
        if (state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id == 0) {
            return Look.NOTHING;
        }
        boolean liquid = !fluid.isEmpty() && fluid.createLegacyBlock().getBlock() == state.getBlock();
        return new Look(id, height, 0, liquid || !state.canOcclude(), liquid);
    }

    /**
     * {@code block}: raw id when drawn as a block, -1 when not; {@code plant}: raw id when it is a plant on the ground, else 0;
     * {@code clear}: seen through, so the ground under it stays a run of its own: water, lava, plants growing in them, leaves and
     * other blocks that do not hide what is behind them; {@code liquid}: a liquid, or a plant growing in one.
     */
    private record Look(int block, int height, int plant, boolean clear, boolean liquid) {
        static final Look NOTHING = new Look(-1, 16, 0, false, false);
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
