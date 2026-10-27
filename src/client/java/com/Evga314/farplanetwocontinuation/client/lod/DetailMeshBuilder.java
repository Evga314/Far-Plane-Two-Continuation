package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.BlockDetail;
import com.Evga314.farplanetwocontinuation.tile.BlockLook;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@link DetailData} into faces, straight from the segments of the cells: a segment shows its top where what lies on it does not
 * hide it, its underside where it hangs over air, and its sides wherever the segments of the neighbouring cell leave them open, so
 * overhangs, caves near their mouths, ledges and floors of buildings come out as they are, each segment with its own look. Neighbouring
 * tops of the same height and look are merged into one quad, walls of the same look one on top of the other too. Every quad faces outwards (counter-clockwise seen from outside), so back faces can be
 * culled: where the walls of two neighbouring tiles lie in the same plane, only the one facing the camera is drawn, never both. Every
 * quad is four vertices; indices come from a shared quad index buffer.
 * <p>
 * How the blocks look depends on {@link BlockDetail}: flat colors taken from the block textures, or the textures themselves, from a
 * blurred copy up to vanilla's own, with thin blocks (snow layers, carpets, slabs) as thin as they are, leaves with gaps and plants
 * on the ground.
 */
final class DetailMeshBuilder {
    private static final int CELLS = DetailData.CELLS;
    /**
     * Position in fixed point (x and z in 64ths of a block, y in 18ths) and the sprite size in texels as four shorts, the color, and the
     * sprite corner in the atlas in half texels as two shorts. Half the size of floats, which matters for the memory of phones. Heights
     * in 18ths hold vanilla's water surface, 8/9 of a block, exactly, so the detailed water meets vanilla's level; they reach 1820
     * blocks up and down, past the tallest worlds of vanilla and the usual mods.
     */
    static final int TEXTURED_VERTEX_SIZE = 16;
    static final float XZ_UNITS = 64.0F;
    static final float Y_UNITS = 18.0F;
    // Vanilla's shading of block faces by direction.
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_BOTTOM = 0.5F;
    private static final float SHADE_NORTH_SOUTH = 0.8F;
    private static final float SHADE_EAST_WEST = 0.6F;
    // How the shader maps a face to its texture, kept in the alpha of the vertex color: 255 minus the kind, minus 4 for faces with gaps.
    private static final int KIND_HORIZONTAL = 0;
    private static final int KIND_NORTH_SOUTH = 1;
    private static final int KIND_EAST_WEST = 2;
    private static final int KIND_PLANT = 3;
    private static final int CUTOUT = 4;
    /** Translucent water, drawn in a pass of its own after everything else. */
    private static final int TRANSLUCENT_WATER = 8;
    /**
     * Next to cells without data the rough terrain is shown, a little lower than the blocks. A short wall closes that step without
     * dropping a tall cliff into the rough terrain.
     */
    private static final int UNKNOWN_WALL = 8;
    /**
     * Faces with gaps (leaves) get a second, closed layer almost one block further in, like the Cull Less Leaves mod: through the gaps
     * the crown looks full of leaves instead of hollow, and nothing behind it has to be drawn there. A little darker, as inside a crown.
     * Short of a whole block, so it never lies in the face of the block behind (a log next to the leaves), where the two would flicker.
     */
    private static final float INNER_LAYER = 17.0F / 18.0F;
    private static final float INNER_SHADE = 0.75F;
    /** Size of the block on top of a stalk, a chorus flower's. */
    private static final float CAP_SIZE = 14.0F / 16.0F;
    private static final int[] DIRECTION_X = {0, 0, -1, 1};
    private static final int[] DIRECTION_Z = {-1, 1, 0, 0};
    private static final Identifier WATER = Identifier.withDefaultNamespace("water");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    // What a block is to the faces around it, see Tile#hides.
    private static final byte AIR = 0;
    private static final byte PLANT = 1;
    private static final byte LIQUID = 2;
    private static final byte CLEAR = 3;
    private static final byte SOLID = 4;
    private static final byte STALK = 5;
    /** On coarse cells, segments of up to this many blocks between two of the same block look like that block on the walls. */
    private static final int INCLUSION = 2;

    private DetailMeshBuilder() {
    }

