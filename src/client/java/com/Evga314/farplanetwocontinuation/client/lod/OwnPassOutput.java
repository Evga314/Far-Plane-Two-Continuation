package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Draws the new renderer's rough terrain (see {@link FrameDrawList}) in the LOD terrain's own pass, which vanilla is drawn over: the
 * way out for OpenGL on phones without depth from 0 to 1, and for Iris without a shader pack. It draws in the pass LodRenderer
 * opens, beside the detailed tiles drawn the old way.
 * <p>
 * The ground first, nearest tiles first, with nothing dropped per pixel; the background under vanilla's columns with it, with its
 * depth, as vanilla is drawn over all of it anyway. Then the water over the ground, farthest first. Then, in the pass that writes the
 * depth vanilla is drawn with, the depth of the rough terrain's own columns only: the background's would hide vanilla's blocks.
 */
final class OwnPassOutput {
    private final FrameDrawList drawList;
    private final Function<RenderPipeline, @Nullable CompiledRenderPipeline> compile;

    OwnPassOutput(FrameDrawList drawList, Function<RenderPipeline, @Nullable CompiledRenderPipeline> compile) {
        this.drawList = drawList;
        this.compile = compile;
    }

    void drawGround(RenderPass pass) {
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.GROUND), false, OwnershipMap.OWN, Double.MAX_VALUE);
        this.drawList.drawSeam(pass, this.compile.apply(SurfacePipelines.GROUND));
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.RING), false, OwnershipMap.BACKGROUND, Double.MAX_VALUE);
    }

    /** The water of the rough terrain's own columns: under vanilla, vanilla's water lies over the background. */
    void drawWater(RenderPass pass) {
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.WATER), true, OwnershipMap.OWN, Double.MAX_VALUE);
    }

    /** The ground's depth for vanilla, of the tiles within {@code distance} of the camera, see surface_depth.fsh. */
    void drawDepth(RenderPass pass, double distance) {
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.DEPTH), false, OwnershipMap.OWN, distance);
        this.drawList.drawSeam(pass, this.compile.apply(SurfacePipelines.DEPTH));
    }
}
