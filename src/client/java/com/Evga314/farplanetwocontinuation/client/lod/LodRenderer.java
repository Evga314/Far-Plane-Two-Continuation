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
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
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
    /** Light of LOD terrain under the End sky: the greenish light vanilla blocks get there, so both look the same at the border. */
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

    public static final RenderPipeline PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false)
            .build()
    );

    /** Writes only depth, converted to vanilla's projection, for tiles beyond the vanilla render distance. */
    public static final RenderPipeline DEPTH_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_depth"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withShaderDefine("DEPTH_ONLY")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .build()
    );

    /** Detailed tiles are closed boxes facing outwards; culling back faces keeps coplanar walls of neighbours from flickering. */
    public static final RenderPipeline DETAIL_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
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
            .withShaderDefine("TEXTURED")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, TEXTURED_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(true)
            .build()
    );

    /** Translucent water of detailed tiles, drawn after everything else without writing depth, so the ground under it shows. */
    public static final RenderPipeline DETAIL_WATER_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "pipeline/lod_terrain_detail_water"))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
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

    private final LodManager manager;
    private @Nullable GpuBuffer roughIndices;
    private @Nullable GpuBuffer quadIndices;
    private int quadIndexCapacity;
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    private @Nullable Matrix4f bobbedProjection;
    private boolean pipelineFailed;
    private @Nullable GpuSampler atlasSampler;
    private int samplerAnisotropy;
    private String lastFrameStats = "none";

    private final Matrix4f lodProjection = new Matrix4f();
    private final Matrix4f bobMatrix = new Matrix4f();
    private final Matrix4f cullMatrix = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
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
            && camera.fogType == FogType.NONE
            && !camera.isPanoramicMode
            && camera.projectionMatrix.m23() != 0.0F;
    }

    public void render(RenderTarget target, CameraRenderState camera, GpuBufferSlice fog) {
        if (!PerfLog.ON) {
            this.renderLod(target, camera, fog);
            return;
        }
        long start = System.nanoTime();
        this.renderLod(target, camera, fog);
        PerfLog.renderTime(System.nanoTime() - start);
    }

    private void renderLod(RenderTarget target, CameraRenderState camera, GpuBufferSlice fog) {
        CompiledRenderPipeline pipeline = this.compiledPipeline(PIPELINE);
        if (pipeline == null || target.getDepthTextureView() == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;

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
        this.visible.clear();
        this.depthTiles.clear();
        double nearest = Double.MAX_VALUE;
        double farthest = 0.0;
        int maxQuads = 0;
        double vanillaFar = camera.depthFar;
        for (TileMesh mesh : this.manager.drawList()) {
            if (mesh.vertices.isClosed()) {
                continue;
            }
            double minX = mesh.pos.minX() - camX;
            double minZ = mesh.pos.minZ() - camZ;
            double size = mesh.pos.size();
            double minY = mesh.minY - camY;
            double maxY = mesh.maxY - camY;
            if (!this.frustum.testAab((float) minX, (float) minY, (float) minZ, (float) (minX + size), (float) maxY, (float) (minZ + size))) {
                continue;
            }
            this.visible.add(mesh);
            double dx = Math.max(0.0, Math.max(minX, -(minX + size)));
            double dy = Math.max(0.0, Math.max(minY, -maxY));
            double dz = Math.max(0.0, Math.max(minZ, -(minZ + size)));
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        // The haze over the horizon matches the overworld sky disc; skies without it would get a pale band instead.
        float haze = hasSky ? 1.0F : 0.0F;
        Vector4f modulator = hasSky ? new Vector4f(brightness, brightness, brightness, 1.0F) : new Vector4f(END_LIGHT);
        float textureLevel = this.manager.config().detail().blocks().fixedTextureLevel();
        float ringRadius = (float) this.manager.detailRingRadius();
        float canopyStart = (float) this.manager.canopyStart();

        List<DynamicGpuData.Transform> transformList = new ArrayList<>(this.visible.size() + 16);
        int[] clippedCounts = new int[this.visible.size()];
        for (int i = 0; i < this.visible.size(); i++) {
            TileMesh mesh = this.visible.get(i);
            // TextureMat is unused by this shader, so it carries per-tile parameters: [0][0] inner cut, [1][1] outer cut, [2][2] depth
            // priority, [3][3] horizon haze. Rough tiles give way to detailed ones wherever both are drawn.
            Matrix4f cut = new Matrix4f().zero();
            cut.m00(mesh.discardRadius);
            cut.m11(mesh.keepRadius);
            // [1][3]: the width of the band inside the outer cut where detailed tiles fade into the rough terrain.
            cut.m13(mesh.fadeWidth);
            // [2][3]: rough tiles lay their tree tops flat closer than this, where detailed tiles or vanilla's chunks are drawn.
            cut.m23(mesh.detail ? 0.0F : canopyStart);
            cut.m22(mesh.detail ? 1.0F : ROUGH_DEPTH_PRIORITY);
            // [2][1]: within this horizontal distance rough tiles lie under detailed ones and are pushed much farther back, see the shader.
            cut.m21(mesh.detail ? 0.0F : ringRadius);
            // [2][0]: the edge of the detailed tiles, where their water turns into the flat water of the rough terrain.
            cut.m20(mesh.detail ? ringRadius : 0.0F);
            cut.m33(haze);
            // [1][0]: the texture level textured detailed tiles always use, below 0 for vanilla's choice by distance.
            cut.m10(textureLevel);
            if (!hasSky) {
                cut.m30(NO_SKY_FOG_RED).m31(NO_SKY_FOG_GREEN).m32(NO_SKY_FOG_BLUE);
            }
            transformList.add(new DynamicGpuData.Transform(camera.viewRotationMatrix, modulator, offset(mesh, camX, camY, camZ), cut));
            int[] clippedChunks = mesh.clippedChunks;
            if (clippedChunks != null) {
                // [0][3] = 1: drop the sections vanilla draws itself; [1][2] holds them as one bit each from section [0][2] up, [0][1] is
                // the camera height to turn positions back into world heights.
                for (int c = 0; c + 3 < clippedChunks.length; c += 4) {
                    Matrix4f clipped = new Matrix4f(cut);
                    clipped.m03(1.0F).m01((float) camY).m02(clippedChunks[c + 3]).m12(clippedChunks[c + 2]);
                    transformList.add(new DynamicGpuData.Transform(camera.viewRotationMatrix, modulator, offset(mesh, camX, camY, camZ), clipped));
                    clippedCounts[i]++;
                }
            }
        }
        GpuBufferSlice[] allSlices = RenderSystem.getDynamicUniforms().writeTransforms(transformList.toArray(DynamicGpuData.Transform[]::new));
        GpuBufferSlice[] transformSlices = new GpuBufferSlice[this.visible.size()];
        GpuBufferSlice[][] clippedSlices = new GpuBufferSlice[this.visible.size()][];
        for (int i = 0, slot = 0; i < this.visible.size(); i++) {
            transformSlices[i] = allSlices[slot++];
            clippedSlices[i] = new GpuBufferSlice[clippedCounts[i]];
            for (int c = 0; c < clippedCounts[i]; c++) {
                clippedSlices[i][c] = allSlices[slot++];
            }
        }

        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("farplane lod");
        }
        GpuBufferSlice projection = this.projectionBuffer.getBuffer(this.lodProjection);
        GpuBuffer roughIndices = this.roughIndices();
        GpuBuffer quadIndices = maxQuads > 0 ? this.quadIndices(maxQuads) : null;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (PerfLog.ON) {
            PerfLog.gpuMark(encoder, false);
        }
        try (RenderPass pass = encoder.createRenderPass(
            () -> "FarPlane LOD terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.of(0.0)
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(pipeline);
            pass.setUniform("Projection", projection);
            pass.setUniform("Fog", fog);
            Pipelines pipelines = new Pipelines(pipeline, this.compiledPipeline(DETAIL_PIPELINE), this.compiledPipeline(DETAIL_TEXTURED_PIPELINE), true, false, false);
            this.drawAll(pass, this.visible, transformSlices, clippedSlices, roughIndices, quadIndices, pipelines);
            if (this.visible.stream().anyMatch(mesh -> mesh.fadeWidth > 0.0F)) {
                // The fading edge of the detailed tiles, blended over the rough terrain drawn under it.
                Pipelines fade = new Pipelines(null, this.compiledPipeline(DETAIL_FADE_PIPELINE), this.compiledPipeline(DETAIL_TEXTURED_FADE_PIPELINE), true, false, true);
                this.drawAll(pass, this.visible, transformSlices, clippedSlices, roughIndices, quadIndices, fade);
            }
            CompiledRenderPipeline water = this.compiledPipeline(DETAIL_WATER_PIPELINE);
            if (water != null && this.visible.stream().anyMatch(mesh -> mesh.hasWater)) {
                this.drawAll(pass, this.visible, transformSlices, clippedSlices, roughIndices, quadIndices, new Pipelines(null, null, water, true, true, false));
            }
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
        Matrix4f vanillaProjection = this.bobbedProjection != null ? this.bobbedProjection : camera.projectionMatrix;
        float vanillaRadius = (minecraft.options.getEffectiveRenderDistance() + 1) * 16.0F;
        DynamicGpuData.Transform[] transforms = new DynamicGpuData.Transform[this.depthTiles.size()];
        List<DynamicGpuData.Transform> clippedList = new ArrayList<>();
        int[] clippedCounts = new int[transforms.length];
        for (int i = 0; i < transforms.length; i++) {
            TileMesh mesh = this.depthTiles.get(i);
            // Detailed tiles leave out every chunk vanilla draws and match real blocks, so they write their depth just behind them
            // everywhere. Rough terrain only guesses the surface: within vanilla's render distance its depth is pushed much farther, so
            // it hides clouds behind LOD mountains there, but not vanilla blocks.
            float pushInside = mesh.detail ? 0.0F : vanillaRadius;
            // Rough tiles never have an outer cut; a negative w there is where their tree tops start to rise instead.
            Vector4f parameters = new Vector4f(
                pushInside, zeroToOne ? 1.0F : 0.0F, mesh.discardRadius, mesh.detail ? mesh.keepRadius : -(float) this.manager.canopyStart()
            );
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, parameters, offset(mesh, camX, camY, camZ), vanillaProjection);
            int[] clippedChunks = mesh.clippedChunks;
            if (clippedChunks != null) {
                // Chunks vanilla draws partly write their depth only in the sections vanilla leaves out, or vanilla would draw mobs under
                // the ground there in front of it. ColorModulator: x = sections vanilla draws, one bit each from section CLIPPED_DEPTH -
                // z up, w = camera height.
                for (int c = 0; c + 3 < clippedChunks.length; c += 4) {
                    Vector4f clipped = new Vector4f(clippedChunks[c + 2], parameters.y, CLIPPED_DEPTH - clippedChunks[c + 3], (float) camY);
                    clippedList.add(new DynamicGpuData.Transform(camera.viewRotationMatrix, clipped, offset(mesh, camX, camY, camZ), vanillaProjection));
                    clippedCounts[i]++;
                }
            }
        }
        DynamicGpuData.Transform[] all = new DynamicGpuData.Transform[transforms.length + clippedList.size()];
        System.arraycopy(transforms, 0, all, 0, transforms.length);
        for (int c = 0; c < clippedList.size(); c++) {
            all[transforms.length + c] = clippedList.get(c);
        }
        GpuBufferSlice[] allSlices = RenderSystem.getDynamicUniforms().writeTransforms(all);
        GpuBufferSlice[] transformSlices = new GpuBufferSlice[transforms.length];
        GpuBufferSlice[][] clippedSlices = new GpuBufferSlice[transforms.length][];
        for (int i = 0, slot = transforms.length; i < transforms.length; i++) {
            transformSlices[i] = allSlices[i];
            clippedSlices[i] = new GpuBufferSlice[clippedCounts[i]];
            for (int c = 0; c < clippedCounts[i]; c++) {
                clippedSlices[i][c] = allSlices[slot++];
            }
        }
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
            this.drawAll(pass, this.depthTiles, transformSlices, clippedSlices, roughIndices, quadIndices, pipelines);
        }
    }

    /**
     * The pipelines of one pass: rough tiles, detailed tiles in flat colors and detailed tiles with block textures. A water pass only
     * draws the tiles with translucent water.
     */
    private record Pipelines(
        @Nullable CompiledRenderPipeline rough, @Nullable CompiledRenderPipeline detail, @Nullable CompiledRenderPipeline textured, boolean bindAtlas,
        boolean waterOnly, boolean fadeOnly
    ) {
        @Nullable CompiledRenderPipeline of(int kind) {
            return kind == KIND_ROUGH ? this.rough : kind == KIND_DETAIL ? this.detail : this.textured;
        }
    }

    /** Marks a transform of the depth pass as one of a chunk vanilla draws partly, see writeVanillaDepth and the shader. */
    private static final float CLIPPED_DEPTH = -1000.0F;

    private static final Comparator<TileMesh> DRAW_ORDER = Comparator.<TileMesh>comparingInt(mesh -> !mesh.detail ? 2 : mesh.textured ? 0 : 1)
        .thenComparingDouble(mesh -> mesh.sortDistance);

    private static final int KIND_ROUGH = 0;
    private static final int KIND_DETAIL = 1;
    private static final int KIND_TEXTURED = 2;

    private void drawAll(
        RenderPass pass, List<TileMesh> meshes, GpuBufferSlice[] transforms, GpuBufferSlice @Nullable [][] clippedTransforms, GpuBuffer roughIndices,
        @Nullable GpuBuffer quadIndices, Pipelines pipelines
    ) {
        int bound = -1;
        for (int i = 0; i < transforms.length; i++) {
            TileMesh mesh = meshes.get(i);
            int kind = !mesh.detail ? KIND_ROUGH : mesh.textured ? KIND_TEXTURED : KIND_DETAIL;
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
                if (kind == KIND_TEXTURED && pipelines.bindAtlas()) {
                    GpuTextureView atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
                    pass.setUniform("Sampler0", atlas, this.atlasSampler());
                }
                bound = kind;
            }
            pass.setUniform("DynamicTransforms", transforms[i]);
            pass.setVertexBuffer(0, mesh.vertices.slice());
            int[] ranges = mesh.drawRanges;
            if (ranges == null) {
                pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0);
                if (PerfLog.ON) {
                    PerfLog.draw();
                }
            } else {
                for (int r = 0; r + 1 < ranges.length; r += 2) {
                    pass.drawIndexed(ranges[r + 1], 1, ranges[r], 0, 0);
                    if (PerfLog.ON) {
                        PerfLog.draw();
                    }
                }
            }
            int[] clipped = mesh.clippedChunks;
            if (clipped != null && clippedTransforms != null && clippedTransforms[i] != null) {
                for (int c = 0; c < clippedTransforms[i].length && c * 4 + 1 < clipped.length; c++) {
                    pass.setUniform("DynamicTransforms", clippedTransforms[i][c]);
                    pass.drawIndexed(clipped[c * 4 + 1], 1, clipped[c * 4], 0, 0);
                    if (PerfLog.ON) {
                        PerfLog.draw();
                    }
                }
            }
        }
    }

    private static Vector3f offset(TileMesh mesh, double camX, double camY, double camZ) {
        return new Vector3f((float) (mesh.pos.minX() - camX), (float) -camY, (float) (mesh.pos.minZ() - camZ));
    }

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
        }
        return compiled;
    }

    private GpuBuffer roughIndices() {
        if (this.roughIndices == null || this.roughIndices.isClosed()) {
            this.roughIndices = RenderSystem.getDevice().createBuffer(() -> "FarPlane LOD indices", GpuBuffer.USAGE_INDEX, TileMeshBuilder.buildIndices());
        }
        return this.roughIndices;
    }

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
        if (this.projectionBuffer != null) {
            this.projectionBuffer.close();
            this.projectionBuffer = null;
        }
    }
}
