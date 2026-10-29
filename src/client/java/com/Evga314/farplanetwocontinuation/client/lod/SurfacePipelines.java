package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.surface.LodVertex;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The pipelines of the new renderer's rough terrain, all with the same vertex shader (core/surface.vsh) and the same uniforms
 * (surface.glsl), shared by its outputs ({@link OwnPassOutput}, {@link VanillaPassOutput}).
 */
final class SurfacePipelines {
    private static final Identifier VERTEX = Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "core/surface");
    /** See {@link LodVertex}. */
    static final VertexFormat FORMAT = VertexFormat.builder(0)
            // Read as normalized and scaled back in the shader, which gives the same whole numbers: integer attributes are not read
            // the same on every backend.
            .addAttribute("Position", GpuFormat.RGBA16_UNORM)
            .addAttribute("Extra", GpuFormat.RGBA8_UNORM)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("Second", GpuFormat.RGBA8_UNORM)
            .build();
    private static final BindGroupLayout SURFACE = BindGroupLayout.builder()
            .withUniform("SurfaceFrame", UniformType.UNIFORM_BUFFER)
            .withUniform("SurfaceTile", UniformType.UNIFORM_BUFFER)
            .build();
    /** How far the water is drawn closer than it lies, as vanilla draws the cracks of blocks being broken: the coast stays put. */
    private static final float WATER_BIAS_SCALE = 1.0F;
    private static final float WATER_BIAS_CONSTANT = 4.0F;

    private SurfacePipelines() {
    }

    private static RenderPipeline.Builder base(String name, String fragment) {
        return RenderPipeline.builder()
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withBindGroupLayout(SURFACE)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/" + name))
                .withVertexShader(VERTEX)
                .withFragmentShader(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, fragment))
                .withVertexBinding(0, FORMAT)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                // The ground faces up, its skirts out of the tile, the undersides of islands down: from the back none is seen.
                .withCull(true);
    }

    /** The ground of the rough terrain's own columns. */
    static final RenderPipeline GROUND = RenderPipelines.registerOptional(
            base("surface_ground", "core/surface_ground")
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .build()
    );
    /** The water, blended, drawn a little closer than it lies. */
    static final RenderPipeline WATER = RenderPipelines.registerOptional(
            base("surface_water", "core/surface_water")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, WATER_BIAS_SCALE, WATER_BIAS_CONSTANT))
                    .build()
    );
    /** The depth of the ground in vanilla's projection, for the LOD terrain's own pass, see surface_depth.fsh. */
    static final RenderPipeline DEPTH = RenderPipelines.registerOptional(
            base("surface_depth", "core/surface_depth")
                    .withShaderDefine("DEPTH")
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                    .build()
    );
}