    /**
     * How a surface is drawn: its color (the tint for textured faces), and for textured faces the texture and whether it has gaps.
     * {@code uShift} moves the texture sideways on the atlas, see {@link #column}. {@code overlay}: a second layer drawn over the top block of a wall, the grass edge on the side of a grass block tinted by the
     * biome as vanilla's; null for most.
     */
    private record Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout, boolean water, float uShift, @Nullable Paint overlay) {
        Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout, boolean water, float uShift) {
            this(color, sprite, cutout, water, uShift, null);
        }

        Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout, boolean water) {
            this(color, sprite, cutout, water, 0.0F, null);
        }

        Paint(int color, @Nullable TextureAtlasSprite sprite, boolean cutout) {
            this(color, sprite, cutout, false, 0.0F, null);
        }

        /** The same texture with its gaps filled, for the closed layer inside faces with gaps. */
        Paint closed() {
            return new Paint(this.color, this.sprite, false, false, this.uShift);
        }
    }

    static GeneralMeshFactory.Built build(long key, int epoch, DetailData data, List<@Nullable Holder<Biome>> biomes, BlockDetail detail) {
        return new Tile(data, biomes, detail, TilePos.unpack(key)).build(key, epoch);
    }

    /**
     * The paint of one face of a block at a place. Flat faces get the average color of the texture with the biome tint; textured
     * faces only the tint. Water stays in the flat color of the rough terrain's water at every level, so the two match where they meet.
     * The colors are evened out over the biomes around the place ({@code around}, the biomes of the cells around this one, null
     * where a place has no neighbours), the way vanilla blends biome colors, so they do not change in steps at biome borders.
     * {@code groundCover}: the face of a thin layer on the ground (see BlockLooks.Look), whose gaps show at every textured level: filled,
     * it would cover the ground under it as a square of its average color.
     */
    private static Paint paint(
        BlockLooks.Face face, BlockState state, boolean water, BlockDetail detail, @Nullable Holder<Biome> biome, int x, int y, int z,
        BlockLooks.@Nullable Blend around, boolean groundCover
    ) {
        boolean textured = detail.textured();
        if (water && detail.translucentWater() && face.sprite() != null) {
            int tint = around == null
                ? biome == null ? 0xFF3F76E4 : ARGB.opaque(biome.value().getWaterColor())
                : blendedWaterColor(around, biome, true);
            return new Paint(tint, face.sprite(), false, true);
        }
        if (water) {
            int color = ARGB.scaleRGB(around == null ? TileColors.waterColor(biome) : blendedWaterColor(around, biome, false), GeneralMeshFactory.WATER_SHADE);
            return new Paint(color, null, false);
        }
        if (!textured || face.sprite() == null) {
            return new Paint(ARGB.opaque(around == null ? face.color(state, biome, x, y, z) : face.colorBlended(state, around, y)), null, false);
        }
        Paint overlay = face.overlaySprite() == null ? null : new Paint(
            around == null ? face.overlayTintColor(state, biome, x, y, z) : face.overlayTintColorBlended(state, around, y), face.overlaySprite(), true
        );
        return new Paint(
            around == null ? face.tintColor(state, biome, x, y, z) : face.tintColorBlended(state, around, y), face.sprite(),
            face.cutout() && (detail.cutout() || groundCover), false, 0.0F, overlay
        );
    }

    /** The color of water at a place, blended between the biomes around it like every other color, see BlockLooks.Blend. */
    private static int blendedWaterColor(BlockLooks.Blend around, @Nullable Holder<Biome> biome, boolean translucent) {
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        for (int i = 0; i < 4; i++) {
            float weight = around.weights[i];
            if (weight <= 0.0F) {
                continue;
            }
            Holder<Biome> near = around.biomes[i] != null ? around.biomes[i] : biome;
            int color = translucent
                ? near == null ? 0xFF3F76E4 : ARGB.opaque(near.value().getWaterColor())
                : TileColors.waterColor(near);
            red += weight * ARGB.red(color);
            green += weight * ARGB.green(color);
            blue += weight * ARGB.blue(color);
        }
        return ARGB.color(255, Math.round(red), Math.round(green), Math.round(blue));
    }

    /**
     * Fills where the colors of a cell are blended from: the middles of the four 4-block biome squares around the middle of the cell,
     * weighted by how close they are. A square outside the tile or without data lends its place to the cell's own biome, so the
     * blend weakens at the edge of a tile instead of stepping.
     */
    private static void blend(
        BlockLooks.Blend blend, DetailData data, List<@Nullable Holder<Biome>> biomes, @Nullable Holder<Biome> own, long baseX, long baseZ, int cx, int cz,
        int cell
    ) {
        // Square middles lie 4q + 2 blocks from the corner of the tile; tiles start on whole squares.
        float fx = (cx * cell + cell * 0.5F - 2.0F) * 0.25F;
        float fz = (cz * cell + cell * 0.5F - 2.0F) * 0.25F;
        int qx = Mth.floor(fx);
        int qz = Mth.floor(fz);
        float tx = fx - qx;
        float tz = fz - qz;
        int span = CELLS * cell;
        for (int i = 0; i < 4; i++) {
            int blockX = (qx + (i & 1)) * 4 + 2;
            int blockZ = (qz + (i >> 1)) * 4 + 2;
            Holder<Biome> biome = own;
            if (blockX >= 0 && blockZ >= 0 && blockX < span && blockZ < span) {
                int index = blockX / cell + blockZ / cell * CELLS;
                if (!data.isMissing(index)) {
                    biome = biomes.get(data.biomes[index] & 0xFF);
                }
            }
            blend.biomes[i] = biome;
            blend.xs[i] = (int) Mth.clamp(baseX + blockX, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
            blend.zs[i] = (int) Mth.clamp(baseZ + blockZ, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
            blend.weights[i] = ((i & 1) == 0 ? 1.0F - tx : tx) * ((i >> 1) == 0 ? 1.0F - tz : tz);
        }
    }

    /** Everything known about one tile while its quads are written. */
    private static final class Tile {
        private final DetailData data;
        private final List<@Nullable Holder<Biome>> biomes;
        private final BlockDetail detail;
        private final int cell;
        private final long baseX;
        private final long baseZ;
        private final Identifier[] ids;
        private final BlockLooks.@Nullable Look[] looks;
        /** What each block of the palette is to the faces around it: {@link #AIR}, {@link #PLANT} and so on. */
        private final byte[] kinds;
        /** Height of each block of the palette in sixteenths, for thin blocks on top of a stack. */
        private final byte[] blockHeights;
        private final boolean[] water;
        /** The liquid a block of the palette is or stands in (raw id, 0 for none): water and what stands in it are one liquid. */
        private final int[] liquids;
        /**
         * For small things standing in water (kelp, seagrass, corals, sea pickles): their own look, drawn as a cross in the water;
         * null for the rest.
         */
        private final BlockLooks.@Nullable Look[] submerged;
        /**
         * Blocks standing in water that are drawn as blocks (waterlogged slabs, stairs, leaves): the water fills what they leave of
         * their block, and water next to them does not wall itself off from them.
         */
        private final boolean[] wets;
        /** The light a small thing in water gives (sea pickles), 0 for the rest; see {@link #submerged}. */
        private final int[] glows;
        /** The light each block of the palette gives, 0 to 15, by its default state. */
        private final int[] emissions;
        /** Whether each block of the palette stops light, see TileLight. */
        private final boolean[] opaque;
        /** Whether each block of the palette is snow, under which grass shows its snowy side as in vanilla. */
        private final boolean[] snow;
        /** Per segment of a block standing in water: the paint of that water, -1 for the rest. */
        private final int[] wetPaints;
        private final BlockLooks.Look waterLook = BlockLooks.get(WATER);
        /** The cave plug (see DetailData#CAVE_PLUG), drawn as stone in a dim light, see PLUG_LIGHT. */
        private final boolean[] plugs;
        /** Per segment of the tile's cells: the paints of a plant under water and of its upper half, -1 for none. */
        private final int[] plantPaints;
        private final int[] plantUpperPaints;
        /** Per segment of the tile's cells: the exclusive top, where its top face lies, and its top and side paints. */
        private final int[] segTops;
        private final float[] visTops;
        private final int[] topPaints;
        private final int[] sidePaints;
        /** Per cell of the tile: its first segment drawn as a block (-1 for none), the top of it, and the bottom of the run it starts. */
        private final int[] firsts = new int[DetailData.CELL_COUNT];
        private final int[] firstTops = new int[DetailData.CELL_COUNT];
        private final int[] firstRunBottoms = new int[DetailData.CELL_COUNT];
        private final Paints paints = new Paints();
        private final QuadWriter out;
        private int floorPaint;
        // Walls being merged, per direction: the next wall of the same paint right under one of these continues it.
        private final float[] pendingTops = new float[4];
        private final float[] pendingBottoms = new float[4];
        private final int[] pendingPaints = new int[4];
        private float x0;
        private float x1;
        private float z0;
        private float z1;
        // Lowest and highest block of the chunk being written whose faces can be seen from outside, see surfaces.
        private int low;
        private int high;

        Tile(DetailData data, List<@Nullable Holder<Biome>> biomes, BlockDetail detail, TilePos pos) {
            this.data = data;
            this.biomes = biomes;
            this.detail = detail;
            this.cell = 1 << (pos.level() - 1);
            this.baseX = pos.minX();
            this.baseZ = pos.minZ();
            this.out = new QuadWriter(detail.textured());
            this.ids = data.blockIds();
            int size = this.ids.length;
            this.looks = new BlockLooks.Look[size];
            this.kinds = new byte[size];
            this.blockHeights = new byte[size];
            this.water = new boolean[size];
            this.liquids = new int[size];
            this.submerged = new BlockLooks.Look[size];
            this.wets = new boolean[size];
            this.glows = new int[size];
            this.emissions = new int[size];
            this.opaque = new boolean[size];
            this.snow = new boolean[size];
            this.plugs = new boolean[size];
            int waterId = BuiltInRegistries.BLOCK.getId(Blocks.WATER);
            for (int i = 0; i < size; i++) {
                this.plugs[i] = this.ids[i].equals(DetailData.CAVE_PLUG);
                Block block = BuiltInRegistries.BLOCK.getOptional(this.ids[i]).orElse(Blocks.STONE);
                BlockState state = block.defaultBlockState();
                BlockLook.Look common = block == Blocks.AIR ? BlockLook.Look.NOTHING : BlockLook.look(state);
                this.blockHeights[i] = (byte) common.height();
                this.emissions[i] = this.plugs[i] ? 0 : state.getLightEmission();
                this.opaque[i] = this.plugs[i] || common.opaque();
                this.snow[i] = state.is(BlockTags.SNOW);
                // The server tells whether a block stands in water (see BlockLook#segment): a coral in the sea, not one on dry land.
                boolean wet = data.blockWet[i] && !common.liquid();
                if (common.liquid()) {
                    BlockLooks.Look look = BlockLooks.get(this.ids[i]);
                    this.liquids[i] = common.fluid();
                    this.water[i] = look.fluid() && this.ids[i].equals(WATER);
                    this.looks[i] = look;
                    this.kinds[i] = LIQUID;
                    continue;
                }
                if (wet && small(state, common)) {
                    // A small thing in the water: its faces are those of the water, it is drawn with the plants; what glows (sea pickles)
                    // farther out, see GLOW_REACH in LodManager.
                    this.submerged[i] = BlockLooks.get(this.ids[i]);
                    this.glows[i] = state.getLightEmission();
                    this.liquids[i] = waterId;
                    this.water[i] = true;
                    this.looks[i] = BlockLooks.get(WATER);
                    this.kinds[i] = LIQUID;
                    continue;
                }
                if (common.block() < 0) {
                    this.kinds[i] = common.plant() != 0 ? PLANT : AIR;
                    if (this.kinds[i] == PLANT) {
                        this.looks[i] = BlockLooks.get(this.ids[i]);
                    }
                    continue;
                }
                BlockLooks.Look look = BlockLooks.get(this.ids[i]);
                this.wets[i] = wet;
                this.liquids[i] = wet ? waterId : 0;
                this.looks[i] = look;
                this.kinds[i] = look.columnWidth() > 0.0F ? STALK : common.clear() ? CLEAR : SOLID;
            }
            int segments = data.segBlocks.length;
            this.segTops = new int[segments];
            this.visTops = new float[segments];
            this.topPaints = new int[segments];
            this.sidePaints = new int[segments];
            this.plantPaints = new int[segments];
            this.plantUpperPaints = new int[segments];
            this.wetPaints = new int[segments];
        }

        /**
         * Whether a block standing in water is a small thing in it, drawn as a plant: what has no collision (kelp, corals, their fans),
         * or what fills neither the width of its block nor its height (sea pickles, lanterns). Slabs and stairs are blocks.
         */
        private static boolean small(BlockState state, BlockLook.Look look) {
            if (look.plant() != 0 || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return true;
            }
            try {
                net.minecraft.world.phys.AABB bounds = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds();
                return look.height() < 16 && (bounds.minX > 0.0 || bounds.maxX < 1.0 || bounds.minZ > 0.0 || bounds.maxZ < 1.0);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private int block(int segment) {
            return Short.toUnsignedInt(this.data.segBlocks[segment]);
        }

        private static boolean open(byte kind) {
            return kind == AIR || kind == PLANT || kind == STALK;
        }

        /**
         * Whether a block next to the side of another hides it: solid blocks and blocks seen through do, liquids only their own kind.
         * Thin blocks (leaf litter, snow layers, slabs) do not: the side is drawn whole behind them, so their gaps show it and not the
         * inside of the block next door; what they cover of it is hidden by them anyway.
         */
        private boolean hides(int cover, int block) {
            byte kind = this.kinds[cover];
            return (kind == SOLID || kind == CLEAR) && (this.blockHeights[cover] >= 16 || !this.detail.thinBlocks())
                || (kind == LIQUID || this.wets[cover]) && this.liquids[cover] == this.liquids[block];
        }

        /**
         * Whether the top of a block can be seen under what lies on it; a block seen through (leaves, ice) shows it. Water under
         * something standing in the same water goes on into it.
         */
        private boolean topOpen(int above, int block) {
            byte kind = this.kinds[above];
            return kind != SOLID && (kind != LIQUID && !this.wets[above] || this.liquids[above] != this.liquids[block]);
        }

        GeneralMeshFactory.Built build(long key, int epoch) {
            this.prepare();
            this.collectLights();
            QuadWriter out = this.out;
            int cellsPerChunk = Math.max(1, 16 / this.cell);
            int chunksPerSide = CELLS / cellsPerChunk;
            int[] chunkSurfaces = new int[chunksPerSide * chunksPerSide * 2];
            out.chunks(chunksPerSide * chunksPerSide);
            // Glowing things in the water (sea pickles) and then plants come last in every chunk, so far chunks can be drawn without
            // them: plants only on the finest tiles, glowing things on all.
            boolean extras = this.detail.plants();
            boolean plants = extras && this.cell == 1;
            for (int chunkZ = 0; chunkZ < chunksPerSide; chunkZ++) {
                for (int chunkX = 0; chunkX < chunksPerSide; chunkX++) {
                    WorkThrottle.yieldToFrames();
                    int chunk = chunkX + chunkZ * chunksPerSide;
                    out.startChunk(chunk);
                    int startX = chunkX * cellsPerChunk;
                    int startZ = chunkZ * cellsPerChunk;
                    this.low = Integer.MAX_VALUE;
                    this.high = Integer.MIN_VALUE;
                    this.topFaces(startX, startX + cellsPerChunk, startZ, startZ + cellsPerChunk);
                    for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                        for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                            this.cell(cx, cz);
                        }
                    }
                    chunkSurfaces[chunk * 2] = this.low;
                    chunkSurfaces[chunk * 2 + 1] = this.high;
                    if (extras) {
                        out.startGlows(chunk);
                        for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                            for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                                this.plants(cx, cz, true);
                            }
                        }
                        out.startPlants(chunk);
                        if (plants) {
                            for (int cz = startZ; cz < startZ + cellsPerChunk; cz++) {
                                for (int cx = startX; cx < startX + cellsPerChunk; cx++) {
                                    this.plants(cx, cz, false);
                                }
                            }
                        }
                    }
                }
            }
            ByteBuffer vertices = out.finish();
            int[] segments = out.segments();
            int[] chunkPlants = extras ? out.plantStarts() : null;
            float minY = out.minY;
            float maxY = out.maxY;
            if (minY > maxY) {
                minY = 0.0F;
                maxY = 0.0F;
            }
            return new GeneralMeshFactory.Built(
                key, epoch, vertices, minY, maxY, minY, null, out.quads * 6, true, coverage(this.data, this.cell), this.data.isComplete(), segments,
                chunkSurfaces, this.detail.textured(), out.water, chunkPlants, out.cutout, null
            );
        }

        /** Heights and paints of every segment of the tile's cells, before any quad is written. */
        private void prepare() {
            DetailData data = this.data;
            BlockLooks.Blend around = new BlockLooks.Blend();
            BlockLooks.Look stone = BlockLooks.get(STONE);
            this.floorPaint = this.paints.index(paint(stone.top(), stone.state(), false, this.detail, null, 0, 0, 0, null, false));
            for (int cz = 0; cz < CELLS; cz++) {
                for (int cx = 0; cx < CELLS; cx++) {
                    int index = cx + cz * CELLS;
                    this.firsts[index] = -1;
                    this.firstTops[index] = Integer.MIN_VALUE;
                    int count = data.segCounts[index];
                    if (count == 0) {
                        continue;
                    }
                    int offset = data.segOffsets[index];
                    Holder<Biome> biome = this.biomes.get(data.biomes[index] & 0xFF);
                    blend(around, data, this.biomes, biome, this.baseX, this.baseZ, cx, cz, this.cell);
                    int worldX = (int) Mth.clamp(this.baseX + (long) cx * this.cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                    int worldZ = (int) Mth.clamp(this.baseZ + (long) cz * this.cell, -FarPlaneConstants.WORLD_LIMIT, FarPlaneConstants.WORLD_LIMIT);
                    int top = data.stackTops[index];
                    int first = -1;
                    for (int s = offset; s < offset + count; s++) {
                        int block = this.block(s);
                        byte kind = this.kinds[block];
                        this.segTops[s] = top;
                        int height = data.segHeights[s];
                        BlockLooks.Look look = this.looks[block];
                        this.topPaints[s] = -1;
                        this.sidePaints[s] = -1;
                        this.plantPaints[s] = -1;
                        this.plantUpperPaints[s] = -1;
                        this.wetPaints[s] = this.wets[block]
                            ? this.paints.index(paint(this.waterLook.top(), this.waterLook.state(), true, this.detail, biome, worldX, top - 1, worldZ, around, false))
                            : -1;
                        BlockLooks.Look plantLook = this.submerged[block];
                        if (plantLook != null) {
                            this.plantPaints[s] = this.paints.index(plantPaint(plantLook.plant(), plantLook, this.detail, biome, worldX, top - height, worldZ, around));
                            if (plantLook.plantUpper() != null) {
                                this.plantUpperPaints[s] = this.paints.index(plantPaint(plantLook.plantUpper(), plantLook, this.detail, biome, worldX, top - 1, worldZ, around));
                            }
                        }
                        if (kind == PLANT) {
                            // The plant and, for plants two blocks tall, its upper half.
                            this.topPaints[s] = this.paints.index(plantPaint(look.plant(), look, this.detail, biome, worldX, top - height, worldZ, around));
                            if (look.plantUpper() != null) {
                                this.sidePaints[s] = this.paints.index(plantPaint(look.plantUpper(), look, this.detail, biome, worldX, top - 1, worldZ, around));
                            }
                        } else if (kind != AIR) {
                            if (first < 0) {
                                first = s;
                            }
                            int y = top - 1;
                            if (kind == STALK) {
                                Paint stalkSide = paint(look.side(), look.state(), false, this.detail, biome, worldX, y, worldZ, around, look.groundCover());
                                // On single-block cells the stalk texture is moved so its middle is in the middle of the column; on coarser
                                // cells the column is not where the texture repeats, so it gets the stalk's color.
                                this.sidePaints[s] = this.paints.index(this.cell == 1 && stalkSide.sprite() != null
                                    ? new Paint(stalkSide.color(), stalkSide.sprite(), stalkSide.cutout(), false,
                                        look.columnU() - (stalkSide.sprite().getU0() + stalkSide.sprite().getU1()) * 0.5F)
                                    : new Paint(ARGB.opaque(look.side().color(look.state(), biome, worldX, y, worldZ)), null, false));
                                this.topPaints[s] = this.paints.index(new Paint(ARGB.opaque(look.top().color(look.state(), biome, worldX, y, worldZ)), null, false));
                            } else {
                                boolean water = this.water[block];
                                this.topPaints[s] = this.paints.index(paint(look.top(), look.state(), water, this.detail, biome, worldX, y, worldZ, around, look.groundCover()));
                                // Under snow, grass has the snowy side, as vanilla's snowy grass block.
                                BlockLooks.Face side = look.snowySide() != null && s > offset && this.snow[this.block(s - 1)] ? look.snowySide() : look.side();
                                this.sidePaints[s] = this.paints.index(paint(side, look.state(), water, this.detail, biome, worldX, y, worldZ, around, look.groundCover()));
                                if (this.plugs[block] && !this.detail.textured()) {
                                    // Textured, it gets a dim sky light instead (see cell).
                                    this.topPaints[s] = this.dark(this.topPaints[s]);
                                    this.sidePaints[s] = this.dark(this.sidePaints[s]);
                                }
                            }
                        }
                        this.visTops[s] = this.visibleTop(index, s, first, top, offset);
                        top -= height;
                    }
                    this.firsts[index] = first;
                    if (first < 0) {
                        continue;
                    }
                    this.firstTops[index] = this.segTops[first];
                    this.firstRunBottoms[index] = this.runBottom(first, offset + count);
                    this.fixSides(offset, count);
                }
            }
        }

        /** The light of blocks over the faces (see TileLight); only with plants, the most detailed blocks. */
        private void collectLights() {
            this.out.light = this.detail.plants() ? TileLight.of(this.data, this.opaque, this.emissions, this.cell) : null;
        }

        /**
         * Where the top face of a segment lies: thin blocks are lower, so is water, where nothing over them fills the rest of their
         * block. Blocks seen through do not (leaves over leaf litter under a tree, ice over water), as in vanilla.
         */
        private float visibleTop(int index, int segment, int first, int top, int offset) {
            int block = this.block(segment);
            byte aboveKind = segment == offset ? AIR : this.kinds[this.block(segment - 1)];
            boolean openAbove = open(aboveKind) || aboveKind == CLEAR;
            if (this.kinds[block] == LIQUID) {
                // Vanilla draws the top of water a ninth of a block under the block's top; here too, so the two meet level.
                return this.water[block] && openAbove ? top - WATER_DIP : top;
            }
            if (!this.detail.thinBlocks() || !openAbove) {
                return top;
            }
            int height = segment == first ? this.data.fractions[index] : this.blockHeights[block];
            return height >= 16 ? top : top - 1 + height / 16.0F;
        }

        /** Bottom of the run of blocks a segment starts: the segments under it of the same kind, solid, seen through or liquid. */
        private int runBottom(int first, int end) {
            int kind = runKind(this.kinds[this.block(first)]);
            int bottom = this.segTops[first] - this.data.segHeights[first];
            for (int s = first + 1; s < end && runKind(this.kinds[this.block(s)]) == kind; s++) {
                bottom -= this.data.segHeights[s];
            }
            return bottom;
        }

        private static int runKind(byte kind) {
            return kind == LIQUID ? 2 : kind == CLEAR || kind == STALK ? 1 : kind == SOLID ? 0 : -1;
        }

        /**
         * Sides that look like another block than their own. A thin block on top drawn as a full block only shows itself on top; its
         * sides look like what it lies on. On coarse cells a short segment between two of the same block (an ore in stone) takes their
         * look, so walls do not get speckled in big squares.
         */
        private void fixSides(int offset, int count) {
            for (int s = offset; s < offset + count; s++) {
                int block = this.block(s);
                byte kind = this.kinds[block];
                if (kind != SOLID && kind != CLEAR) {
                    continue;
                }
                if (!this.detail.thinBlocks() && this.blockHeights[block] < 16 && s + 1 < offset + count && this.sidePaints[s + 1] >= 0
                    && (s == offset || open(this.kinds[this.block(s - 1)]))) {
                    this.sidePaints[s] = this.sidePaints[s + 1];
                } else if (this.cell > 1 && s > offset && s + 1 < offset + count && this.data.segHeights[s] <= INCLUSION
                    && this.block(s - 1) == this.block(s + 1) && this.kinds[this.block(s - 1)] == SOLID) {
                    this.sidePaints[s] = this.sidePaints[s - 1];
                }
            }
        }

        private float x(int cx) {
            return GeneralMeshFactory.localCoordinate(this.baseX, cx * this.cell, 1);
        }

        private float z(int cz) {
            return GeneralMeshFactory.localCoordinate(this.baseZ, cz * this.cell, 1);
        }

        /** Whether the first segment of a cell is drawn by itself: a stalk, or the block on top of one (a chorus flower). */
        private boolean firstDrawnApart(int index) {
            int first = this.firsts[index];
            return first < 0 || this.kinds[this.block(first)] == STALK || this.isCap(first, this.data.segOffsets[index] + this.data.segCounts[index]);
        }

        /** A block of one height on top of a stalk, as a chorus flower is on a chorus plant: drawn as a block a little smaller. */
        private boolean isCap(int segment, int end) {
            return segment + 1 < end && this.kinds[this.block(segment + 1)] == STALK && this.kinds[this.block(segment)] != STALK
                && this.data.segHeights[segment] == 1;
        }

        /**
         * Top faces of the first segments of a block of cells; following cells of a row with the same height and look share one quad,
         * as long as the light of blocks is the same at all their corners: a quad only has it at its own. Stalks are left out, they draw
         * their own.
         */
        private void topFaces(int startX, int endX, int startZ, int endZ) {
            TileLight light = this.out.light;
            for (int cz = startZ; cz < endZ; cz++) {
                float z0 = this.z(cz);
                float z1 = this.z(cz + 1);
                int cx = startX;
                while (cx < endX) {
                    int index = cx + cz * CELLS;
                    if (this.firstDrawnApart(index)) {
                        cx++;
                        continue;
                    }
                    int first = this.firsts[index];
                    float top = this.visTops[first];
                    int paint = this.topPaints[first];
                    int end = cx + 1;
                    int north = 0;
                    int south = 0;
                    if (light != null) {
                        float x = this.x(cx);
                        north = light.at(x, top, z0);
                        south = light.at(x, top, z1);
                    }
                    while (end < endX) {
                        int next = end + cz * CELLS;
                        if (this.firstDrawnApart(next) || this.visTops[this.firsts[next]] != top || this.topPaints[this.firsts[next]] != paint) {
                            break;
                        }
                        if (light != null && !evenLight(light, this.x(end), this.x(end + 1), top, z0, z1, north, south)) {
                            break;
                        }
                        end++;
                    }
                    float x0 = this.x(cx);
                    float x1 = this.x(end);
                    this.out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, this.paints.get(paint), SHADE_TOP, KIND_HORIZONTAL);
                    cx = end;
                }
            }
        }

        /** Whether the light at both ends of the boundaries at {@code x} and {@code next} is that of the start, north and south. */
        private static boolean evenLight(TileLight light, float x, float next, float top, float z0, float z1, int north, int south) {
            return light.at(x, top, z0) == north && light.at(x, top, z1) == south && light.at(next, top, z0) == north && light.at(next, top, z1) == south;
        }

        /** Every face of the segments of one cell but the merged top of the first one. */
        private void cell(int cx, int cz) {
            int index = cx + cz * CELLS;
            int first = this.firsts[index];
            if (first < 0) {
                return;
            }
            DetailData data = this.data;
            QuadWriter out = this.out;
            int end = data.segOffsets[index] + data.segCounts[index];
            this.x0 = this.x(cx);
            this.x1 = this.x(cx + 1);
            this.z0 = this.z(cz);
            this.z1 = this.z(cz + 1);
            float x0 = this.x0;
            float x1 = this.x1;
            float z0 = this.z0;
            float z1 = this.z1;
            // Under the sea everything gets darker with depth like vanilla's, each block taking one level of the sky's light: the
            // ground under the water, and what lies under a ledge or a pocket of it too, measured from the surface of the column.
            out.lightWaterTop = NO_WATER;
            for (int s = first; s < end; s++) {
                if (this.water[this.block(s)] || this.wets[this.block(s)]) {
                    out.lightWaterTop = this.segTops[s];
                    break;
                }
            }
            int open = this.openDownTo(cx, cz);
            int firstTop = this.segTops[first] - 1;
            this.low = Math.min(this.low, firstTop);
            int plantTop = first > data.segOffsets[index] && this.kinds[this.block(first - 1)] == PLANT ? this.segTops[first - 1] - 1 : firstTop;
            this.high = Math.max(this.high, plantTop);
            // The side of the column down to its lowest neighbour is seen too, also next to another chunk: a bank over water there
            // may lie in a section vanilla leaves out while all its tops are in sections vanilla draws.
            this.low = Math.min(this.low, Math.max(this.firstRunBottoms[index], this.lowestNeighbour(cx, cz, firstTop)));
            Arrays.fill(this.pendingPaints, -1);
            // Water is a pocket under something else below the first segment that is not water (plants in it count as water).
            int firstDry = first;
            while (firstDry < end && (this.water[this.block(firstDry)] || this.wets[this.block(firstDry)])) {
                firstDry++;
            }
            for (int s = first; s < end; s++) {
                int block = this.block(s);
                byte kind = this.kinds[block];
                if (kind == AIR || kind == PLANT) {
                    continue;
                }
                out.pocket = s > firstDry;
                int skyLight = this.plugs[block] ? PLUG_LIGHT : 15;
                if (skyLight != out.sky) {
                    // Walls waiting to be joined with the ones under them are written with the light they were made under.
                    this.flushWalls();
                    out.sky = skyLight;
                }
                int top = this.segTops[s];
                int bottom = top - data.segHeights[s];
                float visible = this.visTops[s];
                int above = s > data.segOffsets[index] ? this.block(s - 1) : -1;
                boolean sky = above < 0 || open(this.kinds[above]);
                if (kind == STALK) {
                    this.flushWalls();
                    float centerX = (x0 + x1) * 0.5F;
                    float centerZ = (z0 + z1) * 0.5F;
                    column(out, centerX, centerZ, this.looks[block].columnWidth(), bottom, top, this.paints.get(this.sidePaints[s]),
                        this.paints.get(this.topPaints[s]));
                    continue;
                }
                if (s == first && this.isCap(s, end)) {
                    this.flushWalls();
                    float centerX = (x0 + x1) * 0.5F;
                    float centerZ = (z0 + z1) * 0.5F;
                    float half = CAP_SIZE * 0.5F;
                    Paint capSide = this.paints.get(this.sidePaints[s]);
                    for (int direction = 0; direction < 4; direction++) {
                        face(out, direction, centerX - half, centerX + half, centerZ - half, centerZ + half, bottom, bottom + CAP_SIZE, capSide, 1.0F);
                    }
                    float capTop = bottom + CAP_SIZE;
                    out.quad(centerX - half, capTop, centerZ - half, centerX - half, capTop, centerZ + half, centerX + half, capTop, centerZ + half,
                        centerX + half, capTop, centerZ - half, this.paints.get(this.topPaints[s]), SHADE_TOP, KIND_HORIZONTAL);
                    continue;
                }
                Paint topPaint = this.paints.get(this.topPaints[s]);
                Paint sidePaint = this.paints.get(this.sidePaints[s]);
                boolean liquid = kind == LIQUID;
                boolean shownTop = above < 0 || this.topOpen(above, block);
                if (shownTop && s != first) {
                    out.quad(x0, visible, z0, x0, visible, z1, x1, visible, z1, x1, visible, z0, topPaint, SHADE_TOP, KIND_HORIZONTAL);
                    // A lower top counts for vanilla where it can be seen from outside: a neighbour open to the sky down to it, or
                    // what lies on it seen through.
                    boolean throughAbove = above >= 0 && (this.kinds[above] == CLEAR || this.kinds[above] == LIQUID)
                        && (s - 1 == first || open <= this.segTops[s - 1]);
                    if (open <= top || throughAbove) {
                        this.low = Math.min(this.low, top - 1);
                    }
                }
                if (shownTop && this.water[block] && sky) {
                    // The surface of the water seen from under it, as vanilla draws it: as bright as from above.
                    out.quad(x0, visible, z0, x1, visible, z0, x1, visible, z1, x0, visible, z1, topPaint, SHADE_TOP, KIND_HORIZONTAL);
                }
                if (this.wets[block] && sky && visible < top - WATER_DIP) {
                    // The water over a thin block standing in it (a waterlogged slab, a sea pickle) fills the rest of its block, with the
                    // surface where vanilla's lies, seen from both sides.
                    float surface = top - WATER_DIP;
                    Paint wetPaint = this.paints.get(this.wetPaints[s]);
                    out.quad(x0, surface, z0, x0, surface, z1, x1, surface, z1, x1, surface, z0, wetPaint, SHADE_TOP, KIND_HORIZONTAL);
                    out.quad(x0, surface, z0, x1, surface, z0, x1, surface, z1, x0, surface, z1, wetPaint, SHADE_TOP, KIND_HORIZONTAL);
                }
                if (shownTop && topPaint.cutout() && visible - INNER_LAYER > bottom) {
                    float inner = visible - INNER_LAYER;
                    out.quad(x0, inner, z0, x0, inner, z1, x1, inner, z1, x1, inner, z0, topPaint.closed(), SHADE_TOP * INNER_SHADE, KIND_HORIZONTAL);
                }
                if (s + 1 < end && open(this.kinds[this.block(s + 1)])) {
                    if (liquid) {
                        // Water over a gap closes it with a floor. Where nothing is known under the water (chunks not fully generated) there
                        // is none: the rough water drawn under the detailed tiles shows there instead (see LodRenderer.UNDER_DETAIL_PIPELINE),
                        // as a stone floor would stand out grey through the water.
                        out.quad(x0, bottom, z0, x0, bottom, z1, x1, bottom, z1, x1, bottom, z0, this.paints.get(this.floorPaint), SHADE_TOP, KIND_HORIZONTAL);
                    } else {
                        out.quad(x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1, sidePaint, SHADE_BOTTOM, KIND_HORIZONTAL);
                        if (open <= bottom) {
                            this.low = Math.min(this.low, bottom);
                        }
                    }
                }
                boolean underWater = above >= 0 && this.water[above];
                this.sides(cx, cz, s, block, visible, bottom, top, this.sidePaints[s], liquid, underWater);
            }
            this.flushWalls();
            out.lightWaterTop = NO_WATER;
            out.pocket = false;
            out.sky = 15;
        }

        /** The side walls of one segment, wherever the cells next to it leave them open. */
        private void sides(int cx, int cz, int segment, int block, float top, int bottom, int segTop, int paint, boolean liquid, boolean underWater) {
            DetailData data = this.data;
            for (int direction = 0; direction < 4; direction++) {
                int nx = cx + DIRECTION_X[direction];
                int nz = cz + DIRECTION_Z[direction];
                // Cells of 2 or 4 blocks take their highest column; where vanilla draws the next chunk, its real ground at the border
                // may lie lower than that, and vanilla leaves the side open towards the solid blocks it knows are there.
                boolean otherChunk = this.cell > 1 && (nx * this.cell >> 4 != cx * this.cell >> 4 || nz * this.cell >> 4 != cz * this.cell >> 4);
                boolean outside = nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS;
                if (liquid && outside) {
                    // Walls of water would show through it as sheets; the water next door is level with it anyway.
                    continue;
                }
                if (!liquid && (outside || otherChunk)) {
                    // The neighbouring tile may be of another detail level, may not be drawn at all, or may be vanilla terrain, so its
                    // blocks cannot be relied on: border walls are always complete. Next to a drawn neighbour they stay hidden under it.
                    // The same holds for chunk borders of coarse tiles. Ground under translucent water would hang its border walls under
                    // the water like curtains; a short step is enough there, inside the tile down to the ground next door.
                    float wallBottom = bottom;
                    if (underWater && !outside) {
                        wallBottom = Math.max(bottom, segTop - UNKNOWN_WALL);
                        int neighbour = nx + nz * CELLS;
                        if (!data.isMissing(neighbour)) {
                            wallBottom = Math.max(wallBottom, this.groundUnderWater(neighbour) - (this.cell - 1));
                        }
                    }
                    this.wall(direction, wallBottom, top, paint);
                    continue;
                }
                int neighbour = nx + nz * CELLS;
                if (data.isMissing(neighbour) || data.segCounts[neighbour] == 0) {
                    // Without data next door the wall goes down to the end of the segment, as at the edge of the tile.
                    this.wall(direction, bottom, top, paint);
                    continue;
                }
                // The side of this segment minus everything the neighbour covers, going down. Below its last segment the neighbour goes on
                // to the bottom of the world.
                float cursor = top;
                int offset = data.segOffsets[neighbour];
                int end = offset + data.segCounts[neighbour];
                for (int s = offset; s < end && cursor > bottom; s++) {
                    int cover = this.block(s);
                    if (!this.hides(cover, block)) {
                        continue;
                    }
                    float coverTop = this.visTops[s];
                    if (coverTop < cursor) {
                        this.wall(direction, Math.max(bottom, coverTop), cursor, paint);
                    }
                    cursor = Math.min(cursor, this.segTops[s] - data.segHeights[s]);
                }
                if (cursor > bottom && !this.hides(this.block(end - 1), block)) {
                    this.wall(direction, bottom, cursor, paint);
                }
            }
        }

        /** The paint in the dim light of the cave plug (see DetailData#CAVE_PLUG). */
        private int dark(int paint) {
            Paint light = this.paints.get(paint);
            return this.paints.index(new Paint(ARGB.scaleRGB(light.color(), UNDERWATER_LIGHT[15 - PLUG_LIGHT]), light.sprite(), light.cutout(), light.water(), light.uShift()));
        }

        /** A wall of a paint between two heights; a wall of the same paint right on top of it in the same direction is continued. */
        private void wall(int direction, float bottom, float top, int paint) {
            if (bottom >= top) {
                return;
            }
            if (this.paints.get(paint).water()) {
                DetailMeshBuilder.wall(this.out, direction, this.x0, this.x1, this.z0, this.z1, bottom, top, this.paints.get(paint));
                return;
            }
            if (this.pendingPaints[direction] == paint && this.pendingBottoms[direction] == top) {
                this.pendingBottoms[direction] = bottom;
                return;
            }
            this.flushWall(direction);
            this.pendingPaints[direction] = paint;
            this.pendingTops[direction] = top;
            this.pendingBottoms[direction] = bottom;
        }

        private void flushWall(int direction) {
            int paint = this.pendingPaints[direction];
            if (paint >= 0) {
                DetailMeshBuilder.wall(this.out, direction, this.x0, this.x1, this.z0, this.z1, this.pendingBottoms[direction], this.pendingTops[direction],
                    this.paints.get(paint));
                this.pendingPaints[direction] = -1;
            }
        }

        private void flushWalls() {
            for (int direction = 0; direction < 4; direction++) {
                this.flushWall(direction);
            }
        }

        /** Top of the first segment of a cell that is neither water nor a stalk: the ground, under the water if there is any. */
        private int groundUnderWater(int index) {
            int offset = this.data.segOffsets[index];
            int end = offset + this.data.segCounts[index];
            for (int s = offset; s < end; s++) {
                byte kind = this.kinds[this.block(s)];
                if (kind == SOLID || kind == CLEAR) {
                    return this.segTops[s];
                }
            }
            return Integer.MIN_VALUE / 2;
        }

        /**
         * Lowest block of a column's side that its four neighbours leave open, at most its top. Next to a neighbour outside the tile or
         * without data the side counts as open down as far as border walls are drawn.
         */
        private int lowestNeighbour(int cx, int cz, int top) {
            int index = cx + cz * CELLS;
            int lowest = top;
            for (int direction = 0; direction < 4; direction++) {
                int nx = cx + DIRECTION_X[direction];
                int nz = cz + DIRECTION_Z[direction];
                if (nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS || this.data.isMissing(nx + nz * CELLS)) {
                    lowest = Math.min(lowest, this.firstRunBottoms[index]);
                    continue;
                }
                int neighbour = nx + nz * CELLS;
                if (this.firsts[neighbour] >= 0) {
                    lowest = Math.min(lowest, this.firstTops[neighbour]);
                }
            }
            return lowest;
        }

        /** Lowest height down to which one of the four neighbours of a cell is open to the sky; neighbours outside the tile do not count. */
        private int openDownTo(int cx, int cz) {
            int open = Integer.MAX_VALUE;
            for (int direction = 0; direction < 4; direction++) {
                int nx = cx + DIRECTION_X[direction];
                int nz = cz + DIRECTION_Z[direction];
                if (nx < 0 || nz < 0 || nx >= CELLS || nz >= CELLS) {
                    continue;
                }
                int neighbour = nx + nz * CELLS;
                if (this.data.isMissing(neighbour)) {
                    continue;
                }
                open = Math.min(open, this.firsts[neighbour] < 0 ? Integer.MIN_VALUE : this.firstTops[neighbour]);
            }
            return open;
        }

        /**
         * The plants of a cell as two crossed planes, seen from both sides, the way vanilla draws grass and flowers: as tall as their
         * stack, plants two blocks tall with their lower and upper half.
         */
        /** The plants of a cell, or with {@code glowing} the glowing things in its water (see {@link #glows}). */
        private void plants(int cx, int cz, boolean glowing) {
            int index = cx + cz * CELLS;
            int count = this.data.segCounts[index];
            if (count == 0) {
                return;
            }
            float x0 = this.x(cx);
            float x1 = this.x(cx + 1);
            float z0 = this.z(cz);
            float z1 = this.z(cz + 1);
            int offset = this.data.segOffsets[index];
            // Plants under water darken with depth like the ground there, see QuadWriter.lit.
            for (int s = offset; s < offset + count; s++) {
                if (this.water[this.block(s)] || this.wets[this.block(s)]) {
                    this.out.lightWaterTop = this.segTops[s];
                    break;
                }
            }
            for (int s = offset; s < offset + count; s++) {
                boolean submerged = this.plantPaints[s] >= 0;
                int glow = this.glows[this.block(s)];
                if (glowing != glow > 0 || !submerged && (this.kinds[this.block(s)] != PLANT || this.topPaints[s] < 0)) {
                    continue;
                }
                this.out.blockLight = glow;
                // A plant floating over nothing (the last segment goes on to the bottom of the world) is not drawn.
                if (s + 1 >= offset + count) {
                    continue;
                }
                int height = this.data.segHeights[s];
                float bottom = this.segTops[s] - height;
                // Under water the plant stops at the surface, which lies a little lower than the top of its block.
                float top = submerged ? this.visTops[s] : this.segTops[s];
                Paint plant = this.paints.get(submerged ? this.plantPaints[s] : this.topPaints[s]);
                int upper = submerged ? this.plantUpperPaints[s] : this.sidePaints[s];
                if (upper >= 0 && height == 2) {
                    cross(this.out, x0, x1, z0, z1, bottom, bottom + 1, plant);
                    cross(this.out, x0, x1, z0, z1, bottom + 1, top, this.paints.get(upper));
                } else {
                    cross(this.out, x0, x1, z0, z1, bottom, top, plant);
                }
            }
            this.out.lightWaterTop = NO_WATER;
            this.out.blockLight = 0;
        }
    }

    /** The paint of a plant: its texture with gaps, or its color. */
    private static Paint plantPaint(
        BlockLooks.Face face, BlockLooks.Look look, BlockDetail detail, @Nullable Holder<Biome> biome, int x, int y, int z, BlockLooks.Blend around
    ) {
        Paint paint = paint(face, look.state(), false, detail, biome, x, y, z, around, false);
        return new Paint(paint.color(), paint.sprite(), true);
    }

    /** Two crossed planes between two heights, each seen from both sides. */
    private static void cross(QuadWriter out, float x0, float x1, float z0, float z1, float y0, float y1, Paint plant) {
        out.quad(x0, y0, z0, x0, y1, z0, x1, y1, z1, x1, y0, z1, plant, SHADE_TOP, KIND_PLANT);
        out.quad(x1, y0, z1, x1, y1, z1, x0, y1, z0, x0, y0, z0, plant, SHADE_TOP, KIND_PLANT);
        out.quad(x0, y0, z1, x0, y1, z1, x1, y1, z0, x1, y0, z0, plant, SHADE_TOP, KIND_PLANT);
        out.quad(x1, y0, z0, x1, y1, z0, x0, y1, z1, x0, y0, z1, plant, SHADE_TOP, KIND_PLANT);
    }

    /**
     * A stalk (bamboo, chorus plant) as an eight-sided column standing in the middle of its cell, as thick as vanilla's. Its sides
     * are mapped by their position along x or z like every other face, with the texture shifted so the stalk drawn on it is centered on
     * the column. The top is the flat color of the top block.
     */
    private static void column(QuadWriter out, float centerX, float centerZ, float width, float bottom, float top, Paint side, Paint cap) {
        float r = width * 0.5F;
        float s = r * (float) (Math.sqrt(2.0) - 1.0);
        // The corners going round from the north-west, the order in which every side faces outwards.
        float[] xs = {-s, s, r, r, s, -s, -r, -r};
        float[] zs = {-r, -r, -s, s, r, r, s, -s};
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) & 7;
            float ax = centerX + xs[i];
            float az = centerZ + zs[i];
            float bx = centerX + xs[j];
            float bz = centerZ + zs[j];
            boolean eastWest = i == 2 || i == 6;
            float shade = i % 2 == 1 ? (SHADE_NORTH_SOUTH + SHADE_EAST_WEST) * 0.5F : eastWest ? SHADE_EAST_WEST : SHADE_NORTH_SOUTH;
            out.quad(ax, bottom, az, ax, top, az, bx, top, bz, bx, bottom, bz, side, shade, eastWest ? KIND_EAST_WEST : KIND_NORTH_SOUTH);
        }
        out.quad(centerX - s, top, centerZ - r, centerX - s, top, centerZ + r, centerX + s, top, centerZ + r, centerX + s, top, centerZ - r, cap,
            SHADE_TOP, KIND_HORIZONTAL);
        out.quad(centerX - r, top, centerZ - s, centerX - r, top, centerZ + s, centerX - s, top, centerZ + r, centerX - s, top, centerZ - r, cap,
            SHADE_TOP, KIND_HORIZONTAL);
        out.quad(centerX + s, top, centerZ - r, centerX + s, top, centerZ + r, centerX + r, top, centerZ + s, centerX + r, top, centerZ - s, cap,
            SHADE_TOP, KIND_HORIZONTAL);
    }

    /** A wall of a run between two heights: its top block looks like the side of the block on top, the rest like the side block. */
    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, int runTop, Paint edge, Paint side) {
        if (edge.equals(side)) {
            wall(out, direction, x0, x1, z0, z1, bottom, top, edge);
            return;
        }
        float split = Mth.clamp(runTop - 1, bottom, top);
        wall(out, direction, x0, x1, z0, z1, split, top, edge);
        wall(out, direction, x0, x1, z0, z1, bottom, split, side);
    }

    private static void wall(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, Paint paint) {
        if (bottom >= top) {
            return;
        }
        face(out, direction, x0, x1, z0, z1, bottom, top, paint, 1.0F);
        if (paint.overlay() != null) {
            // The grass edge lies over the top block of the wall, in the same plane and drawn after it.
            face(out, direction, x0, x1, z0, z1, Math.max(bottom, (float) Math.ceil(top) - 1.0F), top, paint.overlay(), 1.0F);
        }
        if (paint.cutout()) {
            float inset = Math.min(INNER_LAYER, Math.max(x1 - x0, z1 - z0));
            switch (direction) {
                case 0 -> face(out, direction, x0, x1, z0 + inset, z1, bottom, top, paint.closed(), INNER_SHADE);
                case 1 -> face(out, direction, x0, x1, z0, z1 - inset, bottom, top, paint.closed(), INNER_SHADE);
                case 2 -> face(out, direction, x0 + inset, x1, z0, z1, bottom, top, paint.closed(), INNER_SHADE);
                default -> face(out, direction, x0, x1 - inset, z0, z1, bottom, top, paint.closed(), INNER_SHADE);
            }
        }
    }

    /** One side face of a box, on its side in the given direction. */
    private static void face(QuadWriter out, int direction, float x0, float x1, float z0, float z1, float bottom, float top, Paint paint, float shade) {
        switch (direction) {
            case 0 -> out.quad(x0, bottom, z0, x0, top, z0, x1, top, z0, x1, bottom, z0, paint, SHADE_NORTH_SOUTH * shade, KIND_NORTH_SOUTH);
            case 1 -> out.quad(x0, bottom, z1, x1, bottom, z1, x1, top, z1, x0, top, z1, paint, SHADE_NORTH_SOUTH * shade, KIND_NORTH_SOUTH);
            case 2 -> out.quad(x0, bottom, z0, x0, bottom, z1, x0, top, z1, x0, top, z0, paint, SHADE_EAST_WEST * shade, KIND_EAST_WEST);
            default -> out.quad(x1, bottom, z0, x1, top, z0, x1, top, z1, x1, bottom, z1, paint, SHADE_EAST_WEST * shade, KIND_EAST_WEST);
        }
    }

    /**
     * One bit per 64-block square of the tile, set when every cell in it has real data. Only those squares replace the rough terrain;
     * the rest keeps showing it.
     */
    static long coverage(DetailData data, int cell) {
        int squares = cell;
        int cellsPerSquare = CELLS / squares;
        long mask = 0L;
        for (int sz = 0; sz < squares; sz++) {
            for (int sx = 0; sx < squares; sx++) {
                boolean complete = true;
                for (int cz = sz * cellsPerSquare; complete && cz < (sz + 1) * cellsPerSquare; cz++) {
                    for (int cx = sx * cellsPerSquare; cx < (sx + 1) * cellsPerSquare; cx++) {
                        if (data.isMissing(cx + cz * CELLS)) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (complete) {
                    mask |= 1L << (sx + sz * squares);
                }
            }
        }
        return mask;
    }

    /** The different paints of one tile, so equal neighbouring faces can be found by comparing numbers. */
    private static final class Paints {
        private final List<Paint> list = new ArrayList<>();
        private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Paint> indices = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();

        Paints() {
            this.indices.defaultReturnValue(-1);
        }

        int index(Paint paint) {
            int index = this.indices.getInt(paint);
            if (index < 0) {
                index = this.list.size();
                this.list.add(paint);
                this.indices.put(paint, index);
            }
            return index;
        }

        Paint get(int index) {
            return this.list.get(index);
        }
    }

    /**
     * Growable vertex buffer, four vertices per quad. Flat colors: position and color. Textured: position, color, the corner of the
     * sprite in the atlas and its size in texels; the shader repeats the sprite over every block of a face.
     */
    /**
     * How bright ground lies under this many blocks of water, as vanilla draws it with the default brightness setting: the sky's light
     * loses a level per block of water, and vanilla's light curve turns the level into brightness. Never quite black, like vanilla's
     * darkest light.
     */
    static float underwaterLight(int depth) {
        float level = Math.clamp(15 - depth, 0, 15) / 15.0F;
        float curve = level / (4.0F - 3.0F * level);
        float lifted = 1.0F - (1.0F - curve) * (1.0F - curve) * (1.0F - curve) * (1.0F - curve);
        float brightness = (curve + lifted) * 0.5F;
        return DARKEST_LIGHT + (1.0F - DARKEST_LIGHT) * brightness;
    }

    private static final float DARKEST_LIGHT = 0.05F;
    /** The sky's light in the cave plug (see DetailData#CAVE_PLUG): dim, but not black, so caves keep their shape. */
    private static final int PLUG_LIGHT = 4;
    /** How far under the top of its block vanilla draws the surface of water with nothing over it. */
    private static final float WATER_DIP = 1.0F / 9.0F;
    /** {@link #underwaterLight} for every depth until it stops changing. */
    /** How bright things are by how deep under the water they lie, in blocks; also used by VanillaWall. */
    static final float[] UNDERWATER_LIGHT = new float[16];
    private static final int NO_WATER = Integer.MIN_VALUE;
    /** Sprite sizes take the low 12 bits of their vertex field, see QuadWriter#vertex. */
    private static final int MAX_SPRITE_SIZE = 4095;

    static {
        for (int depth = 0; depth < UNDERWATER_LIGHT.length; depth++) {
            UNDERWATER_LIGHT[depth] = underwaterLight(depth);
        }
    }

    private static final class QuadWriter {
        private final boolean textured;
        private final int vertexSize;
        /** One growing buffer per way faces can face, see {@link TileMesh#GROUPS}. */
        private final ByteBuffer[] buffers;
        private ByteBuffer buffer;
        private final int[] counts = new int[TileMesh.GROUPS];
        private int chunks = 1;
        /** Quads of each group before each chunk, group by group. */
        private int[] chunkStarts = new int[TileMesh.GROUPS];
        /** Quads of {@link TileMesh#GROUP_OTHER} before the plants of each chunk. */
        private int[] plantStarts = new int[2];
        int quads;
        /**
         * Top of the first water of the column for the light under it, {@link #NO_WATER} without, also under a cover that is not water,
         * like the ice over a frozen sea: each corner gets the light of the water at its height (see underwaterLight and {@link #lit}),
         * so a tall pillar under water darkens downwards like vanilla's instead of taking the light of its top.
         */
        int lightWaterTop = NO_WATER;
        /** The sky's light over the next quads, 0 to 15: full in the open, dim in the cave plug (see DetailData#CAVE_PLUG). */
        int sky = 15;
        /** The light of blocks (sea pickles, torches) over the next quads, 0 to 15. */
        int blockLight;
        /** The light of blocks over the tile, null for none; each corner takes the brighter of it and {@link #blockLight}. */
        @Nullable TileLight light;
        /** The next quads are of a run under the top one: its water is a pocket under something else, not the surface. */
        boolean pocket;
        /** Some face has a texture with gaps. */
        boolean cutout;
        /** Some face is translucent water, so the tile takes part in the water passes. */
        boolean water;
        /** Lowest and highest corner written. */
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        /**
         * The growing buffers quads are written to, kept per mesh builder thread: building every tile in fresh ones that double a few
         * times would leave megabytes of native memory behind per tile until the garbage collector frees it.
         */
        private static final ThreadLocal<ByteBuffer[]> SCRATCH = ThreadLocal.withInitial(() -> {
            ByteBuffer[] buffers = new ByteBuffer[TileMesh.GROUPS];
            for (int g = 0; g < buffers.length; g++) {
                buffers[g] = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
            }
            return buffers;
        });

        QuadWriter(boolean textured) {
            this.textured = textured;
            this.vertexSize = textured ? TEXTURED_VERTEX_SIZE : GeneralMeshFactory.VERTEX_SIZE;
            this.buffers = SCRATCH.get();
            for (ByteBuffer group : this.buffers) {
                group.clear();
            }
            this.buffer = this.buffers[0];
        }

        /** The color of a corner at a height under the water, see {@link #lightWaterTop}. */
        private int lit(Paint paint, float shade, float y) {
            int depth = Math.clamp(this.lightWaterTop - (int) Math.floor(y), 0, UNDERWATER_LIGHT.length - 1);
            return ARGB.scaleRGB(paint.color(), shade * UNDERWATER_LIGHT[depth]);
        }

        void chunks(int count) {
            this.chunks = count;
            this.chunkStarts = new int[TileMesh.GROUPS * count];
            this.plantStarts = new int[count * 2];
        }

        void startChunk(int chunk) {
            for (int g = 0; g < TileMesh.GROUPS; g++) {
                this.chunkStarts[g * this.chunks + chunk] = this.counts[g];
            }
        }

        void startGlows(int chunk) {
            this.plantStarts[chunk * 2] = this.counts[TileMesh.GROUP_OTHER];
        }

        void startPlants(int chunk) {
            this.plantStarts[chunk * 2 + 1] = this.counts[TileMesh.GROUP_OTHER];
        }

        /** The way a quad faces, from its first three corners, which go round counterclockwise seen from the front. */
        private static int group(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
            float ux = bx - ax;
            float uy = by - ay;
            float uz = bz - az;
            float vx = cx - ax;
            float vy = cy - ay;
            float vz = cz - az;
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float length = Math.abs(nx) + Math.abs(ny) + Math.abs(nz);
            float tolerance = length * 1.0E-4F;
            if (Math.abs(nx) <= tolerance && Math.abs(nz) <= tolerance) {
                return ny > 0.0F ? TileMesh.GROUP_UP : TileMesh.GROUP_DOWN;
            }
            if (Math.abs(ny) <= tolerance && Math.abs(nx) <= tolerance) {
                return nz > 0.0F ? TileMesh.GROUP_SOUTH : TileMesh.GROUP_NORTH;
            }
            if (Math.abs(ny) <= tolerance && Math.abs(nz) <= tolerance) {
                return nx > 0.0F ? TileMesh.GROUP_EAST : TileMesh.GROUP_WEST;
            }
            return TileMesh.GROUP_OTHER;
        }

        void quad(
            float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, Paint paint, float shade,
            int kind
        ) {
            // Translucent water goes into a group of its own, the only one the water passes draw.
            int group = paint.water() ? TileMesh.GROUP_WATER : group(ax, ay, az, bx, by, bz, cx, cy, cz);
            this.buffer = this.buffers[group];
            if (this.buffer.remaining() < 4 * this.vertexSize) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(this.buffer.capacity() * 2).order(ByteOrder.nativeOrder());
                this.buffer.flip();
                bigger.put(this.buffer);
                this.buffer = bigger;
                this.buffers[group] = bigger;
            }
            this.counts[group]++;
            this.minY = Math.min(this.minY, Math.min(Math.min(ay, by), Math.min(cy, dy)));
            this.maxY = Math.max(this.maxY, Math.max(Math.max(ay, by), Math.max(cy, dy)));
            // Water lower down a column with water on top is a pocket under something else.
            boolean surface = paint.water() && !this.pocket;
            int water = paint.water() ? TRANSLUCENT_WATER : 0;
            int alpha = 255 - (kind | (paint.cutout() ? CUTOUT : 0) | water);
            this.cutout |= paint.cutout();
            this.water |= paint.water();
            // Blocks and pockets get darker under the water; the surface water keeps its color. With textures the sky light goes to the
            // shader, which lights it from vanilla's lightmap; flat colors keep it in the color.
            if (this.lightWaterTop == NO_WATER || surface) {
                int color = ARGB.scaleRGB(paint.color(), shade);
                this.vertex(ax, ay, az, color, alpha, this.sky, paint);
                this.vertex(bx, by, bz, color, alpha, this.sky, paint);
                this.vertex(cx, cy, cz, color, alpha, this.sky, paint);
                this.vertex(dx, dy, dz, color, alpha, this.sky, paint);
            } else if (this.textured) {
                int color = ARGB.scaleRGB(paint.color(), shade);
                this.vertex(ax, ay, az, color, alpha, this.skyUnderWater(ay), paint);
                this.vertex(bx, by, bz, color, alpha, this.skyUnderWater(by), paint);
                this.vertex(cx, cy, cz, color, alpha, this.skyUnderWater(cy), paint);
                this.vertex(dx, dy, dz, color, alpha, this.skyUnderWater(dy), paint);
            } else {
                this.vertex(ax, ay, az, this.lit(paint, shade, ay), alpha, 15, paint);
                this.vertex(bx, by, bz, this.lit(paint, shade, by), alpha, 15, paint);
                this.vertex(cx, cy, cz, this.lit(paint, shade, cy), alpha, 15, paint);
                this.vertex(dx, dy, dz, this.lit(paint, shade, dy), alpha, 15, paint);
            }
            this.quads++;
        }

        /** The sky's light at a height under the water, as vanilla's: one level less per block of water over it. */
        private int skyUnderWater(float y) {
            return Math.min(this.sky, Math.clamp(15 - (this.lightWaterTop - (int) Math.floor(y)), 0, 15));
        }

        private void vertex(float x, float y, float z, int rgb, int alpha, int sky, Paint paint) {
            TextureAtlasSprite sprite = paint.sprite();
            if (!this.textured) {
                this.buffer.putFloat(x).putFloat(y).putFloat(z);
                this.buffer.put((byte) ARGB.red(rgb)).put((byte) ARGB.green(rgb)).put((byte) ARGB.blue(rgb)).put((byte) 255);
                return;
            }
            // A flat color has the sprite size 0, which the shader recognizes. Sprites are square (one frame of an animated one), so one
            // size serves both directions.
            // The block light rides over the sprite size, the sky light in the high bits of the face code (see the vertex shader).
            int size = sprite == null ? 0 : Math.min(sprite.contents().width(), MAX_SPRITE_SIZE);
            int block = this.light == null ? this.blockLight : Math.max(this.blockLight, this.light.at(x, y, z));
            this.buffer.putShort((short) Math.round(x * XZ_UNITS)).putShort((short) Math.round(y * Y_UNITS)).putShort((short) Math.round(z * XZ_UNITS))
                .putShort((short) (size | block << 12));
            this.buffer.put((byte) ARGB.red(rgb)).put((byte) ARGB.green(rgb)).put((byte) ARGB.blue(rgb)).put((byte) (alpha - ((15 - sky) << 4)));
            if (sprite == null) {
                this.buffer.putShort((short) 0).putShort((short) 0);
            } else {
                // The corner comes from the texture coordinates: getX and getY include the padding around the sprite in the atlas.
                double atlasWidth = sprite.contents().width() / (double) (sprite.getU1() - sprite.getU0());
                double atlasHeight = sprite.contents().height() / (double) (sprite.getV1() - sprite.getV0());
                this.buffer.putShort((short) Math.round((sprite.getU0() + paint.uShift()) * atlasWidth * 2.0))
                    .putShort((short) Math.round(sprite.getV0() * atlasHeight * 2.0));
            }
        }

        /** The quads written, group after group, in a buffer of their own: the scratch buffers are used again by the next tile. */
        ByteBuffer finish() {
            int bytes = 0;
            for (ByteBuffer group : this.buffers) {
                bytes += group.position();
            }
            ByteBuffer result = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            for (ByteBuffer group : this.buffers) {
                group.flip();
                result.put(group);
            }
            result.flip();
            return result;
        }

        /** First quad of every chunk in every group, group by group, plus the total; see {@link TileMesh#segments}. */
        int[] segments() {
            int[] segments = new int[TileMesh.GROUPS * this.chunks + 1];
            int base = 0;
            for (int g = 0; g < TileMesh.GROUPS; g++) {
                for (int c = 0; c < this.chunks; c++) {
                    segments[g * this.chunks + c] = base + this.chunkStarts[g * this.chunks + c];
                }
                base += this.counts[g];
            }
            segments[segments.length - 1] = base;
            return segments;
        }

        /**
         * First quad of the glowing things and of the plants of every chunk, two per chunk, which come last in its part of
         * {@link TileMesh#GROUP_OTHER}.
         */
        int[] plantStarts() {
            int base = 0;
            for (int g = 0; g < TileMesh.GROUP_OTHER; g++) {
                base += this.counts[g];
            }
            int[] starts = new int[this.chunks * 2];
            for (int c = 0; c < this.chunks * 2; c++) {
                starts[c] = base + this.plantStarts[c];
            }
            return starts;
        }
    }
}
