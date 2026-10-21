package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.mixin.client.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * How a block looks, taken from its vanilla model once and shared by every detailed tile: the textures of its top and its sides, their
 * average colors, whether they are tinted by the biome (grass, leaves, water) and whether they have holes (leaves). Resource packs and
 * modded blocks are picked up the same way vanilla draws them. Built lazily from the mesh threads; models are only read.
 */
final class BlockLooks {
    private static final Map<Identifier, Look> LOOKS = new ConcurrentHashMap<>();
    private static volatile @Nullable Object models;

    private BlockLooks() {
    }

    /**
     * One face of a block: its texture, the average color of the texture and the tint vanilla puts over it. Some faces have a second,
     * tinted layer over part of them (the grass edge on the side of a grass block), which covers {@code overlayCoverage} of the face.
     */
    record Face(
        @Nullable TextureAtlasSprite sprite, int average, @Nullable BlockTintSource tint, boolean cutout,
        int overlayAverage, @Nullable BlockTintSource overlayTint, float overlayCoverage
    ) {
        static Face of(@Nullable TextureAtlasSprite sprite, int average, @Nullable BlockTintSource tint, boolean cutout) {
            return new Face(sprite, average, tint, cutout, 0, null, 0.0F);
        }

        /** The average color of the face at a place, with the tint of the biome there. */
        int color(BlockState state, @Nullable Holder<Biome> biome, int x, int y, int z) {
            int color = this.tint == null ? this.average : ARGB.multiply(this.average, tintAt(this.tint, state, biome, x, y, z));
            if (this.overlayCoverage <= 0.0F) {
                return color;
            }
            int overlay = this.overlayTint == null ? this.overlayAverage : ARGB.multiply(this.overlayAverage, tintAt(this.overlayTint, state, biome, x, y, z));
            return ARGB.srgbLerp(this.overlayCoverage, color, overlay);
        }

        /** Only the tint, for textured faces where the texture brings the rest. */
        int tintColor(BlockState state, @Nullable Holder<Biome> biome, int x, int y, int z) {
            return this.tint == null ? 0xFFFFFFFF : ARGB.opaque(tintAt(this.tint, state, biome, x, y, z));
        }

        /** The average color of the face at a place, with the tint blended between the biomes around it, see {@link Blend}. */
        int colorBlended(BlockState state, Blend blend, int y) {
            int color = this.tint == null ? this.average : ARGB.multiply(this.average, tintAtBlended(this.tint, state, blend, y));
            if (this.overlayCoverage <= 0.0F) {
                return color;
            }
            int overlay = this.overlayTint == null ? this.overlayAverage : ARGB.multiply(this.overlayAverage, tintAtBlended(this.overlayTint, state, blend, y));
            return ARGB.srgbLerp(this.overlayCoverage, color, overlay);
        }

        /** Only the tint, blended between the biomes around the place, for textured faces. */
        int tintColorBlended(BlockState state, Blend blend, int y) {
            return this.tint == null ? 0xFFFFFFFF : ARGB.opaque(tintAtBlended(this.tint, state, blend, y));
        }
    }

    /**
     * The faces of a block that detailed tiles draw. {@code plant} is the face of a plant drawn as crossed planes. Thin stalks
     * (bamboo, chorus plants) are drawn as eight-sided columns {@code columnWidth} blocks thick, 0 for every other block; their side
     * texture is centered on {@code columnU}, the middle of the stalk on the atlas.
     */
    record Look(BlockState state, Face top, Face side, Face plant, boolean fluid, float columnWidth, float columnU) {
        Look(BlockState state, Face top, Face side, Face plant, boolean fluid) {
            this(state, top, side, plant, fluid, 0.0F, 0.0F);
        }
    }

    /** Thickness of the stalk of the blocks drawn as columns, the same as vanilla's: bamboo 3 pixels, chorus plants 10. */
    private static float columnWidth(BlockState state) {
        if (state.getBlock() instanceof BambooStalkBlock) {
            return 3.0F / 16.0F;
        }
        if (state.getBlock() instanceof ChorusPlantBlock) {
            return 10.0F / 16.0F;
        }
        return 0.0F;
    }

