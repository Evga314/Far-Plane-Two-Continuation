package com.Evga314.farplanetwocontinuation.client.lod;

import java.util.Optional;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.targets.DepthTexture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What one Iris pipeline (one shader pack in one dimension) has for the LOD terrain: the pack's dh_terrain program, the framebuffer it
 * draws into (the pack's buffers, with a depth texture of the LOD terrain's own, which the pack reads as dhDepthTex) and that texture.
 * Made with the pipeline and freed with it, see DHCompatMixin. Only loaded with Iris.
 */
public final class IrisPrograms {
    /** Iris's DH glue (DHCompat) with this attached, see DHCompatMixin. */
    public interface Holder {
        @Nullable IrisPrograms farplane$programs();
    }

    final IrisLodRenderProgram solid;
    /** Owned by the pipeline's render targets, which free it. */
    final GlFramebuffer framebuffer;
    private @Nullable DepthTexture depth;
    private int width;
    private int height;

    private IrisPrograms(IrisLodRenderProgram solid, GlFramebuffer framebuffer) {
        this.solid = solid;
        this.framebuffer = framebuffer;
    }

    /** The programs of a pipeline, or null when its pack has no dh_terrain program. */
    public static @Nullable IrisPrograms create(IrisRenderingPipeline pipeline) {
        Optional<ProgramSource> terrain = pipeline.getDHTerrainShader();
        if (terrain.isEmpty()) {
            return null;
        }
        ProgramSource source = terrain.get();
        IrisLodRenderProgram solid = IrisLodRenderProgram.createProgram(source.getName(), false, false, source, pipeline.getCustomUniforms(), pipeline);
        IrisPrograms programs = new IrisPrograms(solid, pipeline.createDHFramebuffer(source, false));
        // The pack samples the textures from its first frame on, drawn or not.
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        programs.resize(main.width, main.height);
        return programs;
    }

    /** Makes the depth texture again when the window changed size, and attaches it to the framebuffer. */
    void resize(int width, int height) {
        if (this.depth != null && this.width == width && this.height == height) {
            return;
        }
        this.destroyTexture();
        this.depth = new DepthTexture("FarPlane LOD depth", width, height, DepthBufferFormat.DEPTH32F);
        this.width = width;
        this.height = height;
        this.framebuffer.addDepthAttachmentBypass(this.depth.getTextureId());
    }

    /** For the pack's dhDepthTex samplers. */
    public int depthTexture() {
        return this.depth == null ? 0 : this.depth.getTextureId();
    }

    /** For dhDepthTex1, the depth without the water: the same while the LOD terrain draws no water for the pack. */
    public int opaqueDepthTexture() {
        return this.depthTexture();
    }

    private void destroyTexture() {
        if (this.depth != null) {
            this.depth.destroy();
            this.depth = null;
        }
    }

    public void free() {
        this.solid.free();
        this.destroyTexture();
    }
}
