package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Draws the new renderer's rough terrain (see {@link FrameDrawList}) inside vanilla's main pass, into vanilla's depth with its far
 * plane at infinity (see CameraMixin): the fastest way, for devices with depth from 0 to 1 (computers, Vulkan). Whatever vanilla drew
 * in front is rejected before it is shaded, and no depth has to be written for vanilla.
 * <p>
 * The ground goes right after vanilla's opaque chunks: the rough terrain's own columns with their depth, then the background under
 * vanilla's at the far plane, which passes the depth test only where vanilla drew nothing (the gap along its edge, the seabed it left
 * out, a column it has just started drawing) and writes no depth. The water of the own columns goes after vanilla's solid entities,
 * before its translucent things, which blend over it.
 */
final class VanillaPassOutput {
    private final FrameDrawList drawList;
    private final Function<RenderPipeline, @Nullable CompiledRenderPipeline> compile;

    VanillaPassOutput(FrameDrawList drawList, Function<RenderPipeline, @Nullable CompiledRenderPipeline> compile) {
        this.drawList = drawList;
        this.compile = compile;
    }

    void drawGround(RenderPass pass) {
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.GROUND), false, OwnershipMap.OWN, Double.MAX_VALUE);
        this.drawList.drawSeam(pass, this.compile.apply(SurfacePipelines.GROUND));
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.BACKGROUND), false, OwnershipMap.BACKGROUND, Double.MAX_VALUE);
    }
    void drawWater(RenderPass pass) {
        this.drawList.draw(pass, this.compile.apply(SurfacePipelines.WATER), true, OwnershipMap.OWN, Double.MAX_VALUE);
    }
}
