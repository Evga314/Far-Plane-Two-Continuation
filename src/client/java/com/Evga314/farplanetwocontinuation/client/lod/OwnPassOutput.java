package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.surface.LodVertex;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceIndices;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceTile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
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
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the rough tiles of the new renderer (see {@link com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceMesher}) in the
 * LOD terrain's own pass: the way out for OpenGL on phones and for Iris without a shader pack. Stage 3 of the rewrite: it draws in
 * the pass LodRenderer opens, beside the detailed tiles drawn the old way.
 * <p>
 * The ground first, nearest tiles first, with nothing dropped per pixel, then the water over it, farthest first, blended and drawn a
 * little closer than it lies, so where the ground goes under it, nearly level with it, the coast stays where it is. Each tile is one
 * draw call per layer: the chunk columns it draws are its index ranges, drawn together. A chunk column vanilla or the detailed tiles
 * draw is left out (for now by the chunk mask, see {@link #owned}).
 * <p>
 * What the shaders need comes in two named uniform blocks, see surface.glsl: once a frame, and per tile.
 */
final class OwnPassOutput implements AutoCloseable {
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
    /** How far the water is drawn closer than it lies, see the class: as vanilla draws the cracks of blocks being broken. */
    private static final float WATER_BIAS_SCALE = 1.0F;
    private static final float WATER_BIAS_CONSTANT = 4.0F;
    /** The depth left for vanilla is pushed this much away, so its blocks at the same place win, see surface_depth.fsh. */
    private static final float VANILLA_DEPTH_PUSH = 1.004F;

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

    static final RenderPipeline GROUND = RenderPipelines.registerOptional(
        base("surface_ground", "core/surface_ground")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build()
    );
    static final RenderPipeline WATER = RenderPipelines.registerOptional(
        base("surface_water", "core/surface_water")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, WATER_BIAS_SCALE, WATER_BIAS_CONSTANT))
            .build()
    );
    static final RenderPipeline DEPTH = RenderPipelines.registerOptional(
        base("surface_depth", "core/surface_depth")
            .withShaderDefine("DEPTH")
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build()
    );

    /** What the frame block holds, see surface.glsl. */
    private static final class FrameData implements DynamicGpuDataStorage.DynamicGpuData {
        static final int SIZE = 64 + 64 + 3 * 16;
        final Matrix4f view = new Matrix4f();
        final Matrix4f vanillaProjection = new Matrix4f();
        final Vector4f light = new Vector4f();
        final Vector4f sky = new Vector4f();
        final Vector4f params = new Vector4f();

        @Override
        public void write(ByteBuffer buffer) {
            this.view.get(buffer.position(), buffer);
            this.vanillaProjection.get(buffer.position() + 64, buffer);
            this.light.get(buffer.position() + 128, buffer);
            this.sky.get(buffer.position() + 144, buffer);
            this.params.get(buffer.position() + 160, buffer);
            buffer.position(buffer.position() + SIZE);
        }
    }

    /** What the tile block holds, see surface.glsl. Kept from frame to frame, one per tile drawn. */
    private static final class TileData implements DynamicGpuDataStorage.DynamicGpuData {
        static final int SIZE = 16;
        float x;
        float y;
        float z;
        float scale;

        @Override
        public void write(ByteBuffer buffer) {
            buffer.putFloat(this.x).putFloat(this.y).putFloat(this.z).putFloat(this.scale);
        }
    }

    private final LodManager manager;
    /** Made with the first frame drawn: the GPU is not there yet when the mod starts. */
    private @Nullable DynamicGpuDataStorageMapped<FrameData> frames;
    private @Nullable DynamicGpuDataStorageMapped<TileData> tileStorage;
    private final FrameData frame = new FrameData();
    private TileData[] tileData = new TileData[0];
    private TileData[] written = new TileData[0];
    private GpuBufferSlice @Nullable [] tileSlices;
    private @Nullable GpuBufferSlice frameSlice;
    /** The shared triangles of the ground, one list per number of chunk columns, see {@link SurfaceIndices}; made once. */
    private final @Nullable GpuBuffer[] groundIndices = new GpuBuffer[5];
    /** The tiles drawn this frame, nearest first, and per tile which of its chunk columns are drawn. */
    private final java.util.ArrayList<TileMesh> tiles = new java.util.ArrayList<>();
    private boolean[][] drawn = new boolean[0][];
    /** What one draw call of a tile's ranges takes, kept. */
    private PointerBuffer offsets = MemoryUtil.memAllocPointer(256);
    private IntBuffer counts = MemoryUtil.memAllocInt(256);
    private IntBuffer baseVertices = MemoryUtil.memCallocInt(256);

    OwnPassOutput(LodManager manager) {
        this.manager = manager;
    }

    /**
     * Before the pass opens, as nothing may be written to buffers while it is: what is drawn of the rough tiles of the new mesh among
     * {@code visible} (nearest first), and the blocks of the frame and the tiles.
     */
    void prepare(
        List<TileMesh> visible, double camX, double camY, double camZ, Matrix4fc viewRotation, Matrix4fc vanillaProjection, boolean zeroToOne,
        Vector4f light, boolean overworldSky, float noSkyRed, float noSkyGreen, float noSkyBlue, float canopyStart, float waterBlur
    ) {
        if (this.frames == null) {
            this.frames = new DynamicGpuDataStorageMapped<>("FarPlane surface frame", FrameData.SIZE, GpuBuffer.USAGE_UNIFORM, 1);
            this.tileStorage = new DynamicGpuDataStorageMapped<>("FarPlane surface tiles", TileData.SIZE, GpuBuffer.USAGE_UNIFORM, 64);
        }
        this.frames.endFrame();
        this.tileStorage.endFrame();
        this.tiles.clear();
        for (TileMesh mesh : visible) {
            if (mesh.surface != null) {
                this.tiles.add(mesh);
            }
        }
        this.tileSlices = null;
        this.frameSlice = null;
        if (this.tiles.isEmpty()) {
            return;
        }
        FrameData frame = this.frame;
        frame.view.set(viewRotation);
        frame.vanillaProjection.set(vanillaProjection);
        frame.light.set(light);
        frame.sky.set(noSkyRed, noSkyGreen, noSkyBlue, overworldSky ? 1.0F : 0.0F);
        frame.params.set(canopyStart, waterBlur, zeroToOne ? 1.0F : 0.0F, VANILLA_DEPTH_PUSH);
        this.frameSlice = this.frames.writeData(frame);

        int count = this.tiles.size();
        if (this.tileData.length < count) {
            TileData[] grown = java.util.Arrays.copyOf(this.tileData, Math.max(count, this.tileData.length * 2));
            for (int i = this.tileData.length; i < grown.length; i++) {
                grown[i] = new TileData();
            }
            this.tileData = grown;
            this.drawn = java.util.Arrays.copyOf(this.drawn, grown.length);
        }
        LodManager.ChunkMask mask = this.manager.chunkMask();
        // The storage writes an array as long as it is: one of that length, kept while the count stays.
        if (this.written.length != count) {
            this.written = new TileData[count];
        }
        TileData[] written = this.written;
        for (int i = 0; i < count; i++) {
            TileMesh mesh = this.tiles.get(i);
            SurfaceTile surface = mesh.surface;
            TileData data = this.tileData[i];
            data.x = (float) (mesh.pos.minX() - camX);
            data.y = (float) (surface.baseY() - camY);
            data.z = (float) (mesh.pos.minZ() - camZ);
            data.scale = (1 << mesh.pos.level()) / LodVertex.XZ_UNITS;
            written[i] = data;
            int groups = surface.groups() * surface.groups();
            if (this.drawn[i] == null || this.drawn[i].length < groups) {
                this.drawn[i] = new boolean[Math.max(groups, 16)];
            }
            owned(mesh, mask, camY, this.drawn[i]);
        }
        this.tileSlices = this.tileStorage.writeData(written);
    }

    /**
     * Which chunk columns of a tile it draws itself: not those vanilla draws, as far above and below the camera as it draws sections,
     * nor those the detailed tiles draw. For now from the chunk mask, as the old renderer; stage 4 gives every chunk one owner.
     */
    private static void owned(TileMesh mesh, LodManager.ChunkMask mask, double camY, boolean[] drawn) {
        SurfaceTile surface = mesh.surface;
        int groups = surface.groups();
        java.util.Arrays.fill(drawn, 0, groups * groups, true);
        int half = mask.half();
        if (half < 0 || groups == 1) {
            return;
        }
        int side = 2 * half + 1;
        int[] values = mask.values();
        // Vanilla draws no sections farther above or below the camera than its reach: there the tile's ground is the rough terrain's.
        boolean withinReach = camY - mesh.maxY < mask.reach() && mesh.minY - camY < mask.reach();
        long firstChunkX = Math.floorDiv(mesh.pos.minX(), 16L);
        long firstChunkZ = Math.floorDiv(mesh.pos.minZ(), 16L);
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                long x = firstChunkX + gx - mask.originX() + half;
                long z = firstChunkZ + gz - mask.originZ() + half;
                if (x < 0 || z < 0 || x >= side || z >= side || values.length != side * side) {
                    continue;
                }
                int value = values[(int) x + (int) z * side];
                boolean vanilla = withinReach && (value & (LodManager.ChunkMask.VANILLA | LodManager.ChunkMask.VANILLA_EDGE)) != 0;
                boolean detailed = (value & LodManager.ChunkMask.DETAILED) != 0 && (value & LodManager.ChunkMask.DETAILED_EDGE) == 0;
                if (vanilla || detailed) {
                    drawn[gx + gz * groups] = false;
                }
            }
        }
    }

    boolean hasTiles() {
        return this.frameSlice != null;
    }

    /** The ground of every tile, nearest first. */
    void drawGround(RenderPass pass, @Nullable CompiledRenderPipeline pipeline) {
        this.draw(pass, pipeline, false, Double.MAX_VALUE);
    }

    /** The water of every tile, farthest first, so it blends over what lies behind it. */
    void drawWater(RenderPass pass, @Nullable CompiledRenderPipeline pipeline) {
        this.draw(pass, pipeline, true, Double.MAX_VALUE);
    }

    /** The ground's depth for vanilla, of the tiles within {@code distance} of the camera, see surface_depth.fsh. */
    void drawDepth(RenderPass pass, @Nullable CompiledRenderPipeline pipeline, double distance) {
        this.draw(pass, pipeline, false, distance);
    }

    private void draw(RenderPass pass, @Nullable CompiledRenderPipeline pipeline, boolean water, double distance) {
        GpuBufferSlice[] slices = this.tileSlices;
        if (pipeline == null || slices == null || this.frameSlice == null) {
            return;
        }
        pass.setPipeline(pipeline);
        pass.setUniform("SurfaceFrame", this.frameSlice);
        pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        GpuBuffer boundIndices = null;
        int count = this.tiles.size();
        for (int n = 0; n < count; n++) {
            int i = water ? count - 1 - n : n;
            TileMesh mesh = this.tiles.get(i);
            SurfaceTile surface = mesh.surface;
            if (mesh.vertices.isClosed() || water && !surface.hasWater() || mesh.sortDistance >= distance) {
                continue;
            }
            GpuBuffer indices;
            long base;
            int[] ranges;
            if (water || !surface.sharedGround()) {
                indices = mesh.vertices.buffer;
                base = mesh.vertices.offset + surface.vertexBytes();
                ranges = water ? surface.waterOffsets() : surface.groundOffsets();
            } else {
                indices = this.groundIndices(surface.groups());
                base = 0L;
                ranges = surface.groundOffsets();
            }
            int draws = this.ranges(ranges, this.drawn[i], base);
            if (draws == 0) {
                continue;
            }
            if (indices != boundIndices) {
                pass.setIndexBuffer(indices, IndexType.SHORT);
                boundIndices = indices;
            }
            pass.setUniform("SurfaceTile", slices[i]);
            pass.setVertexBuffer(0, mesh.vertexSlice());
            if (this.separateMultiDraw()) {
                pass.multiDrawIndexed(this.offsets, this.counts, this.baseVertices, draws);
            } else {
                // Vulkan has the ranges in another layout, which nothing in the game uses yet: one call each, a few per tile at most,
                // as the chunk columns next to each other are joined.
                for (int d = 0; d < draws; d++) {
                    pass.drawIndexed(this.counts.get(d), 1, (int) (this.offsets.get(d) / 2L), 0, 0);
                }
            }
            if (PerfLog.ON) {
                for (int d = 0; d < draws; d++) {
                    PerfLog.draw(this.counts.get(d));
                }
            }
        }
    }

    /**
     * The index ranges of the chunk columns a tile draws, those next to each other joined, into {@link #offsets} (in bytes) and
     * {@link #counts}; returns how many.
     */
    private int ranges(int[] groupOffsets, boolean[] drawn, long base) {
        int groups = groupOffsets.length - 1;
        if (this.counts.capacity() < groups) {
            MemoryUtil.memFree(this.counts);
            MemoryUtil.memFree(this.baseVertices);
            MemoryUtil.memFree(this.offsets);
            this.offsets = MemoryUtil.memAllocPointer(groups);
            this.counts = MemoryUtil.memAllocInt(groups);
            this.baseVertices = MemoryUtil.memCallocInt(groups);
        }
        int draws = 0;
        int start = -1;
        for (int g = 0; g <= groups; g++) {
            boolean take = g < groups && drawn[g] && groupOffsets[g + 1] > groupOffsets[g];
            if (take && start < 0) {
                start = g;
            } else if (!take && start >= 0) {
                int first = groupOffsets[start];
                int count = groupOffsets[g] - first;
                if (count > 0) {
                    this.offsets.put(draws, base + 2L * first);
                    this.counts.put(draws, count);
                    draws++;
                }
                start = -1;
            }
        }
        return draws;
    }

    /** Whether the device draws a tile's ranges in one call, given as separate lists (OpenGL); 0 not asked yet, 1 yes, 2 no. */
    private int separateMultiDraw;

    private boolean separateMultiDraw() {
        if (this.separateMultiDraw == 0) {
            this.separateMultiDraw = RenderSystem.getDevice().getDeviceInfo().features().multiDrawDirectSeparate() ? 1 : 2;
        }
        return this.separateMultiDraw == 1;
    }

    private GpuBuffer groundIndices(int groups) {
        int slot = Integer.numberOfTrailingZeros(groups);
        GpuBuffer buffer = this.groundIndices[slot];
        if (buffer == null) {
            ByteBuffer data = SurfaceIndices.ground(groups).indices();
            buffer = RenderSystem.getDevice().createBuffer(() -> "FarPlane surface ground indices x" + groups, GpuBuffer.USAGE_INDEX, data);
            this.groundIndices[slot] = buffer;
        }
        return buffer;
    }

    @Override
    public void close() {
        if (this.frames != null) {
            this.frames.close();
            this.tileStorage.close();
            this.frames = null;
            this.tileStorage = null;
        }
        for (int i = 0; i < this.groundIndices.length; i++) {
            if (this.groundIndices[i] != null) {
                this.groundIndices[i].close();
                this.groundIndices[i] = null;
            }
        }
        MemoryUtil.memFree(this.offsets);
        MemoryUtil.memFree(this.counts);
        MemoryUtil.memFree(this.baseVertices);
    }
}
