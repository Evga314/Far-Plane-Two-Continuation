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
 * What one Iris pipeline (one shader pack in one dimension) has for the LOD terrain: the pack's dh_terrain and dh_water programs (the
 * water drawn with the ground's program where a pack has none, as Iris does for DH), the framebuffers they draw into (the pack's
 * buffers, with a depth texture of the LOD terrain's own, which the pack reads as dhDepthTex), that texture, and its copy before the
 * water is drawn (dhDepthTex1). Made with the pipeline and freed with it, see DHCompatMixin. Only loaded with Iris.
 */
public final class IrisPrograms {
    /** Iris's DH glue (DHCompat) with this attached, see DHCompatMixin. */
    public interface Holder {
        @Nullable IrisPrograms farplane$programs();
    }

    final IrisLodRenderProgram solid;
    final IrisLodRenderProgram water;
    /** Owned by the pipeline's render targets, which free them. */
    final GlFramebuffer framebuffer;
    final GlFramebuffer waterFramebuffer;
    private @Nullable DepthTexture depth;
    private @Nullable DepthTexture opaqueDepth;
    private int width;
    private int height;

    private IrisPrograms(IrisLodRenderProgram solid, IrisLodRenderProgram water, GlFramebuffer framebuffer, GlFramebuffer waterFramebuffer) {
        this.solid = solid;
        this.water = water;
        this.framebuffer = framebuffer;
        this.waterFramebuffer = waterFramebuffer;
    }

    /** The programs of a pipeline, or null when its pack has no dh_terrain program. */
    public static @Nullable IrisPrograms create(IrisRenderingPipeline pipeline) {
        Optional<ProgramSource> terrain = pipeline.getDHTerrainShader();
        if (terrain.isEmpty()) {
            return null;
        }
        ProgramSource source = terrain.get();
        IrisLodRenderProgram solid = IrisLodRenderProgram.createProgram(source.getName(), false, false, source, pipeline.getCustomUniforms(), pipeline);
        Optional<ProgramSource> waterSource = pipeline.getDHWaterShader();
        IrisLodRenderProgram water = solid;
        GlFramebuffer waterFramebuffer;
        if (waterSource.isPresent()) {
            ProgramSource waters = waterSource.get();
            water = IrisLodRenderProgram.createProgram(waters.getName(), false, true, waters, pipeline.getCustomUniforms(), pipeline);
            waterFramebuffer = pipeline.createDHFramebuffer(waters, true);
        } else {
            waterFramebuffer = pipeline.createDHFramebuffer(source, true);
        }
        IrisPrograms programs = new IrisPrograms(solid, water, pipeline.createDHFramebuffer(source, false), waterFramebuffer);
        // The pack samples the textures from its first frame on, drawn or not.
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        programs.resize(main.width, main.height);
        return programs;
    }

    /** Makes the depth textures again when the window changed size, and attaches the depth to the framebuffers. */
    void resize(int width, int height) {
        if (this.depth != null && this.width == width && this.height == height) {
            return;
        }
        this.destroyTextures();
        this.depth = new DepthTexture("FarPlane LOD depth", width, height, DepthBufferFormat.DEPTH32F);
        this.opaqueDepth = new DepthTexture("FarPlane LOD depth before water", width, height, DepthBufferFormat.DEPTH32F);
        this.width = width;
        this.height = height;
        this.framebuffer.addDepthAttachmentBypass(this.depth.getTextureId());
        this.waterFramebuffer.addDepthAttachmentBypass(this.depth.getTextureId());
    }

    int width() {
        return this.width;
    }

    int height() {
        return this.height;
    }

    /** For the pack's dhDepthTex samplers. */
    public int depthTexture() {
        return this.depth == null ? 0 : this.depth.getTextureId();
    }

    /** For dhDepthTex1: the depth without the water, copied before it is drawn, see IrisOutput.drawWater. */
    public int opaqueDepthTexture() {
        return this.opaqueDepth == null ? 0 : this.opaqueDepth.getTextureId();
    }

    private void destroyTextures() {
        if (this.depth != null) {
            this.depth.destroy();
            this.depth = null;
        }
        if (this.opaqueDepth != null) {
            this.opaqueDepth.destroy();
            this.opaqueDepth = null;
        }
    }

    public void free() {
        if (this.water != this.solid) {
            this.water.free();
        }
        this.solid.free();
        this.destroyTextures();
    }
}
