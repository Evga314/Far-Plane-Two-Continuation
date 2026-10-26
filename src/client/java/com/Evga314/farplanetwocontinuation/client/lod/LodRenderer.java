package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Draws LOD tiles into the main target before vanilla terrain. The pass uses its own projection fitted to the visible tiles, which
 * keeps depth precision usable millions of blocks away even on GLES devices without clip control. Afterwards depth is cleared, so
 * vanilla geometry always ends up in front, and LOD tiles outside the vanilla render distance write their depth again in vanilla's
 * depth range, so clouds and other far vanilla effects behind LOD mountains are hidden correctly.
 */
public final class LodRenderer {
    /** Depth scale of rough tiles: 1% farther than they are, which is enough for detailed blocks in the same place to always win. */
    private static final float ROUGH_DEPTH_PRIORITY = 1.01F;
    /** How much farther each coarser level of rough tiles is pushed on top of that, see the vertex shader. */
    private static final float ROUGH_LEVEL_PRIORITY = 0.002F;

    /** No change of color: for the terrain lit by vanilla's lightmap in the shader. */
    private static final Vector4f UNLIT = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    /** Light of the bottom of the world and the wall under water under the End sky: the greenish light vanilla blocks get there, so both look the same at the border. */
    private static final Vector4f END_LIGHT = new Vector4f(0.42F, 0.5F, 0.45F, 1.0F);
    /**
     * Average color of the End sky. Its fog color is a different, greenish one; distant islands faded into it would show as a band
     * under the starry sky, so they fade into the sky instead.
     */
    private static final float NO_SKY_FOG_RED = 0.08F;
    private static final float NO_SKY_FOG_GREEN = 0.063F;
    private static final float NO_SKY_FOG_BLUE = 0.115F;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "core/lod_terrain");

    /** Rough tiles: position, land color, and water color with the share of water in its alpha; see TileMeshBuilder.putVertex. */
    public static final VertexFormat ROUGH_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("WaterColor", GpuFormat.RGBA8_UNORM)
        .addAttribute("LandThird", GpuFormat.RGBA8_UNORM)
        .addAttribute("WaterThird", GpuFormat.RGBA8_UNORM)
        .build();

    private static final java.util.Set<RenderPipeline> LIT_PIPELINES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Values ahead of the chunks in the chunk mask buffer, see {@link #CHUNK_MASK}. */
    private static final int MASK_HEADER = 5;
    /**
     * The chunks around the camera vanilla draws, for rough tiles: in ints, how far the camera is from the corner of the chunk in the
     * middle (x and z as float bits), how many chunks the mask reaches from the middle, how far vanilla draws above and below the camera
     * (float bits), the height of the camera over the bottom of the world plus 1 (float bits), then one value per chunk, see
     * {@link LodManager.ChunkMask}.
     */
    private static final BindGroupLayout CHUNK_MASK = BindGroupLayout.builder().withUniform("ChunkMask", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT).build();

    // Rough tiles are drawn from the front only: their surface faces up and their skirts face out of the tile, so from under the ground
    // they are not seen, and of the two skirts on a shared edge only one ever is, never flickering against the other. Floating islands
    // (the End) are seen from below too: their tiles draw the same triangles once more the other way round, see TileMeshBuilder.
    public static final RenderPipeline PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("ROUGH")
            .withBindGroupLayout(CHUNK_MASK)
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /** Rough tiles with nothing cut away, see {@link TileMesh#solid}. */
    public static final RenderPipeline SOLID_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_solid"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("NO_DISCARD")
            .withShaderDefine("ROUGH")
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /**
     * Rough tiles of floating islands (the End): the coast value of a vertex is the signed distance to the island edge, and the
     * shader cuts the surface where it crosses zero instead of half way to the void sample, see TileMeshBuilder.
     */
    public static final RenderPipeline FLOATING_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_floating"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("ROUGH")
            .withShaderDefine("FLOATING")
            .withBindGroupLayout(CHUNK_MASK)
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /**
     * The water of rough tiles within the detailed tiles and vanilla's chunks ([2][1]), drawn after the rough terrain and before the
     * detailed tiles without writing depth, the way vanilla is drawn over rough water: whatever the detailed tiles have there is drawn
     * over it, and it only shows where they have nothing, through the gaps between them and through their water where the seabed is
     * not loaded yet. Rough water there never fights with the seabed for the depth test.
     */
    public static final RenderPipeline UNDER_DETAIL_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_under_detail"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("ROUGH")
            .withShaderDefine("UNDER_DETAIL")
            .withBindGroupLayout(CHUNK_MASK)
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(true)
            .build()
    );

    /** Writes only depth, converted to vanilla's projection, for tiles beyond the vanilla render distance. */
    public static final RenderPipeline DEPTH_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_depth"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("DEPTH_ONLY")
            .withShaderDefine("ROUGH")
            .withBindGroupLayout(CHUNK_MASK)
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build()
    );

    /** The depth pass of floating tiles, with the same island edge cut as {@link #FLOATING_PIPELINE}. */
    public static final RenderPipeline DEPTH_FLOATING_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_depth_floating"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("DEPTH_ONLY")
            .withShaderDefine("ROUGH")
            .withShaderDefine("FLOATING")
            .withBindGroupLayout(CHUNK_MASK)
            .withVertexBinding(0, ROUGH_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build()
    );

    /** Detailed tiles are closed boxes facing outwards; culling back faces keeps coplanar walls of neighbours from flickering. */
    public static final RenderPipeline DETAIL_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /**
     * The bottom of the world, which only shows where nothing at all is drawn. It writes no depth, so everything drawn after it covers
     * it. In the LOD pass of its own it is drawn first into the cleared depth. Inside vanilla's pass it comes right after the rough
     * terrain, pushed far back ({@link #FLOOR_PRIORITY}): it never covers vanilla's blocks or the rough terrain pushed back under the
     * detailed tiles (see [2][1] in the shader), which from high up lies deeper than the bottom of the world, and the depth test leaves
     * out early what they cover. The rough water under the detailed tiles and the detailed tiles are drawn over it.
     */
    public static final RenderPipeline FLOOR_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_world_floor"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(true)
            .build()
    );

    /**
     * Detailed tiles with block textures, 16 bytes a vertex: the position in fixed point with the sprite size in texels as fourth value,
     * the color, and the sprite corner in the atlas in half texels. See {@code DetailMeshBuilder.QuadWriter} and the vertex shader.
     */
    public static final VertexFormat TEXTURED_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGBA16_SINT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG16_UINT)
        .build();

    public static final RenderPipeline DETAIL_TEXTURED_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_textured"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("TEXTURED")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    public static final RenderPipeline DETAIL_SOLID_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_solid"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("NO_DISCARD")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    public static final RenderPipeline DETAIL_TEXTURED_SOLID_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_textured_solid"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("TEXTURED")
            .withShaderDefine("NO_DISCARD")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /**
     * Under water with its fog turned off: a wall at the edge of the detailed tiles in the color of the underwater fog, darker with depth,
     * from the bottom of the world up to the water surface, with a lid on the surface beyond it, so the view ends in the sea like in
     * vanilla's fog instead of showing the sky and far terrain through the water. The terrain fades into it, see the shader.
     */
    public static final RenderPipeline WALL_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_water_wall"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("WATER_WALL")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build()
    );

    /** Translucent water of detailed tiles, drawn after everything else without writing depth, so the ground under it shows. */
    public static final RenderPipeline DETAIL_WATER_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_water"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("TEXTURED")
            .withShaderDefine("WATER_PASS")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(true)
            .build()
    );

    /**
     * The fading edge of the detailed tiles, blended over the rough terrain under it. It writes depth, so of a detailed tile only its
     * front faces blend in, not the ground behind its trees as well.
     */
    public static final RenderPipeline DETAIL_TEXTURED_FADE_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_textured_fade"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("TEXTURED")
            .withShaderDefine("FADE_PASS")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    public static final RenderPipeline DETAIL_FADE_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_fade"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("LIGHTMAP")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withShaderDefine("FADE_PASS")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    public static final RenderPipeline DEPTH_DETAIL_TEXTURED_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_textured_depth"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("DEPTH_ONLY")
            .withShaderDefine("TEXTURED")
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build()
    );

    public static final RenderPipeline DEPTH_DETAIL_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_depth"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("DEPTH_ONLY")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build()
    );

    static {
        java.util.Collections.addAll(LIT_PIPELINES, PIPELINE, SOLID_PIPELINE, FLOATING_PIPELINE, UNDER_DETAIL_PIPELINE, DETAIL_PIPELINE,
            DETAIL_TEXTURED_PIPELINE, DETAIL_SOLID_PIPELINE, DETAIL_TEXTURED_SOLID_PIPELINE, DETAIL_WATER_PIPELINE, DETAIL_TEXTURED_FADE_PIPELINE,
            DETAIL_FADE_PIPELINE);
    }

    private final LodManager manager;
    private @Nullable GpuBuffer roughIndices;
    private @Nullable GpuBuffer quadIndices;
    /**
     * The bottom of the world: one big quad in the color of deep water, see {@link #worldFloor}. Seen through water at a grazing angle,
     * a view goes on under the surface far beyond the blocks and the detailed tiles, to where the rough terrain has only the surface of
     * its water; without something down there the sky would show through the water.
     */
    private @Nullable GpuBuffer floorVertices;
    private int floorColor;
    private int quadIndexCapacity;
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    private @Nullable Matrix4f bobbedProjection;
    private boolean pipelineFailed;
    private @Nullable GpuSampler atlasSampler;
    /**
     * Compiled pipelines lit by vanilla's lightmap (LIGHTMAP in the shader): the terrain takes its light the way vanilla's does, with
     * the time of day, night vision, the brightness setting and the darkness effect.
     */
    private final java.util.Set<CompiledRenderPipeline> litPipelines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private @Nullable MappableRingBuffer chunkMaskBuffer;
    /** Version of the mask each buffer of {@link #chunkMaskBuffer} holds; -1 none yet. */
    private final int[] chunkMaskVersions = {-1, -1, -1};
    private int chunkMaskSlot;
    /** The chunk mask of this frame, see {@link #uploadChunkMask}. */
    private @Nullable GpuBuffer frameChunkMask;
    private int samplerAnisotropy;
    private String lastFrameStats = "none";

    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f bobMatrix = new Matrix4f();
    private final Matrix4f cullMatrix = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    /** Scratch for {@link #frameRanges}, only used on the render thread. */
    private boolean[] onScreen = new boolean[256];
    private final boolean[] groupsSeen = new boolean[TileMesh.GROUPS];
    /** Culling is turned off this frame, see {@link PerfLog#occlusionOff()}. */
    private boolean cullingOff;
    private final List<TileMesh> visible = new ArrayList<>();
    private final List<TileMesh> depthTiles = new ArrayList<>();

    public LodRenderer(LodManager manager) {
        this.manager = manager;
    }

    /** Projection actually used for the level this frame, including view bobbing and nausea distortion. */
    public void captureLevelProjection(Matrix4f projection) {
        if (this.bobbedProjection == null) {
            this.bobbedProjection = new Matrix4f();
        }
        this.bobbedProjection.set(projection);
    }

    public boolean shouldRender(CameraRenderState camera) {
        return !this.pipelineFailed
            && this.manager.isActive()
            && !this.manager.drawList().isEmpty()
            && !this.manager.enclosedUnderground()
            && (camera.fogType == FogType.NONE || camera.fogType == FogType.WATER)
            && !camera.isPanoramicMode
            && camera.projectionMatrix.m23() != 0.0F;
    }

    /**
     * Prototype of drawing the LOD terrain inside vanilla's main pass, after its opaque chunks, into the same depth buffer with the far
     * plane at infinity (see CameraMixin and LevelRendererMixin). Off by default; the debug build and the tests turn it on.
     */
    public static volatile boolean mainPassPrototype;

    /**
     * Where Sodium stops looking for sections behind the fog: the nearer end of the fog this frame, infinite without fog. Set by LodFog
     * from vanilla's fog as it is written for the frame, as Sodium takes it; see LodView.sodiumInReach.
     */
    public static volatile float fogCullDistance = Float.POSITIVE_INFINITY;

    public void render(RenderTarget target, CameraRenderState camera, GpuBufferSlice fog) {
        if (!PerfLog.ON) {
            this.renderLod(target, camera, fog, false);
            return;
        }
        long start = System.nanoTime();
        this.renderLod(target, camera, fog, false);
        PerfLog.renderTime(System.nanoTime() - start);
    }

    /**
     * Prototype, first half: everything that writes to buffers (culling, the tiles' transforms, the chunk mask), done before vanilla's
     * main pass opens: nothing may be written while a pass is open. The drawing waits for {@link #renderInMainPass}.
     */
    public void prepareMainPass(CameraRenderState camera) {
        long start = PerfLog.ON ? System.nanoTime() : 0L;
        this.pendingDraw = null;
        this.pendingWater = null;
        this.renderLod(null, camera, null, true);
        if (PerfLog.ON) {
            PerfLog.renderTime(System.nanoTime() - start);
        }
    }

    /**
     * Prototype, second half: draws the opaque terrain {@link #prepareMainPass} prepared into vanilla's main pass, right after vanilla's
     * opaque chunks, sharing its projection and depth.
     */
    public void renderInMainPass(RenderPass pass, CameraRenderState camera) {
        java.util.function.Consumer<RenderPass> draw = this.pendingDraw;
        this.pendingDraw = null;
        if (draw == null) {
            return;
        }
        long start = PerfLog.ON ? System.nanoTime() : 0L;
        draw.accept(pass);
        // Vanilla draws its entities next with its own uniforms.
        RenderSystem.bindDefaultUniforms(pass);
        if (PerfLog.ON) {
            PerfLog.renderTime(System.nanoTime() - start);
        }
    }

    /**
     * Prototype, last part: the translucent water, after vanilla's solid entities and before its translucent things and its own water,
     * which is then blended over it where both are seen.
     */
    public void renderWaterInMainPass(RenderPass pass) {
        java.util.function.Consumer<RenderPass> water = this.pendingWater;
        this.pendingWater = null;
        if (water != null) {
            long start = PerfLog.ON ? System.nanoTime() : 0L;
            // The entities drawn before may have left their own uniforms.
            RenderSystem.bindDefaultUniforms(pass);
            water.accept(pass);
            RenderSystem.bindDefaultUniforms(pass);
            if (PerfLog.ON) {
                PerfLog.renderTime(System.nanoTime() - start);
            }
        }
        this.visible.clear();
        this.depthTiles.clear();
    }

    /** Prototype: the drawing prepared for vanilla's main pass this frame, the opaque terrain and the water. */
    private java.util.function.@Nullable Consumer<RenderPass> pendingDraw;
    private java.util.function.@Nullable Consumer<RenderPass> pendingWater;

    private void renderLod(@Nullable RenderTarget target, CameraRenderState camera, @Nullable GpuBufferSlice fog, boolean inMain) {
        CompiledRenderPipeline pipeline = this.compiledPipeline(PIPELINE);
        if (pipeline == null || !inMain && (target == null || target.getDepthTextureView() == null)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;
        // What vanilla draws along its edge is asked once a frame, before the tiles are culled and their transforms are made.
        this.manager.frameCheck(camX, camY, camZ);

        Matrix4f base = camera.projectionMatrix;
        float fovY = 2.0F * (float) Math.atan(1.0 / base.m11());
        float aspect = base.m11() / base.m00();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        if (this.bobbedProjection != null) {
            this.bobMatrix.set(base).invert().mul(this.bobbedProjection);
        } else {
            this.bobMatrix.identity();
        }

        // Cull against a projection that is guaranteed to contain every tile, then fit the real near and far planes to what is left.
        this.lodProjection.setPerspective(fovY, aspect, this.manager.activeLodDistance() * 2.0F + (float) Math.abs(camY) + 4096.0F, 0.05F, zeroToOne).mul(this.bobMatrix);
        this.cullMatrix.set(this.lodProjection).mul(camera.viewRotationMatrix);
        this.frustum.set(this.cullMatrix, false);
        LodView.lastView(this.cullMatrix, camX, camY, camZ);
        this.visible.clear();
        this.depthTiles.clear();
        double nearest = Double.MAX_VALUE;
        double farthest = 0.0;
        int maxQuads = 0;
        double vanillaFar = camera.depthFar;
        boolean end = minecraft.level != null && DimensionKind.of(minecraft.level) == DimensionKind.END;
        if (end) {
            // If the floating variant failed to compile the rough tiles keep the old edge, half way to the void sample.
            CompiledRenderPipeline floatingPipeline = this.compiledPipeline(FLOATING_PIPELINE);
            if (floatingPipeline != null) {
                pipeline = floatingPipeline;
            }
        }
        this.cullingOff = PerfLog.occlusionOff();
        DimensionKind dimension = minecraft.level == null ? DimensionKind.OVERWORLD : DimensionKind.of(minecraft.level);
        boolean vision = minecraft.player != null && (minecraft.player.hasEffect(MobEffects.BLINDNESS) || minecraft.player.hasEffect(MobEffects.DARKNESS));
        LodFog lodFog = this.lodFog;
        lodFog.compute(this.manager.config().distant(), dimension, camera.fogType, vision, minecraft.options.getEffectiveRenderDistance(), this.manager.activeLodDistance());
        // Tiles all in fog are left out where what is behind them comes out in the fog color too: the sky in a fluid, or the bottom of
        // the world, drawn with the same fog.
        boolean fogCulls = camera.fogType != FogType.NONE || dimension == DimensionKind.OVERWORLD && !DebugToggles.noPatch;
        // Under water with its fog off, the view ends at a wall at the edge of the detailed tiles (see WALL_PIPELINE); rough tiles all
        // beyond it are not drawn. With the fog on it hides that edge long before, and there is no wall.
        float wallRadius = 0.0F;
        float waterTop = 0.0F;
        if (inMain && !end && camera.fogType == FogType.WATER && minecraft.level != null) {
            float radius = (float) Math.max(this.manager.detailRingRadius(), this.manager.vanillaRadius());
            if (radius > 0.0F && lodFog.environmentalEnd > radius * WALL_FADE_START) {
                wallRadius = radius;
                waterTop = this.waterSurface(minecraft.level, camX, camY, camZ);
            }
        }
        for (TileMesh mesh : this.manager.drawList()) {
            if (mesh.vertices.isClosed() || mesh.occluded) {
                continue;
            }
            double minX = mesh.pos.minX() - camX;
            double minZ = mesh.pos.minZ() - camZ;
            double size = mesh.pos.size();
            double minY = mesh.minY - camY;
            double maxY = mesh.maxY - camY;
            int inFrustum = this.frustum.intersectAab((float) minX, (float) minY, (float) minZ, (float) (minX + size), (float) maxY, (float) (minZ + size));
            if (inFrustum >= 0) {
                continue;
            }
            double dx = Math.max(0.0, Math.max(minX, -(minX + size)));
            double dy = Math.max(0.0, Math.max(minY, -maxY));
            double dz = Math.max(0.0, Math.max(minZ, -(minZ + size)));
            double horizontal = dx * dx + dz * dz;
            double distance = Math.sqrt(horizontal + dy * dy);
            if (fogCulls && lodFog.hides(distance, Math.sqrt(horizontal), dy)
                || wallRadius > 0.0F && !mesh.detail && horizontal > (double) wallRadius * wallRadius) {
                continue;
            }
            mesh.frameRanges = null;
            if (mesh.segments != null && !this.frameRanges(mesh, inFrustum == FrustumIntersection.INTERSECT, minX, minY, minZ, maxY)) {
                continue;
            }
            mesh.solid = isSolid(mesh, camX, camZ, end, Math.max(this.manager.vanillaRadius(), this.manager.detailRingRadius()) + 32.0);
            this.visible.add(mesh);
            mesh.sortDistance = distance;
            nearest = Math.min(nearest, distance);
            if (distance < vanillaFar) {
                this.depthTiles.add(mesh);
            }
            double fx = Math.max(Math.abs(minX), Math.abs(minX + size));
            double fy = Math.max(Math.abs(minY), Math.abs(maxY));
            double fz = Math.max(Math.abs(minZ), Math.abs(minZ + size));
            farthest = Math.max(farthest, Math.sqrt(fx * fx + fy * fy + fz * fz));
            if (mesh.detail) {
                maxQuads = Math.max(maxQuads, mesh.indexCount / 6);
            }
        }
        if (this.visible.isEmpty()) {
            return;
        }
        // Tiles of one kind together, so the pipeline is switched only a few times per frame instead of between neighbouring tiles;
        // within a kind near tiles first, so the depth test rejects most hidden fragments early. Detailed tiles are the near ones anyway.
        this.visible.sort(DRAW_ORDER);
        this.depthTiles.sort(DRAW_ORDER);

        float far = (float) (farthest * 1.02 + 16.0);
        // The near plane is perpendicular to the view direction, so a point at distance d near the screen corner is only
        // d * cos(corner angle) deep. Scale the nearest distance by that to never clip visible tiles.
        double tanX = 1.0 / Math.abs(base.m00());
        double tanY = 1.0 / Math.abs(base.m11());
        double cornerCos = 1.0 / Math.sqrt(1.0 + tanX * tanX + tanY * tanY);
        float near = (float) Math.min(Math.max(0.5, nearest * cornerCos * 0.9), far * 0.5);
        this.lodProjection.setPerspective(fovY, aspect, far, near, zeroToOne).mul(this.bobMatrix);
        this.lastFrameStats = "visible=" + this.visible.size() + " near=" + near + " far=" + far + " zeroToOne=" + zeroToOne;

        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float skyFactor = minecraft.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTick);
        // Under any other sky than the overworld one (the End) vanilla blocks are lit evenly, so the sky light factor of 0 there would
        // make LOD terrain almost black.
        boolean hasSky = minecraft.level == null || DimensionKind.of(minecraft.level) != DimensionKind.END;
        float brightness = 0.12F + 0.88F * Mth.clamp(skyFactor, 0.0F, 1.0F);
        // Under the overworld sky the terrain fades into the fog color; skies without its sky disc would get a pale band instead.
        float overworldSky = hasSky ? 1.0F : 0.0F;
        Vector4f modulator = hasSky ? this.modulator.set(brightness, brightness, brightness, 1.0F) : this.modulator.set(END_LIGHT);
        float textureLevel = this.manager.config().detail().blocks().fixedTextureLevel();
        float ringRadius = (float) this.manager.detailRingRadius();
        float canopyStart = (float) this.manager.canopyStart();
        // Rough tiles are also pushed back within vanilla's render distance, behind vanilla's blocks in chunks vanilla is still building:
        // the chunks it draws leave the rough terrain out (see LodView.chunkMask) only once they are settled.
        float underRadius = Math.max(ringRadius, (float) this.manager.vanillaRadius());
        float waterBlur = blurWidth(this.manager.config().distant().smoothing.waterBlur);
        float coastBlur = blurWidth(this.manager.config().distant().smoothing.coastsBlur);

        // The tiles' own transforms first, then those of the chunks vanilla draws partly, see Frame.
        Frame frame = this.frame;
        // The bottom of the world under the sea, see floorVertices. Its transform comes after those of the tiles.
        boolean floor = minecraft.level != null && DimensionKind.of(minecraft.level) == DimensionKind.OVERWORLD && !DebugToggles.noPatch;
        int floorIndex = this.visible.size();
        boolean wall = wallRadius > 0.0F && hasSky;
        int wallIndex = floorIndex + (floor ? 1 : 0);
        // The wall darkens as the camera goes deeper, like vanilla's view under water.
        float cameraShade = Mth.lerp((float) Math.exp(-Math.max(0.0, waterTop - camY) / WALL_CAMERA_DEPTH), WALL_CAMERA_DEEPEST, 1.0F);
        frame.begin(wallIndex + (wall ? 1 : 0));
        boolean anyFade = false;
        boolean anyWater = false;
        for (int i = 0; i < this.visible.size(); i++) {
            TileMesh mesh = this.visible.get(i);
            anyFade |= mesh.fadeWidth > 0.0F;
            anyWater |= mesh.hasWater;
            // TextureMat is unused by this shader, so it carries per-tile parameters: [0][0] inner cut, [1][1] outer cut, [2][2] depth
            // priority, [3][3] overworld sky. Rough tiles give way to detailed ones wherever both are drawn.
            Matrix4f cut = frame.matrix().zero();
            cut.m00(mesh.discardRadius);
            cut.m11(mesh.keepRadius);
            // [1][3]: the width of the band inside the outer cut where detailed tiles fade into the rough terrain.
            cut.m13(mesh.fadeWidth);
            // [2][3]: rough tiles lay their tree tops flat closer than this, where detailed tiles or vanilla's chunks are drawn.
            cut.m23(mesh.detail ? 0.0F : canopyStart);
            if (!mesh.detail) {
                // [0][2] and [1][2]: how soft the borders between waters of different temperatures and the coasts are drawn, see the
                // shader.
                cut.m02(waterBlur);
                cut.m12(coastBlur);
                // [0][3]: the debug build marks the rough terrain under vanilla's chunks, see DebugToggles.markVanillaChunks.
                cut.m03(DebugToggles.markVanillaChunks ? 1.0F : 0.0F);
            }
            // Finer rough tiles win over coarser ones drawn in the same place while they load: on flat water both lie in one plane.
            cut.m22(mesh.detail ? 1.0F : ROUGH_DEPTH_PRIORITY + ROUGH_LEVEL_PRIORITY * mesh.pos.level());
            // [2][1]: within this horizontal distance rough tiles lie under detailed ones and are pushed much farther back, see the shader.
            cut.m21(mesh.detail ? 0.0F : underRadius);
            // [2][0]: the edge of the detailed tiles, where their water turns into the flat water of the rough terrain.
            cut.m20(mesh.detail ? ringRadius : 0.0F);
            cut.m33(overworldSky);
            // [1][0]: the texture level textured detailed tiles always use, below 0 for vanilla's choice by distance.
            cut.m10(textureLevel);
            if (!hasSky) {
                cut.m30(NO_SKY_FOG_RED).m31(NO_SKY_FOG_GREEN).m32(NO_SKY_FOG_BLUE);
            } else if (wall) {
                // [3][0] the radius of the wall the terrain fades into, [3][1] the water surface, [3][2] the darkening by the depth of
                // the camera, [0][1] the camera height, see the shader.
                cut.m30(wallRadius).m31(waterTop).m32(cameraShade).m01((float) camY);
            }
            Vector3f offset = frame.offset(mesh, camX, camY, camZ);
            // The terrain takes its light from vanilla's lightmap, see LIGHTMAP in the shader.
            frame.set(i, new DynamicGpuData.Transform(camera.viewRotationMatrix, UNLIT, offset, cut));
            int[] clippedChunks = mesh.clippedChunks;
            if (clippedChunks != null) {
                // [0][3] = 1: drop the sections vanilla draws itself; [1][2] holds them as one bit each from section [0][2] up, [0][1] is
                // the camera height to turn positions back into world heights.
                for (int c = 0; c + 3 < clippedChunks.length; c += 4) {
                    Matrix4f clipped = frame.matrix().set(cut);
                    clipped.m03(1.0F).m01((float) camY).m02(clippedChunks[c + 3]).m12(clippedChunks[c + 2]);
                    frame.addClipped(i, new DynamicGpuData.Transform(camera.viewRotationMatrix, UNLIT, offset, clipped));
                }
            }
        }
        if (floor) {
            // Drawn like the flat detailed tiles, with nothing cut away: in its own pass with the depth of where it is, inside vanilla's
            // pushed far back, see FLOOR_PIPELINE.
            Matrix4f cut = frame.matrix().zero();
            cut.m22(inMain ? FLOOR_PRIORITY : 1.0F);
            cut.m33(overworldSky);
            Vector3f offset = frame.offset(0.0F, (float) (minecraft.level.getMinY() - camY), 0.0F);
            frame.set(floorIndex, new DynamicGpuData.Transform(camera.viewRotationMatrix, modulator, offset, cut));
        }
        if (wall) {
            Matrix4f cut = frame.matrix().zero();
            cut.m22(1.0F);
            cut.m33(overworldSky);
            cut.m30(wallRadius).m31(waterTop).m32(cameraShade).m01((float) camY);
            Vector3f offset = frame.offset(0.0F, (float) (waterTop - camY), 0.0F);
            frame.set(wallIndex, new DynamicGpuData.Transform(camera.viewRotationMatrix, modulator, offset, cut));
        }
        GpuBufferSlice[] transformSlices = frame.write();

        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("farplane lod");
        }
        GpuBufferSlice projection = this.projectionBuffer.getBuffer(this.lodProjection);
        // The LOD terrain's own fog, see LodFog; vanilla's is bound again after it.
        GpuBufferSlice fogSlice = lodFog.write();
        GpuBuffer roughIndices = this.roughIndices();
        GpuBuffer quadIndices = maxQuads > 0 || floor || wall ? this.quadIndices(Math.max(maxQuads, WALL_SEGMENTS * 2)) : null;

        this.frameChunkMask = this.uploadChunkMask(camX, camY, camZ);
        CompiledRenderPipeline roughPipeline = pipeline;
        boolean waterTiles = anyWater;
        boolean fadeTiles = anyFade;
        CompiledRenderPipeline floorPipeline = floor && quadIndices != null ? this.compiledPipeline(FLOOR_PIPELINE) : null;
        GpuBuffer floorVertices = floorPipeline != null ? this.worldFloor(minecraft, camX, camY, camZ) : null;
        java.util.function.Consumer<RenderPass> drawFloor = pass -> {
            if (floorPipeline != null && floorVertices != null) {
                pass.setPipeline(floorPipeline);
                pass.setIndexBuffer(quadIndices, IndexType.INT);
                pass.setUniform("DynamicTransforms", transformSlices[floorIndex]);
                pass.setVertexBuffer(0, floorVertices.slice(0, floorVertices.size()));
                pass.drawIndexed(6, 1, 0, 0, 0);
            }
        };
        CompiledRenderPipeline wallPipeline = wall && quadIndices != null ? this.compiledPipeline(WALL_PIPELINE) : null;
        GpuBuffer wallVertices = wallPipeline != null ? this.waterWall(wallRadius, (float) (waterTop - minecraft.level.getMinY())) : null;
        java.util.function.Consumer<RenderPass> draw = pass -> {
                pass.setUniform("Fog", fogSlice);
                if (!inMain) {
                    // First of all, see FLOOR_PIPELINE.
                    drawFloor.accept(pass);
                }
                pass.setPipeline(roughPipeline);
                // The rough terrain, then its water under the detailed tiles (see UNDER_DETAIL_PIPELINE), then the detailed tiles.
                Pipelines rough = new Pipelines(
                    roughPipeline, null, null, true, false, false, new CompiledRenderPipeline[] {this.compiledPipeline(SOLID_PIPELINE), null, null}
                );
                PerfLog.pass(0);
                this.drawAll(pass, this.visible, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, rough);
                // The patch draws only water fragments; floating tiles have none, and with the distance in the coast value it would
                // take the land at their edges for water and draw fringes around the islands.
                if (inMain) {
                    // Right after the rough terrain, see FLOOR_PIPELINE.
                    drawFloor.accept(pass);
                }
                CompiledRenderPipeline under = end || DebugToggles.noPatch ? null : this.compiledPipeline(UNDER_DETAIL_PIPELINE);
                if (under != null) {
                    // Solid rough tiles lie beyond the detailed tiles and vanilla's chunks: only the others have water there.
                    Pipelines underDetail = new Pipelines(under, null, null, true, false, false, new CompiledRenderPipeline[] {null, null, null}, true);
                    PerfLog.pass(1);
                    this.drawAll(pass, this.visible, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, underDetail);
                }
                Pipelines detail = new Pipelines(
                    null, this.compiledPipeline(DETAIL_PIPELINE), this.compiledPipeline(DETAIL_TEXTURED_PIPELINE), true, false, false,
                    new CompiledRenderPipeline[] {null, this.compiledPipeline(DETAIL_SOLID_PIPELINE), this.compiledPipeline(DETAIL_TEXTURED_SOLID_PIPELINE)}
                );
                PerfLog.pass(2);
                this.drawAll(pass, this.visible, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, detail);
                if (fadeTiles) {
                    // The fading edge of the detailed tiles, blended over the rough terrain drawn under it.
                    Pipelines fade = new Pipelines(null, this.compiledPipeline(DETAIL_FADE_PIPELINE), this.compiledPipeline(DETAIL_TEXTURED_FADE_PIPELINE), true, false, true);
                    PerfLog.pass(3);
                    this.drawAll(pass, this.visible, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, fade);
                }
                if (wallPipeline != null && wallVertices != null) {
                    pass.setPipeline(wallPipeline);
                    pass.setIndexBuffer(quadIndices, IndexType.INT);
                    pass.setUniform("DynamicTransforms", transformSlices[wallIndex]);
                    pass.setVertexBuffer(0, wallVertices.slice(0, wallVertices.size()));
                    pass.drawIndexed(WALL_SEGMENTS * 2 * 6, 1, 0, 0, 0);
                }
        };
        CompiledRenderPipeline waterPipeline = waterTiles ? this.compiledPipeline(DETAIL_WATER_PIPELINE) : null;
        java.util.function.Consumer<RenderPass> drawWater = pass -> {
            if (waterPipeline != null) {
                pass.setUniform("Fog", fogSlice);
                PerfLog.pass(4);
                this.drawAll(pass, this.visible, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, new Pipelines(null, null, waterPipeline, true, true, false));
            }
        };
        if (inMain) {
            this.pendingDraw = draw;
            this.pendingWater = drawWater;
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (PerfLog.ON) {
            PerfLog.gpuMark(encoder, false);
        }
        try (RenderPass pass = encoder.createRenderPass(
            () -> "FarPlane LOD terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.of(0.0)
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projection);
            draw.accept(pass);
            drawWater.accept(pass);
        }
        encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
        this.writeVanillaDepth(target, camera, fog, encoder, camX, camY, camZ, zeroToOne, roughIndices, quadIndices, minecraft);
        if (PerfLog.ON) {
            PerfLog.gpuMark(encoder, true);
        }
        this.visible.clear();
        this.depthTiles.clear();
    }

    /**
     * Second, depth-only pass over the tiles within vanilla's far plane. Their depth is written in vanilla's own projection, slightly
     * pushed back, so vanilla blocks at the same place always win, vanilla things behind LOD terrain are hidden and clouds behind LOD
     * mountains too. Rough tiles only write it outside the vanilla render distance.
     */
    private void writeVanillaDepth(
        RenderTarget target, CameraRenderState camera, GpuBufferSlice fog, CommandEncoder encoder, double camX, double camY, double camZ,
        boolean zeroToOne, GpuBuffer roughIndices, @Nullable GpuBuffer quadIndices, Minecraft minecraft
    ) {
        if (this.depthTiles.isEmpty()) {
            return;
        }
        CompiledRenderPipeline depthPipeline = this.compiledPipeline(DEPTH_PIPELINE);
        if (depthPipeline == null) {
            return;
        }
        if (minecraft.level != null && DimensionKind.of(minecraft.level) == DimensionKind.END) {
            CompiledRenderPipeline floatingDepth = this.compiledPipeline(DEPTH_FLOATING_PIPELINE);
            if (floatingDepth != null) {
                depthPipeline = floatingDepth;
            }
        }
        Matrix4f vanillaProjection = this.bobbedProjection != null ? this.bobbedProjection : camera.projectionMatrix;
        Frame frame = this.frame;
        frame.begin(this.depthTiles.size());
        for (int i = 0; i < this.depthTiles.size(); i++) {
            TileMesh mesh = this.depthTiles.get(i);
            // Detailed tiles leave out every chunk vanilla draws and match real blocks, so they write their depth just behind them
            // everywhere. Rough terrain writes none where vanilla or the detailed tiles draw (the chunk mask, see the shader), but
            // within vanilla's render distance it does where vanilla has nothing yet: clouds behind it are hidden there too.
            float pushInside = 0.0F;
            // Rough tiles never have an outer cut; a negative w there is where their tree tops start to rise instead.
            Vector4f parameters = frame.vector().set(
                pushInside, zeroToOne ? 1.0F : 0.0F, mesh.discardRadius, mesh.detail ? mesh.keepRadius : -(float) this.manager.canopyStart()
            );
            Vector3f offset = frame.offset(mesh, camX, camY, camZ);
            frame.set(i, new DynamicGpuData.Transform(camera.viewRotationMatrix, parameters, offset, vanillaProjection));
            int[] clippedChunks = mesh.clippedChunks;
            if (clippedChunks != null) {
                // Chunks vanilla draws partly write their depth only in the sections vanilla leaves out, or vanilla would draw mobs under
                // the ground there in front of it. ColorModulator: x = sections vanilla draws, one bit each from section CLIPPED_DEPTH -
                // z up, w = camera height.
                for (int c = 0; c + 3 < clippedChunks.length; c += 4) {
                    Vector4f clipped = frame.vector().set(clippedChunks[c + 2], parameters.y, CLIPPED_DEPTH - clippedChunks[c + 3], (float) camY);
                    frame.addClipped(i, new DynamicGpuData.Transform(camera.viewRotationMatrix, clipped, offset, vanillaProjection));
                }
            }
        }
        GpuBufferSlice[] transformSlices = frame.write();
        GpuBufferSlice projection = this.projectionBuffer.getBuffer(this.lodProjection);
        try (RenderPass pass = encoder.createRenderPass(
            () -> "FarPlane LOD vanilla depth", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(depthPipeline);
            pass.setUniform("Projection", projection);
            pass.setUniform("Fog", fog);
            Pipelines pipelines = new Pipelines(
                depthPipeline, this.compiledPipeline(DEPTH_DETAIL_PIPELINE), this.compiledPipeline(DEPTH_DETAIL_TEXTURED_PIPELINE), false, false, false
            );
            PerfLog.pass(5);
            this.drawAll(pass, this.depthTiles, transformSlices, frame.clippedFirst, frame.clippedCount, roughIndices, quadIndices, pipelines);
        }
    }

    /**
     * The pipelines of one pass: rough tiles, detailed tiles in flat colors and detailed tiles with block textures. A water pass only
     * draws the tiles with translucent water.
     */
    private record Pipelines(
        @Nullable CompiledRenderPipeline rough, @Nullable CompiledRenderPipeline detail, @Nullable CompiledRenderPipeline textured, boolean bindAtlas,
        boolean waterOnly, boolean fadeOnly, @Nullable CompiledRenderPipeline @Nullable [] solid, boolean skipSolid
    ) {
        Pipelines(
            @Nullable CompiledRenderPipeline rough, @Nullable CompiledRenderPipeline detail, @Nullable CompiledRenderPipeline textured, boolean bindAtlas,
            boolean waterOnly, boolean fadeOnly
        ) {
            this(rough, detail, textured, bindAtlas, waterOnly, fadeOnly, null, false);
        }

        Pipelines(
            @Nullable CompiledRenderPipeline rough, @Nullable CompiledRenderPipeline detail, @Nullable CompiledRenderPipeline textured, boolean bindAtlas,
            boolean waterOnly, boolean fadeOnly, @Nullable CompiledRenderPipeline @Nullable [] solid
        ) {
            this(rough, detail, textured, bindAtlas, waterOnly, fadeOnly, solid, false);
        }

        /** The pipeline of a {@link #drawKind}: the one that never drops fragments for solid tiles, where this pass has one. */
        @Nullable CompiledRenderPipeline of(int drawKind) {
            int kind = drawKind / 2;
            if (drawKind % 2 == 0 && this.skipSolid) {
                return null;
            }
            if (drawKind % 2 == 0 && this.solid != null && this.solid[kind] != null) {
                return this.solid[kind];
            }
            return kind == KIND_ROUGH ? this.rough : kind == KIND_DETAIL ? this.detail : this.textured;
        }
    }

    /** How wide a border is drawn at a step of blur, in units of its value, as the shader does for the steps kept with samples. */
    private static float blurWidth(int step) {
        return step > 0 ? 0.02F * (1 << step) : 0.0F;
    }

    /** Marks a transform of the depth pass as one of a chunk vanilla draws partly, see writeVanillaDepth and the shader. */
    private static final float CLIPPED_DEPTH = -1000.0F;

    private static final Comparator<TileMesh> DRAW_ORDER = Comparator.<TileMesh>comparingInt(LodRenderer::drawKind)
        .thenComparingDouble(mesh -> mesh.sortDistance);

    /** Which pipeline a tile is drawn with; solid tiles of a kind come first, they hide the most for the depth test. */
    private static int drawKind(TileMesh mesh) {
        int kind = !mesh.detail ? KIND_ROUGH : mesh.textured ? KIND_TEXTURED : KIND_DETAIL;
        return kind * 2 + (mesh.solid ? 0 : 1);
    }

    /**
     * Whether nothing of a tile is cut away this frame: no part closer than its inner cut or past its outer cut, no sections vanilla
     * draws, no translucent water or textures with gaps, no void between islands (the End).
     */
    private static boolean isSolid(TileMesh mesh, double camX, double camZ, boolean end, double maskRadius) {
        if (end || mesh.clippedChunks != null || mesh.detail && (mesh.hasWater || mesh.textured && mesh.hasCutout)) {
            return false;
        }
        // Rough tiles leave out the chunks vanilla and the detailed tiles draw, see LodManager.ChunkMask.
        if (!mesh.detail && mesh.pos.distanceTo(camX, camZ) < maskRadius) {
            return false;
        }
        if (mesh.discardRadius > 0.0F && mesh.pos.distanceTo(camX, camZ) < mesh.discardRadius) {
            return false;
        }
        return mesh.keepRadius <= 0.0F || mesh.pos.farthestDistanceTo(camX, camZ) <= mesh.keepRadius - mesh.fadeWidth;
    }

    /**
     * What of a detailed tile is drawn this frame, in {@link TileMesh#frameRanges}: its faces turned away from the camera are left out,
     * and on the edge of the screen its chunks off screen. Returns whether anything of it is left.
     *
     * @param edge the tile is only partly on screen
     */
    private boolean frameRanges(TileMesh mesh, boolean edge, double minX, double minY, double minZ, double maxY) {
        int[] segments = mesh.segments;
        int side = mesh.chunksPerSide();
        int chunks = side * side;
        double size = mesh.pos.size();
        // A face can only be seen from in front of its plane, and all faces of a group lie within the tile: once the camera is past
        // the tile on one side, the faces of the group looking the other way all turn their backs to it.
        boolean[] groups = this.groupsSeen;
        groups[TileMesh.GROUP_UP] = minY < 0.0;
        groups[TileMesh.GROUP_DOWN] = maxY > 0.0;
        groups[TileMesh.GROUP_NORTH] = minZ + size > 0.0;
        groups[TileMesh.GROUP_SOUTH] = minZ < 0.0;
        groups[TileMesh.GROUP_WEST] = minX + size > 0.0;
        groups[TileMesh.GROUP_EAST] = minX < 0.0;
        groups[TileMesh.GROUP_OTHER] = true;
        groups[TileMesh.GROUP_WATER] = true;
        if (this.cullingOff) {
            // Turned off together with the occlusion culling, to compare.
            java.util.Arrays.fill(groups, true);
        }
        boolean anyGroupOff = false;
        for (boolean seen : groups) {
            anyGroupOff |= !seen;
        }
        if (this.onScreen.length < chunks) {
            this.onScreen = new boolean[chunks];
        }
        boolean anyOff = false;
        boolean anyOn = !edge;
        for (int c = 0; c < chunks; c++) {
            boolean on = true;
            if (edge) {
                float x = (float) (minX + (c % side) * 16.0);
                float z = (float) (minZ + (c / side) * 16.0);
                on = this.frustum.testAab(x, (float) minY, z, x + 16.0F, (float) maxY, z + 16.0F);
                anyOff |= !on;
                anyOn |= on;
            }
            this.onScreen[c] = on;
        }
        if (!anyOn) {
            return false;
        }
        if (!anyOff && !anyGroupOff) {
            return true;
        }
        int[] base = mesh.drawRanges != null ? mesh.drawRanges : new int[] {mesh.firstIndex, mesh.indexCount};
        int[] ranges = new int[base.length + chunks * TileMesh.GROUPS * 2];
        int count = 0;
        int lastSegment = chunks * TileMesh.GROUPS;
        for (int r = 0; r + 1 < base.length; r += 2) {
            int start = base[r];
            int stop = start + base[r + 1];
            // The segment holding the first index. Empty segments share their first quad with the next one; the walk steps over them.
            int found = java.util.Arrays.binarySearch(segments, 0, lastSegment, start / 6);
            int segment = found >= 0 ? found : Math.max(0, -found - 2);
            while (start < stop && segment < lastSegment) {
                int pieceEnd = Math.min(stop, segments[segment + 1] * 6);
                if (pieceEnd > start && groups[segment / chunks] && this.onScreen[segment % chunks]) {
                    if (count > 0 && ranges[count - 2] + ranges[count - 1] == start) {
                        ranges[count - 1] += pieceEnd - start;
                    } else {
                        ranges[count++] = start;
                        ranges[count++] = pieceEnd - start;
                    }
                }
                start = Math.max(start, pieceEnd);
                segment++;
            }
        }
        mesh.frameRanges = java.util.Arrays.copyOf(ranges, count);
        return count > 0 || mesh.clippedChunks != null;
    }

    /** Draws the indices of a range that lie between {@code low} and {@code high}, if any. */
    private static void drawPart(RenderPass pass, int first, int count, int low, int high) {
        int start = Math.max(first, low);
        int end = Math.min(first + count, high);
        if (end > start) {
            pass.drawIndexed(end - start, 1, start, 0, 0);
            if (PerfLog.ON) {
                PerfLog.draw(end - start);
            }
        }
    }

    private static final int KIND_ROUGH = 0;
    private static final int KIND_DETAIL = 1;
    private static final int KIND_TEXTURED = 2;

    /**
     * @param transforms    the transform of each tile, and after them those of the chunks vanilla draws partly
     * @param clippedFirst  where in {@code transforms} those of each tile start
     * @param clippedCount  how many each tile has
     */
    private void drawAll(
        RenderPass pass, List<TileMesh> meshes, GpuBufferSlice[] transforms, int[] clippedFirst, int[] clippedCount, GpuBuffer roughIndices,
        @Nullable GpuBuffer quadIndices, Pipelines pipelines
    ) {
        int bound = -1;
        int count = meshes.size();
        for (int n = 0; n < count; n++) {
            // Tiles come nearest first, which lets the depth test skip hidden fragments; translucent water blends correctly only
            // from the farthest to the nearest.
            int i = pipelines.waterOnly() ? count - 1 - n : n;
            TileMesh mesh = meshes.get(i);
            int kind = drawKind(mesh);
            CompiledRenderPipeline pipeline = pipelines.of(kind);
            if (pipeline == null || mesh.detail && quadIndices == null || pipelines.waterOnly() && !mesh.hasWater
                || pipelines.fadeOnly() && mesh.fadeWidth <= 0.0F) {
                continue;
            }
            if (bound != kind) {
                pass.setPipeline(pipeline);
                if (mesh.detail) {
                    pass.setIndexBuffer(quadIndices, IndexType.INT);
                } else {
                    pass.setIndexBuffer(roughIndices, IndexType.SHORT);
                }
                boolean solidRough = pipelines.solid() != null && pipeline == pipelines.solid()[KIND_ROUGH];
                if (!mesh.detail && !solidRough && this.frameChunkMask != null) {
                    pass.setUniform("ChunkMask", this.frameChunkMask);
                }
                if (mesh.textured && pipelines.bindAtlas()) {
                    GpuTextureView atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
                    pass.setUniform("Sampler0", atlas, this.atlasSampler());
                }
                if (this.litPipelines.contains(pipeline)) {
                    pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                }
                bound = kind;
            }
            pass.setUniform("DynamicTransforms", transforms[i]);
            pass.setVertexBuffer(0, mesh.vertexSlice());
            if (pipelines.skipSolid() && !mesh.detail) {
                // The water under the detailed tiles writes no depth, so of overlapping triangles the last one drawn is seen: the skirts
                // of a farther tile would lie over the water of a nearer one, flickering as the order changes. Only the surface is drawn.
                pass.drawIndexed(TileMeshBuilder.SURFACE_INDEX_COUNT, 1, 0, 0, 0);
                if (PerfLog.ON) {
                    PerfLog.draw(TileMeshBuilder.SURFACE_INDEX_COUNT);
                }
                continue;
            }
            // Detailed tiles keep their translucent water last: the water passes draw only that, the others everything before it.
            int low = mesh.firstIndex;
            int high = mesh.firstIndex + mesh.indexCount;
            if (mesh.detail) {
                int water = mesh.waterStart();
                if (pipelines.waterOnly()) {
                    low = water;
                } else {
                    high = water;
                }
            }
            int[] ranges = mesh.frameRanges != null ? mesh.frameRanges : mesh.drawRanges;
            if (ranges == null) {
                drawPart(pass, mesh.firstIndex, mesh.indexCount, low, high);
            } else {
                for (int r = 0; r + 1 < ranges.length; r += 2) {
                    drawPart(pass, ranges[r], ranges[r + 1], low, high);
                }
            }
            int[] clipped = mesh.clippedChunks;
            if (clipped != null) {
                for (int c = 0; c < clippedCount[i] && c * 4 + 1 < clipped.length; c++) {
                    pass.setUniform("DynamicTransforms", transforms[clippedFirst[i] + c]);
                    drawPart(pass, clipped[c * 4], clipped[c * 4 + 1], low, high);
                }
            }
        }
    }

    /**
     * Writes the chunk mask of the manager for this frame, see {@link #CHUNK_MASK}. The camera moves every frame, the mask only now and
     * then: the place of the camera in it is written every frame, the values only into buffers that do not hold this version yet.
     */
    private @Nullable GpuBuffer uploadChunkMask(double camX, double camY, double camZ) {
        LodManager.ChunkMask chunkMask = this.manager.chunkMask();
        int[] mask = chunkMask.values();
        int half = chunkMask.half();
        int size = (MASK_HEADER + Math.max(mask.length, 1)) * 4;
        try {
            if (this.chunkMaskBuffer == null || this.chunkMaskBuffer.size() < size) {
                if (this.chunkMaskBuffer != null) {
                    this.chunkMaskBuffer.close();
                }
                // Room for render distances up to 64 at once, so it is made only once.
                int capacity = Math.max(size, (MASK_HEADER + 131 * 131) * 4);
                this.chunkMaskBuffer = new MappableRingBuffer(() -> "FarPlane chunk mask", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, capacity);
                Arrays.fill(this.chunkMaskVersions, -1);
                this.chunkMaskSlot = 0;
            }
            // The ring buffer goes round its three buffers; which one is current is followed here to know what each one holds.
            this.chunkMaskBuffer.rotate();
            this.chunkMaskSlot = (this.chunkMaskSlot + 1) % 3;
            GpuBuffer buffer = this.chunkMaskBuffer.currentBuffer();
            boolean values = this.chunkMaskVersions[this.chunkMaskSlot] != chunkMask.version();
            try (GpuBufferSlice.MappedView view = buffer.slice(0, values ? size : MASK_HEADER * 4).map(false, true)) {
                ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
                data.putInt(Float.floatToIntBits((float) (camX - chunkMask.originX() * 16.0)));
                data.putInt(Float.floatToIntBits((float) (camZ - chunkMask.originZ() * 16.0)));
                data.putInt(mask.length == 0 ? -1 : half);
                data.putInt(Float.floatToIntBits(chunkMask.reach()));
                // The floors of the chunks count from the bottom of the world up from 1; this turns them into heights around the camera.
                data.putInt(Float.floatToIntBits((float) (camY - chunkMask.minY() + 1)));
                if (values) {
                    data.asIntBuffer().put(mask);
                }
            }
            this.chunkMaskVersions[this.chunkMaskSlot] = chunkMask.version();
            return buffer;
        } catch (RuntimeException e) {
            LOGGER.error("FarPlane could not write the chunk mask", e);
            Arrays.fill(this.chunkMaskVersions, -1);
            return null;
        }
    }

    /**
     * What one pass writes of the tiles' transforms, kept from frame to frame: the matrices and vectors in them are only read while
     * they are written to the GPU, so the same ones serve every frame, and the render thread makes hardly any garbage. The tiles'
     * own transforms come first, then those of the chunks vanilla draws partly, see drawAll.
     */
    private static final class Frame {
        private final List<Matrix4f> matrices = new ArrayList<>();
        private final List<Vector4f> vectors = new ArrayList<>();
        private final List<Vector3f> offsets = new ArrayList<>();
        private int usedMatrices;
        private int usedVectors;
        private int usedOffsets;
        private DynamicGpuData.Transform[] own = new DynamicGpuData.Transform[0];
        private final List<DynamicGpuData.Transform> clipped = new ArrayList<>();
        private DynamicGpuData.Transform @Nullable [] all;
        int[] clippedFirst = new int[0];
        int[] clippedCount = new int[0];
        private int tiles;

        void begin(int tiles) {
            this.tiles = tiles;
            this.usedMatrices = 0;
            this.usedVectors = 0;
            this.usedOffsets = 0;
            this.clipped.clear();
            if (this.own.length < tiles) {
                int size = Math.max(tiles, this.own.length * 2);
                this.own = new DynamicGpuData.Transform[size];
                this.clippedFirst = new int[size];
                this.clippedCount = new int[size];
            }
            java.util.Arrays.fill(this.clippedCount, 0, tiles, 0);
        }

        Matrix4f matrix() {
            if (this.usedMatrices == this.matrices.size()) {
                this.matrices.add(new Matrix4f());
            }
            return this.matrices.get(this.usedMatrices++);
        }

        Vector4f vector() {
            if (this.usedVectors == this.vectors.size()) {
                this.vectors.add(new Vector4f());
            }
            return this.vectors.get(this.usedVectors++);
        }

        Vector3f offset(float x, float y, float z) {
            if (this.usedOffsets == this.offsets.size()) {
                this.offsets.add(new Vector3f());
            }
            return this.offsets.get(this.usedOffsets++).set(x, y, z);
        }

        Vector3f offset(TileMesh mesh, double camX, double camY, double camZ) {
            if (this.usedOffsets == this.offsets.size()) {
                this.offsets.add(new Vector3f());
            }
            return this.offsets.get(this.usedOffsets++).set((float) (mesh.pos.minX() - camX), (float) -camY, (float) (mesh.pos.minZ() - camZ));
        }

        void set(int tile, DynamicGpuData.Transform transform) {
            this.own[tile] = transform;
        }

        void addClipped(int tile, DynamicGpuData.Transform transform) {
            if (this.clippedCount[tile] == 0) {
                this.clippedFirst[tile] = this.tiles + this.clipped.size();
            }
            this.clippedCount[tile]++;
            this.clipped.add(transform);
        }

        /** Writes all transforms to the GPU; the array has to be as long as there are of them, it is kept while that stays the same. */
        GpuBufferSlice[] write() {
            int total = this.tiles + this.clipped.size();
            DynamicGpuData.Transform[] all = this.all;
            if (all == null || all.length != total) {
                all = new DynamicGpuData.Transform[total];
                this.all = all;
            }
            System.arraycopy(this.own, 0, all, 0, this.tiles);
            for (int c = 0; c < this.clipped.size(); c++) {
                all[this.tiles + c] = this.clipped.get(c);
            }
            GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(all);
            // The transforms are written; nothing keeps them alive until the next frame.
            java.util.Arrays.fill(all, null);
            java.util.Arrays.fill(this.own, 0, this.tiles, null);
            return slices;
        }
    }

    private final Frame frame = new Frame();
    private final LodFog lodFog = new LodFog();
    private final Vector4f modulator = new Vector4f();

    public String lastFrameStats() {
        return this.lastFrameStats;
    }

    private @Nullable CompiledRenderPipeline compiledPipeline(RenderPipeline renderPipeline) {
        if (this.pipelineFailed) {
            return null;
        }
        CompiledRenderPipeline compiled = null;
        try {
            compiled = RenderSystem.getCompiledPipelineNullable(renderPipeline);
        } catch (RuntimeException e) {
            LOGGER.error("FarPlane LOD pipeline {} failed to load", renderPipeline.getLocation(), e);
        }
        if (compiled == null) {
            LOGGER.error("FarPlane LOD shaders are not supported by this graphics driver, LOD terrain is disabled");
            this.pipelineFailed = true;
        } else if (LIT_PIPELINES.contains(renderPipeline)) {
            this.litPipelines.add(compiled);
        }
        return compiled;
    }

    private GpuBuffer roughIndices() {
        if (this.roughIndices == null || this.roughIndices.isClosed()) {
            this.roughIndices = RenderSystem.getDevice().createBuffer(() -> "FarPlane LOD indices", GpuBuffer.USAGE_INDEX, TileMeshBuilder.buildIndices());
        }
        return this.roughIndices;
    }

    /**
     * The quad of the bottom of the world, around the camera as far as anything is drawn, in the color the rough terrain gives the
     * water of the biome the camera is in. Made again only when that color changes.
     */
    private GpuBuffer worldFloor(Minecraft minecraft, double camX, double camY, double camZ) {
        int water = minecraft.level == null ? 0xFF3F76E4
            : ARGB.opaque(minecraft.level.getBiome(BlockPos.containing(camX, camY, camZ)).value().getWaterColor());
        int color = ARGB.scaleRGB(water, FLOOR_SHADE);
        if (this.floorVertices == null || this.floorVertices.isClosed() || this.floorColor != color) {
            ByteBuffer data = ByteBuffer.allocateDirect(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()).order(ByteOrder.nativeOrder());
            float[] corners = {-FLOOR_EXTENT, -FLOOR_EXTENT, -FLOOR_EXTENT, FLOOR_EXTENT, FLOOR_EXTENT, FLOOR_EXTENT, FLOOR_EXTENT, -FLOOR_EXTENT};
            for (int c = 0; c < corners.length; c += 2) {
                // Facing up, wound like the top faces of the detailed tiles.
                data.putFloat(corners[c]).putFloat(0.0F).putFloat(corners[c + 1]);
                data.put((byte) ARGB.red(color)).put((byte) ARGB.green(color)).put((byte) ARGB.blue(color)).put((byte) 255);
            }
            data.flip();
            if (this.floorVertices != null) {
                this.floorVertices.close();
            }
            this.floorVertices = RenderSystem.getDevice().createBuffer(() -> "FarPlane world floor", GpuBuffer.USAGE_VERTEX, data);
            this.floorColor = color;
        }
        return this.floorVertices;
    }

    /** How much farther back the bottom of the world lies for the depth test inside vanilla's pass, see FLOOR_PIPELINE. */
    private static final float FLOOR_PRIORITY = 16.0F;
    /**
     * The water wall around the camera, see WALL_PIPELINE: a ring of quads standing at the radius from the water surface (y 0) down by
     * {@code depth}, and a ring of quads lying on the surface from the radius out to the world border. Built again only when the radius
     * or the depth changes.
     */
    private GpuBuffer waterWall(float radius, float depth) {
        if (this.wallVertices == null || this.wallVertices.isClosed() || this.wallRadius != radius || this.wallDepth != depth) {
            int vertexSize = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
            ByteBuffer data = ByteBuffer.allocateDirect(WALL_SEGMENTS * 2 * 4 * vertexSize).order(ByteOrder.nativeOrder());
            for (int part = 0; part < 2; part++) {
                for (int i = 0; i < WALL_SEGMENTS; i++) {
                    double a0 = Math.PI * 2.0 * i / WALL_SEGMENTS;
                    double a1 = Math.PI * 2.0 * (i + 1) / WALL_SEGMENTS;
                    float x0 = (float) Math.cos(a0);
                    float z0 = (float) Math.sin(a0);
                    float x1 = (float) Math.cos(a1);
                    float z1 = (float) Math.sin(a1);
                    if (part == 0) {
                        wallVertex(data, x0 * radius, -depth, z0 * radius);
                        wallVertex(data, x0 * radius, 0.0F, z0 * radius);
                        wallVertex(data, x1 * radius, 0.0F, z1 * radius);
                        wallVertex(data, x1 * radius, -depth, z1 * radius);
                    } else {
                        wallVertex(data, x0 * radius, 0.0F, z0 * radius);
                        wallVertex(data, x0 * FLOOR_EXTENT, 0.0F, z0 * FLOOR_EXTENT);
                        wallVertex(data, x1 * FLOOR_EXTENT, 0.0F, z1 * FLOOR_EXTENT);
                        wallVertex(data, x1 * radius, 0.0F, z1 * radius);
                    }
                }
            }
            data.flip();
            if (this.wallVertices != null) {
                this.wallVertices.close();
            }
            this.wallVertices = RenderSystem.getDevice().createBuffer(() -> "FarPlane water wall", GpuBuffer.USAGE_VERTEX, data);
            this.wallRadius = radius;
            this.wallDepth = depth;
        }
        return this.wallVertices;
    }

    private static void wallVertex(ByteBuffer data, float x, float y, float z) {
        // The shader paints the wall itself; the color only has to be opaque.
        data.putFloat(x).putFloat(y).putFloat(z).put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1);
    }

    /** The top of the water the camera is in: the first block up from it that is not water, looked for again when the camera moves. */
    private float waterSurface(net.minecraft.world.level.Level level, double x, double y, double z) {
        long key = BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (key != this.surfaceKey) {
            this.surfaceKey = key;
            BlockPos.MutableBlockPos pos = this.surfacePos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            int top = Math.min(level.getMaxY(), pos.getY() + WATER_SURFACE_SEARCH);
            while (pos.getY() < top && level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
                pos.move(net.minecraft.core.Direction.UP);
            }
            this.surfaceTop = pos.getY();
        }
        return this.surfaceTop;
    }

    private @Nullable GpuBuffer wallVertices;
    private float wallRadius;
    private float wallDepth;
    private final BlockPos.MutableBlockPos surfacePos = new BlockPos.MutableBlockPos();
    private long surfaceKey = Long.MIN_VALUE;
    private float surfaceTop;
    /** Quads of the wall and of its lid each; enough for a round edge at the distance of the detailed tiles. */
    private static final int WALL_SEGMENTS = 64;
    /** The terrain starts to fade into the wall at this part of its radius; the shader has the same value. */
    private static final float WALL_FADE_START = 0.7F;
    /** How dark the wall gets with the camera deep down, and over how many blocks of depth it gets most of the way there. */
    private static final float WALL_CAMERA_DEEPEST = 0.5F;
    private static final float WALL_CAMERA_DEPTH = 32.0F;
    /** How far up the water surface is looked for. */
    private static final int WATER_SURFACE_SEARCH = 256;
    /** How far the bottom of the world reaches around the camera: the world border. */
    private static final float FLOOR_EXTENT = 3.0E7F;
    /**
     * The shade of the rough terrain's water against the plain water color (see TileMeshBuilder.WATER_SHADE and the shader), darker:
     * deep water. Not as dark as the deepest seabed: where nothing at all is drawn (gaps at the edge of vanilla's chunks, seen from
     * high up), the floor shows without water over it, and near black stands out there far more than the color of deep water.
     */
    private static final float FLOOR_SHADE = 0.85F * 0.92F * 0.5F;

    /** Index buffer for detailed tiles, which are made of quads: 0 1 2, 0 2 3 for every four vertices. Grows when needed. */
    private GpuBuffer quadIndices(int quads) {
        if (this.quadIndices == null || this.quadIndices.isClosed() || this.quadIndexCapacity < quads) {
            int capacity = Math.max(4096, Integer.highestOneBit(Math.max(1, quads - 1)) << 1);
            ByteBuffer data = ByteBuffer.allocateDirect(capacity * 6 * 4).order(ByteOrder.nativeOrder());
            for (int quad = 0; quad < capacity; quad++) {
                int first = quad * 4;
                data.putInt(first).putInt(first + 1).putInt(first + 2);
                data.putInt(first).putInt(first + 2).putInt(first + 3);
            }
            data.flip();
            if (this.quadIndices != null) {
                this.quadIndices.close();
            }
            this.quadIndices = RenderSystem.getDevice().createBuffer(() -> "FarPlane detail quad indices", GpuBuffer.USAGE_INDEX, data);
            this.quadIndexCapacity = capacity;
        }
        return this.quadIndices;
    }

    /**
     * Nearest texels with mipmaps, like vanilla's blocks, and with vanilla's anisotropic filtering when it is on: without it textures
     * seen at a grazing angle blur into a flat color, while the vanilla blocks next to them stay sharp.
     */
    private GpuSampler atlasSampler() {
        Minecraft minecraft = Minecraft.getInstance();
        int anisotropy = minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? minecraft.options.maxAnisotropyValue() : 1;
        if (this.atlasSampler == null || this.samplerAnisotropy != anisotropy) {
            if (this.atlasSampler != null) {
                this.atlasSampler.close();
            }
            this.atlasSampler = RenderSystem.getDevice()
                .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, anisotropy, OptionalDouble.empty());
            this.samplerAnisotropy = anisotropy;
        }
        return this.atlasSampler;
    }

    public void close() {
        this.lodFog.close();
        if (this.atlasSampler != null) {
            this.atlasSampler.close();
            this.atlasSampler = null;
        }
        if (this.roughIndices != null) {
            this.roughIndices.close();
            this.roughIndices = null;
        }
        if (this.quadIndices != null) {
            this.quadIndices.close();
            this.quadIndices = null;
        }
        if (this.floorVertices != null) {
            this.floorVertices.close();
            this.floorVertices = null;
        }
        if (this.projectionBuffer != null) {
            this.projectionBuffer.close();
            this.projectionBuffer = null;
        }
    }
}
