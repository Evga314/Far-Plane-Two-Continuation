package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.client.lod.surface.PackMesher;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceTile;
import com.Evga314.farplanetwocontinuation.mixin.iris.IrisLodRenderProgramAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Draws the new renderer's rough terrain ({@link FrameDrawList}) with a shader pack's DH programs, see IrisGlue: the third output beside
 * {@link OwnPassOutput} and {@link VanillaPassOutput}, for Iris with a pack that has them. The tiles are built for it (PackMesher),
 * and drawn into the pack's buffers right after vanilla's opaque terrain, before the pack's deferred passes, with the depth of their own
 * the pack reads as DH's (see IrisPrograms); their water after those passes, before vanilla's translucent things. The pack puts it
 * together with vanilla's blocks itself, as it does DH's terrain. Of the chunk columns only the rough terrain's own ones are drawn.
 * <p>
 * Iris's programs are OpenGL's, so this is raw OpenGL, inside vanilla's main pass: every state it touches is put back as it was, through
 * GlStateManager where that keeps it, so the pass goes on unaware. Made by name only when Iris is loaded, see IrisGlue.
 */
final class IrisOutput implements IrisGlue.Bridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_DRAW_BUFFERS = 8;
    /** DH's offset of faces that would fight, which the LOD terrain does not use. */
    private static final float MICRO_OFFSET = 0.01F;
    /** Stages of a frame, see {@link #withState}. */
    private static final int CLEAR = 0;
    private static final int TERRAIN = 1;
    private static final int WATER = 2;

    private int vertexArray;
    /** The ground was drawn this frame, and its water is still to come. */
    private boolean drawn;
    private boolean waterPending;
    private int debugCalls;
    private int debugWater;
    private int debugFrames;

    private boolean failed;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private final boolean[] blends = new boolean[MAX_DRAW_BUFFERS];
    /** For the matrices: made once, kept for the game. */
    private final FloatBuffer floats = MemoryUtil.memAllocFloat(16);

    @Override
    public boolean ready() {
        return !this.failed && Iris.isPackInUseQuick() && programs() != null;
    }

    private static @Nullable IrisPrograms programs() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof IrisRenderingPipeline iris && iris.getDHCompat() instanceof IrisPrograms.Holder holder) {
            return holder.farplane$programs();
        }
        return null;
    }

    @Override
    public void reload() {
        // Also before the world's pipeline is made: the pack is loaded with its defines when the game starts.
        if (Iris.getCurrentPack().isEmpty()) {
            return;
        }
        try {
            Iris.reload();
        } catch (Exception e) {
            LOGGER.warn("FarPlane could not reload the shader pack", e);
        }
    }

    @Override
    public void drawTerrain(FrameDrawList list) {
        IrisPrograms programs = programs();
        if (programs == null || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            return;
        }
        this.drawn = true;
        this.safely(programs, list, TERRAIN);
    }

    @Override
    public void endSolid() {
        IrisPrograms programs = programs();
        if (!this.drawn && programs != null && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            // The pack reads the depth whether the LOD terrain is drawn or not: no LOD terrain is cleared depth.
            this.safely(programs, null, CLEAR);
        }
        this.waterPending = this.drawn;
        this.drawn = false;
    }

    @Override
    public void drawWater(@Nullable FrameDrawList list) {
        IrisPrograms programs = programs();
        if (programs == null || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            return;
        }
        if (PerfLog.ON && ++this.debugCalls % 300 == 1) {
            LOGGER.info("[FarPlane debug] pack water: pending {}, list {}, tiles {}", this.waterPending, list != null,
                list == null ? -1 : list.tiles().size());
        }
        // The copy is taken drawn or not, so dhDepthTex1 is never a frame behind.
        this.safely(programs, this.waterPending ? list : null, WATER);
        this.waterPending = false;
    }

    private void safely(IrisPrograms programs, @Nullable FrameDrawList list, int stage) {
        if (this.failed) {
            return;
        }
        try {
            this.withState(programs, list, stage);
        } catch (RuntimeException | LinkageError e) {
            // Something of Iris is not as expected: the pack goes on without the LOD terrain rather than the game stopping.
            this.failed = true;
            LOGGER.error("FarPlane could not draw the LOD terrain with the shader pack; it is left out with packs from now on", e);
        }
    }

    /**
     * One stage of the frame with the state of the pass kept around it: {@link #CLEAR} the depth, {@link #TERRAIN} clear it and draw
     * the ground, or {@link #WATER}: copy the depth without the water for dhDepthTex1 and blend the water over the pack's buffers, after
     * its deferred passes, as Iris does with DH's.
     */
    private void withState(IrisPrograms programs, @Nullable FrameDrawList list, int stage) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int readFramebuffer = GlStateManager._getInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int drawFramebuffer = GlStateManager._getInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int program = GlStateManager._getInteger(GL20C.GL_CURRENT_PROGRAM);
        int vertexArray = GlStateManager._getInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int arrayBuffer = GlStateManager._getInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        int depthFunc = GlStateManager._getInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        int srcRgb = GlStateManager._getInteger(GL14C.GL_BLEND_SRC_RGB);
        int dstRgb = GlStateManager._getInteger(GL14C.GL_BLEND_DST_RGB);
        int srcAlpha = GlStateManager._getInteger(GL14C.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GlStateManager._getInteger(GL14C.GL_BLEND_DST_ALPHA);
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            this.blends[i] = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
        }

        programs.resize(main.width, main.height);
        GlStateManager._disableScissorTest();
        boolean draw = list != null && !list.tiles().isEmpty();
        if (stage == WATER) {
            DepthCopyStrategy.fastest(false).copy(programs.framebuffer, programs.depthTexture(), null, programs.opaqueDepthTexture(),
                programs.width(), programs.height());
            if (draw) {
                programs.waterFramebuffer.bind();
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GL11C.GL_LEQUAL);
                GlStateManager._depthMask(true);
                // Seen from below too, as Iris draws DH's water.
                GlStateManager._disableCull();
                for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
                    GlStateManager._enableBlend(i);
                }
                GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
                this.drawTiles(programs.water, list, true);
            }
        } else {
            programs.framebuffer.bind();
            GlStateManager._depthMask(true);
            GlStateManager._clearBuffer(1.0);
            if (stage == TERRAIN && draw) {
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GL11C.GL_LEQUAL);
                GlStateManager._enableCull();
                for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
                    GlStateManager._disableBlend(i);
                }
                this.drawTiles(programs.solid, list, false);
            }
        }

        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        if (depthTest) {
            GlStateManager._enableDepthTest();
        } else {
            GlStateManager._disableDepthTest();
        }
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        if (cull) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }
        if (scissor) {
            GlStateManager._enableScissorTest();
        }
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            if (this.blends[i]) {
                GlStateManager._enableBlend(i);
            } else {
                GlStateManager._disableBlend(i);
            }
        }
    }

    /** The ground nearest first, or the water farthest first, so it blends over what lies behind it; of every tile its own columns. */
    private void drawTiles(IrisLodRenderProgram program, FrameDrawList list, boolean water) {
        program.bind();
        IrisGlue.projection(CapturedRenderingState.INSTANCE.getGbufferProjection(), this.projection);
        this.modelView.set(CapturedRenderingState.INSTANCE.getGbufferModelView());
        // TEMP CHECK: the model view shift against the draw list camera
        if (!water && PerfLog.ON && ++this.debugFrames % 300 == 1) {
            LOGGER.info("[FarPlane iris] modelView shift {}, {}, {} list cam {}, {}, {}", this.modelView.m30(), this.modelView.m31(),
                    this.modelView.m32(), list.camX(), list.camY(), list.camZ());
        }
        this.modelView.setTranslation(0.0F, 0.0F, 0.0F); // TEMP A/B: zero the model view shift
        this.matrix(program.modelViewUniform, this.modelView);
        this.matrix(program.modelViewInverseUniform, this.modelView.invert(this.inverse));
        this.matrix(program.projectionUniform, this.projection);
        this.matrix(program.projectionInverseUniform, this.projection.invert(this.inverse));
        if (program.normalMatrix3fUniform != -1) {
            this.inverse.set(this.modelView).invert().transpose3x3(this.normal);
            this.normal.get(this.floats);
            GL20C.glUniformMatrix3fv(program.normalMatrix3fUniform, false, this.floats.limit(9));
            this.floats.clear();
        }
        if (program.mircoOffsetUniform != -1) {
            GL20C.glUniform1f(program.mircoOffsetUniform, MICRO_OFFSET);
        }
        if (program.clipDistanceUniform != -1) {
            GL20C.glUniform1f(program.clipDistanceUniform, IrisGlue.nearPlane());
        }
        IrisLodRenderProgramAccessor parts = (IrisLodRenderProgramAccessor) program;
        parts.farplane$samplers().update();
        parts.farplane$uniforms().update();
        parts.farplane$customUniforms().push(program);
        parts.farplane$images().update();

        this.bindVertexArray();
        double camX = list.camX();
        double camY = list.camY();
        double camZ = list.camZ();
        List<TileMesh> tiles = list.tiles();
        PointerBuffer offsets = list.rangeOffsets();
        IntBuffer counts = list.rangeCounts();
        int bound = 0;
        int count = tiles.size();
        for (int n = 0; n < count; n++) {
            int i = water ? count - 1 - n : n;
            TileMesh mesh = tiles.get(i);
            SurfaceTile surface = mesh.surface;
            if (mesh.vertices.isClosed() || !surface.pack() || water && !surface.hasWater() || !(mesh.vertices.buffer instanceof GlBuffer buffer)) {
                continue;
            }
            long base = mesh.vertices.offset + surface.vertexBytes();
            // The rough terrain's own columns only: the pack shows what vanilla left out of its own by the depth of its own, and the
            // ring under vanilla's edge would show where it stands higher than vanilla's ground, as the pack's programs cannot lower it.
            int draws = list.ranges(water ? surface.waterOffsets() : surface.groundOffsets(), list.parts(i), base, OwnershipMap.OWN);
            if (draws == 0) {
                continue;
            }
            float offsetX = (float) (mesh.pos.minX() - camX);
            float offsetY = (float) (surface.baseY() - camY);
            float offsetZ = (float) (mesh.pos.minZ() - camZ);
            GL20C.glUniform3f(program.modelOffsetUniform, offsetX, offsetY, offsetZ);
// TEMP CHECK: the first tile's numbers. / ВРЕМЕННАЯ ПРОВЕРКА: числа первого тайла.
            if (!water && PerfLog.ON && i == 0) {
                LOGGER.info("[FarPlane iris] tile {} baseY {} offset {}, {}, {} camY {}", mesh.pos, surface.baseY(), offsetX, offsetY,
                        offsetZ, camY);
            }
            int handle = buffer.handle();
            if (handle != bound) {
                GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, handle);
                GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, handle);
                bound = handle;
            }
            long at = mesh.vertices.offset;
            // As Iris declares them for DH's programs: position and light, and material and face, as unsigned ints; the color normalized.
            GlStateManager._vertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, PackMesher.SIZE, at + PackMesher.POSITION);
            GlStateManager._vertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, PackMesher.SIZE, at + PackMesher.COLOR);
            GlStateManager._vertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, PackMesher.SIZE, at + PackMesher.EXTRA);
            if (water && PerfLog.ON && this.debugCalls % 300 == 1 && this.debugWater++ < 3) {
                LOGGER.info("[FarPlane debug] pack water tile {} draws {} first count {}", mesh.pos, draws, counts.get(0));
            }
            for (int d = 0; d < draws; d++) {
                GL11C.glDrawElements(GL11C.GL_TRIANGLES, counts.get(d), GL11C.GL_UNSIGNED_SHORT, offsets.get(d));
                if (PerfLog.ON) {
                    PerfLog.draw(counts.get(d));
                }
            }
        }
        program.unbind();
    }

    /** Sets a matrix uniform of the program bound, unless the pack has none of that name (-1). */
    private void matrix(int location, Matrix4f matrix) {
        if (location != -1) {
            matrix.get(this.floats);
            GL20C.glUniformMatrix4fv(location, false, this.floats);
        }
    }

    /** The vertex array of the tiles, made on first use: the three attributes of DH's programs. */
    private void bindVertexArray() {
        if (this.vertexArray == 0) {
            this.vertexArray = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(this.vertexArray);
            GlStateManager._enableVertexAttribArray(0);
            GlStateManager._enableVertexAttribArray(1);
            GlStateManager._enableVertexAttribArray(2);
            return;
        }
        GlStateManager._glBindVertexArray(this.vertexArray);
    }
}
