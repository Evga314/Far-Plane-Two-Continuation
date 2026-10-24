package com.Evga314.farplanetwocontinuation.tile;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a block state looks like from far away, in one place for the server chunk summary and the client detail adapter, so
 * both sides classify a block the same way: whether it is drawn as a block at all, whether it is a plant on the ground,
 * whether what is under it stays visible, and how high its top face sits.
 * Что значит блок издалека - классификация в одном месте для серверной сводки чанка и клиентского адаптера детальных
 * тайлов, чтобы обе стороны понимали блок одинаково: рисуется ли он блоком, растение ли это на земле, видно ли сквозь
 * него то, что под ним, и на какой высоте его верхняя грань.
 */
public final class BlockLook {
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
    /** What a block state means for the summary, worked out once per state. */
    private static final Map<BlockState, Look> LOOKS = new ConcurrentHashMap<>();

    private BlockLook() {
    }

    /**
     * What a block state looks like from far away. Air and blocks invisible on maps (glass, barriers, light) are not drawn. Plants,
     * flowers, torches and other small things without collision are no blocks either, but are remembered as the plant on the ground;
     * those growing under water (kelp, seagrass) count as the water around them. Everything else is a block, as high as its shape.
     */
    public static Look look(BlockState state) {
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
    public record Look(int block, int height, int plant, boolean clear, boolean liquid) {
        public static final Look NOTHING = new Look(-1, 16, 0, false, false);
    }
}

