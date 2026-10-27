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
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Draws the new renderer's rough terrain ({@link FrameDrawList}) with a shader pack's DH programs, see IrisGlue: the third output beside
 * {@link OwnPassOutput} and {@link VanillaPassOutput}, for Iris with a pack that has them. The tiles are built for it (PackMesher),
 * and drawn into the pack's buffers right after vanilla's opaque terrain, before the pack's deferred passes, with the depth of their own
 * the pack reads as DH's (see IrisPrograms). The pack puts it together with vanilla's blocks itself, as it does DH's terrain: of the
 * chunk columns, the rough terrain's own ones and the ring under vanilla's edge are drawn alike.
 * <p>
 * Iris's programs are OpenGL's, so this is raw OpenGL, inside vanilla's main pass: every state it touches is put back as it was, through
 * GlStateManager where that keeps it, so the pass goes on unaware. Made by name only when Iris is loaded, see IrisGlue.
 */
final class IrisOutput implements IrisGlue.Bridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_DRAW_BUFFERS = 8;
    /** DH's offset of faces that would fight, which the LOD terrain does not use. */
    private static final float MICRO_OFFSET = 0.01F;
    /** The chunk columns drawn: the rough terrain's own, and the ring under vanilla's edge, which the pack shows where vanilla left a gap. */
    private static final byte[] PARTS = {OwnershipMap.OWN, OwnershipMap.BACKGROUND};

    private int vertexArray;
    private boolean drawn;
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
        try {
            this.draw(programs, list);
        } catch (RuntimeException | LinkageError e) {
            // Something of Iris is not as expected: the pack goes on without the LOD terrain rather than the game stopping.
            this.failed = true;
            LOGGER.error("FarPlane could not draw the LOD terrain with the shader pack; it is left out with packs from now on", e);
        }
    }

    @Override
    public void endSolid() {
        if (!this.drawn && !this.failed) {
            IrisPrograms programs = programs();
            if (programs != null && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
                // The pack reads the depth whether the LOD terrain is drawn or not: no LOD terrain is cleared depth.
                this.withState(programs, null);
            }
        }
        this.drawn = false;
    }

    private void draw(IrisPrograms programs, FrameDrawList list) {
        this.drawn = true;
        this.withState(programs, list);
    }

    /** Clears the LOD terrain's depth and, with a list, draws it, with the state of the pass kept around it. */
    private void withState(IrisPrograms programs, @Nullable FrameDrawList list) {
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
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            this.blends[i] = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
        }

        programs.resize(main.width, main.height);
        programs.framebuffer.bind();
        GlStateManager._disableScissorTest();
        GlStateManager._depthMask(true);
        GlStateManager._clearBuffer(1.0);
        if (list != null && !list.tiles().isEmpty()) {
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11C.GL_LEQUAL);
            GlStateManager._enableCull();
            for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
                GlStateManager._disableBlend(i);
            }
            this.drawTiles(programs.solid, list);
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
        for (int i = 0; i < MAX_DRAW_BUFFERS; i++) {
            if (this.blends[i]) {
                GlStateManager._enableBlend(i);
            } else {
                GlStateManager._disableBlend(i);
            }
        }
    }

    private void drawTiles(IrisLodRenderProgram program, FrameDrawList list) {
        program.bind();
        IrisGlue.projection(CapturedRenderingState.INSTANCE.getGbufferProjection(), this.projection);
        this.modelView.set(CapturedRenderingState.INSTANCE.getGbufferModelView());
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
        for (int i = 0; i < tiles.size(); i++) {
            TileMesh mesh = tiles.get(i);
            SurfaceTile surface = mesh.surface;
            if (mesh.vertices.isClosed() || !surface.pack() || !(mesh.vertices.buffer instanceof GlBuffer buffer)) {
                continue;
            }
            byte[] tileParts = list.parts(i);
            long base = mesh.vertices.offset + surface.vertexBytes();
            boolean placed = false;
            for (int p = 0; p < PARTS.length; p++) {
                int draws = list.ranges(surface.groundOffsets(), tileParts, base, PARTS[p]);
                if (draws == 0) {
                    continue;
                }
                if (!placed) {
                    placed = true;
                    GL20C.glUniform3f(program.modelOffsetUniform, (float) (mesh.pos.minX() - camX), (float) (surface.baseY() - camY), (float) (mesh.pos.minZ() - camZ));
                    int handle = buffer.handle();
                    if (handle != bound) {
                        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, handle);
                        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, handle);
                        bound = handle;
                    }
                    long at = mesh.vertices.offset;
                    GlStateManager._vertexAttribIPointer(0, 4, GL11C.GL_UNSIGNED_SHORT, PackMesher.SIZE, at + PackMesher.POSITION);
                    GlStateManager._vertexAttribPointer(1, 4, GL11C.GL_UNSIGNED_BYTE, true, PackMesher.SIZE, at + PackMesher.COLOR);
                    GlStateManager._vertexAttribIPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, PackMesher.SIZE, at + PackMesher.EXTRA);
                }
                for (int d = 0; d < draws; d++) {
                    GL11C.glDrawElements(GL11C.GL_TRIANGLES, counts.get(d), GL11C.GL_UNSIGNED_SHORT, offsets.get(d));
                    if (PerfLog.ON) {
                        PerfLog.draw(counts.get(d));
                    }
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
