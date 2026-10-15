package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
            && camera.fogType == FogType.NONE
            && !camera.isPanoramicMode
            && camera.projectionMatrix.m23() != 0.0F;
    }

    public void render(RenderTarget target, CameraRenderState camera, GpuBufferSlice fog) {
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
        // Near tiles first, so the depth test rejects most hidden fragments early.
        this.visible.sort(Comparator.comparingDouble(mesh -> mesh.sortDistance));

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
        float brightness = 0.12F + 0.88F * Mth.clamp(skyFactor, 0.0F, 1.0F);
        Vector4f modulator = new Vector4f(brightness, brightness, brightness, 1.0F);

        DynamicGpuData.Transform[] transforms = new DynamicGpuData.Transform[this.visible.size()];
        for (int i = 0; i < transforms.length; i++) {
            TileMesh mesh = this.visible.get(i);
            // TextureMat is unused by this shader, so it carries per-tile parameters: [0][0] inner cut, [1][1] outer cut, [2][2] depth
            // priority. Rough tiles give way to detailed ones wherever both are drawn.
            Matrix4f cut = new Matrix4f().zero();
            cut.m00(mesh.discardRadius);
            cut.m11(mesh.keepRadius);
            cut.m22(mesh.detail ? 1.0F : ROUGH_DEPTH_PRIORITY);
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, modulator, offset(mesh, camX, camY, camZ), cut);
        }
        GpuBufferSlice[] transformSlices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);

        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("farplane lod");
        }
        GpuBufferSlice projection = this.projectionBuffer.getBuffer(this.lodProjection);
        GpuBuffer roughIndices = this.roughIndices();
        GpuBuffer quadIndices = maxQuads > 0 ? this.quadIndices(maxQuads) : null;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
            () -> "FarPlane LOD terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.of(0.0)
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(pipeline);
            pass.setUniform("Projection", projection);
            pass.setUniform("Fog", fog);
            this.drawAll(pass, this.visible, transformSlices, roughIndices, quadIndices, pipeline, this.compiledPipeline(DETAIL_PIPELINE));
        }
        encoder.clearDepthTexture(target.getDepthTexture(), 0.0);

        this.writeVanillaDepth(target, camera, fog, encoder, camX, camY, camZ, zeroToOne, roughIndices, quadIndices, minecraft);
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
        for (int i = 0; i < transforms.length; i++) {
            TileMesh mesh = this.depthTiles.get(i);
            // Detailed tiles leave out every chunk vanilla draws and match real blocks, so they write their depth everywhere: vanilla
            // chunks behind them stay hidden. Rough terrain only guesses the surface, so near the player it never hides vanilla.
            float hideInside = mesh.detail ? 0.0F : vanillaRadius;
            Vector4f parameters = new Vector4f(hideInside, zeroToOne ? 1.0F : 0.0F, mesh.discardRadius, mesh.keepRadius);
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, parameters, offset(mesh, camX, camY, camZ), vanillaProjection);
        }
        GpuBufferSlice[] transformSlices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        GpuBufferSlice projection = this.projectionBuffer.getBuffer(this.lodProjection);
        try (RenderPass pass = encoder.createRenderPass(
            () -> "FarPlane LOD vanilla depth", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(depthPipeline);
            pass.setUniform("Projection", projection);
            pass.setUniform("Fog", fog);
            this.drawAll(pass, this.depthTiles, transformSlices, roughIndices, quadIndices, depthPipeline, this.compiledPipeline(DEPTH_DETAIL_PIPELINE));
        }
    }

    private void drawAll(
        RenderPass pass, List<TileMesh> meshes, GpuBufferSlice[] transforms, GpuBuffer roughIndices, @Nullable GpuBuffer quadIndices,
        CompiledRenderPipeline roughPipeline, @Nullable CompiledRenderPipeline detailPipeline
    ) {
        Boolean boundDetail = null;
        for (int i = 0; i < transforms.length; i++) {
            TileMesh mesh = meshes.get(i);
            if (mesh.detail && (quadIndices == null || detailPipeline == null)) {
                continue;
            }
            if (boundDetail == null || boundDetail != mesh.detail) {
                if (mesh.detail) {
                    pass.setPipeline(detailPipeline);
                    pass.setIndexBuffer(quadIndices, IndexType.INT);
                } else {
                    pass.setPipeline(roughPipeline);
                    pass.setIndexBuffer(roughIndices, IndexType.SHORT);
                }
                boundDetail = mesh.detail;
            }
            pass.setUniform("DynamicTransforms", transforms[i]);
            pass.setVertexBuffer(0, mesh.vertices.slice());
            int[] ranges = mesh.drawRanges;
            if (ranges == null) {
                pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0);
            } else {
                for (int r = 0; r + 1 < ranges.length; r += 2) {
                    pass.drawIndexed(ranges[r + 1], 1, ranges[r], 0, 0);
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

    public void close() {
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