    static Look get(Identifier id) {
        Object current = currentModels();
        if (current != models) {
            // Resource packs were reloaded: textures and models changed.
            LOOKS.clear();
            models = current;
        }
        Look look = LOOKS.get(id);
        if (look == null) {
            look = compute(id);
            LOOKS.put(id, look);
        }
        return look;
    }

    private static @Nullable Object currentModels() {
        try {
            return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Look compute(Identifier id) {
        BlockState state = BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.STONE).defaultBlockState();
        // Blocks that show nothing but their liquid (a bubble column) look like that liquid; older summaries still have them.
        if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE && !state.getFluidState().isEmpty()) {
            state = state.getFluidState().createLegacyBlock();
        }
        int mapColor = ARGB.opaque(state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col);
        Face fallback = Face.of(null, mapColor, null, false);
        boolean fluid = !state.getFluidState().isEmpty() && state.getBlock() == state.getFluidState().createLegacyBlock().getBlock();
        try {
            BlockStateModelSet set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            BlockStateModel model = set.get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            BlockTintSource firstTint = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
            TextureAtlasSprite particle = model.particleMaterial().sprite();
            Face particleFace = face(particle, fluid ? firstTint : null, mapColor);
            if (fluid) {
                return new Look(state, particleFace, particleFace, particleFace, true);
            }
            Face top = face(parts, state, Direction.UP, mapColor);
            Face side = face(parts, state, Direction.NORTH, mapColor);
            Face plant = face(parts, state, null, mapColor);
            float columnWidth = columnWidth(state);
            BakedQuad stalk = columnWidth > 0.0F ? firstQuad(parts, Direction.NORTH) : null;
            float columnU = 0.0F;
            if (stalk != null) {
                float minU = Float.MAX_VALUE;
                float maxU = -Float.MAX_VALUE;
                for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                    float u = UVPair.unpackU(stalk.packedUV(vertex));
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                }
                columnU = (minU + maxU) * 0.5F;
                side = face(stalk.materialInfo().sprite(), null, mapColor);
            }
            return new Look(
                state, top != null ? top : particleFace, side != null ? side : particleFace, plant != null ? plant : particleFace, false,
                stalk != null ? columnWidth : 0.0F, columnU
            );
        } catch (RuntimeException e) {
            return new Look(state, fallback, fallback, fallback, fluid);
        }
    }

    /**
     * The face of a model pointing a way, or any face for {@code null}. A grass block has its side twice: dirt, and the grass edge
     * tinted over it; the edge is kept, it is what tells grass from dirt.
     */
    private static @Nullable Face face(List<BlockStateModelPart> parts, BlockState state, @Nullable Direction direction, int fallback) {
        BakedQuad base = null;
        BakedQuad overlay = null;
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(direction == null ? List.of() : part.getQuads(direction));
            quads.addAll(part.getQuads(null));
            for (BakedQuad quad : quads) {
                if (direction != null && quad.direction() != direction) {
                    continue;
                }
                if (base == null) {
                    base = quad;
                } else if (overlay == null && quad.materialInfo().isTinted() && !base.materialInfo().isTinted()) {
                    overlay = quad;
                }
            }
        }
        if (base == null) {
            return null;
        }
        BlockTintSource tint = null;
        BakedQuad tinted = overlay != null ? overlay : base;
        if (tinted.materialInfo().isTinted()) {
            tint = Minecraft.getInstance().getBlockColors().getTintSource(state, tinted.materialInfo().tintIndex());
        }
        if (overlay == null) {
            return face(base.materialInfo().sprite(), tint, fallback);
        }
        Face dirt = face(base.materialInfo().sprite(), null, fallback);
        int[] edge = averageColor(overlay.materialInfo().sprite());
        if (edge == null) {
            return dirt;
        }
        return new Face(dirt.sprite(), dirt.average(), null, dirt.cutout(), edge[0], tint, edge[2] / 1000.0F);
    }

    private static @Nullable BakedQuad firstQuad(List<BlockStateModelPart> parts, Direction direction) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = new ArrayList<>(part.getQuads(direction));
            quads.addAll(part.getQuads(null));
            for (BakedQuad quad : quads) {
                if (quad.direction() == direction) {
                    return quad;
                }
            }
        }
        return null;
    }

    private static Face face(TextureAtlasSprite sprite, @Nullable BlockTintSource tint, int fallback) {
        int[] average = averageColor(sprite);
        return Face.of(sprite, average == null ? fallback : average[0], tint, average != null && average[1] != 0);
    }

    /**
     * Average color of the opaque pixels of the first frame, whether some pixels are transparent and the share of opaque pixels in
     * thousandths; null when it cannot be read.
     */
    private static int @Nullable [] averageColor(TextureAtlasSprite sprite) {
        try {
            SpriteContents contents = sprite.contents();
            NativeImage image = ((SpriteContentsAccessor) (Object) contents).farplane$originalImage();
            if (image == null || image.isClosed()) {
                return null;
            }
            int width = Math.min(contents.width(), image.getWidth());
            int height = Math.min(contents.height(), image.getHeight());
            long red = 0;
            long green = 0;
            long blue = 0;
            int count = 0;
            boolean holes = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixel(x, y);
                    if (ARGB.alpha(pixel) < 128) {
                        holes = true;
                        continue;
                    }
                    red += ARGB.red(pixel);
                    green += ARGB.green(pixel);
                    blue += ARGB.blue(pixel);
                    count++;
                }
            }
            if (count == 0) {
                return null;
            }
            int coverage = (int) (count * 1000L / Math.max(1, width * height));
            return new int[] {ARGB.color(255, (int) (red / count), (int) (green / count), (int) (blue / count)), holes ? 1 : 0, coverage};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The tint of a block in a biome at a place. Remembered per mesh builder thread for squares of {@link #TINT_CELL} blocks: vanilla's
     * tints only change with the biome, apart from the patches of swamp grass, which are much bigger than that.
     */
    private static int tintAt(BlockTintSource tint, BlockState state, @Nullable Holder<Biome> biome, int x, int y, int z) {
        Map<TintKey, Integer> cache = TINTS.get();
        TintKey key = new TintKey(tint, state, biome, x >> TINT_CELL, z >> TINT_CELL);
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        int color;
        try {
            color = tint.colorInWorld(state, new TintGetter(biome), new BlockPos(x, y, z));
        } catch (RuntimeException e) {
            color = -1;
        }
        if (cache.size() >= MAX_TINTS) {
            cache.clear();
        }
        cache.put(key, color);
        return color;
    }

    /**
     * The tint of a block at a place, blended between the four squares of biome around it (see {@link Blend}). The tints themselves
     * are blended, as they are multipliers; the color of the face is multiplied with the result once, afterwards.
     */
    private static int tintAtBlended(BlockTintSource tint, BlockState state, Blend blend, int y) {
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        for (int i = 0; i < 4; i++) {
            float weight = blend.weights[i];
            if (weight <= 0.0F) {
                continue;
            }
            int sample = tintAt(tint, state, blend.biomes[i], blend.xs[i], y, blend.zs[i]);
            red += weight * ARGB.red(sample);
            green += weight * ARGB.green(sample);
            blue += weight * ARGB.blue(sample);
        }
        return ARGB.color(255, Math.round(red), Math.round(green), Math.round(blue));
    }

    /**
     * Where colors are blended from at a place. Vanilla keeps biomes in squares of 4 blocks, and every color changes in a step at
     * their borders unless it is blended. Like vanilla's biome blend, each place takes the colors at the middles of the four squares
     * around it, weighted by how close it is to each, so a border becomes a smooth change over one square instead of a staircase.
     * Filled by the mesh builder per cell and reused.
     */
    static final class Blend {
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] biomes = new Holder[4];
        final int[] xs = new int[4];
        final int[] zs = new int[4];
        final float[] weights = new float[4];
    }

    /** Squares of 2^TINT_CELL blocks share a tint. */
    private static final int TINT_CELL = 2;
    private static final int MAX_TINTS = 8192;
    private static final ThreadLocal<Map<TintKey, Integer>> TINTS = ThreadLocal.withInitial(java.util.HashMap::new);

    private record TintKey(BlockTintSource tint, BlockState state, @Nullable Holder<Biome> biome, int x, int z) {
    }

    /** Just enough of a world for vanilla's tint sources: the biome colors of one place, nothing else. */
    private record TintGetter(@Nullable Holder<Biome> biome) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return this.biome == null ? -1 : resolver.getColor(this.biome.value(), pos.getX(), pos.getZ());
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }
}
