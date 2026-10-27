package com.Evga314.farplanetwocontinuation.mixin.iris;

import com.Evga314.farplanetwocontinuation.client.lod.IrisGlue;
import com.Evga314.farplanetwocontinuation.client.lod.IrisPrograms;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris's glue to Distant Horizons, which hands DH's planes, projection and depth textures to the pack's uniforms and samplers. Without
 * DH (the mixins are applied only then, see IrisMixinPlugin) it hands out the LOD terrain's, and holds the pack's DH programs the LOD
 * terrain is drawn with, see IrisPrograms. Only while the mod wants it (IrisGlue.wanted); else Iris's own answers stand.
 */
@Mixin(value = DHCompat.class, remap = false)
abstract class DHCompatMixin implements IrisPrograms.Holder {
    @Unique
    private static final Logger FARPLANE_LOGGER = LogUtils.getLogger();

    @Unique
    private @Nullable IrisPrograms farplane$programs;

    @Override
    public @Nullable IrisPrograms farplane$programs() {
        return this.farplane$programs;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void farplane$makePrograms(IrisRenderingPipeline pipeline, boolean renderDHShadow, CallbackInfo ci) {
        if (!IrisGlue.programsWanted()) {
            return;
        }
        try {
            this.farplane$programs = IrisPrograms.create(pipeline);
        } catch (RuntimeException e) {
            // A pack whose DH programs do not build for the LOD terrain keeps working; the LOD terrain is then left out.
            FARPLANE_LOGGER.warn("FarPlane could not build the shader pack's DH programs; the LOD terrain is not drawn with this pack", e);
        }
    }

    @Inject(method = "clearPipeline", at = @At("TAIL"))
    private void farplane$freePrograms(CallbackInfo ci) {
        if (this.farplane$programs != null) {
            this.farplane$programs.free();
            this.farplane$programs = null;
        }
    }

    @Inject(method = "getDepthTex", at = @At("HEAD"), cancellable = true)
    private void farplane$depthTex(CallbackInfoReturnable<Integer> cir) {
        if (this.farplane$programs != null) {
            cir.setReturnValue(this.farplane$programs.depthTexture());
        }
    }

    @Inject(method = "getDepthTexNoTranslucent", at = @At("HEAD"), cancellable = true)
    private void farplane$depthTexNoTranslucent(CallbackInfoReturnable<Integer> cir) {
        if (this.farplane$programs != null) {
            cir.setReturnValue(this.farplane$programs.opaqueDepthTexture());
        }
    }

    @Inject(method = "getFarPlane", at = @At("HEAD"), cancellable = true)
    private static void farplane$farPlane(CallbackInfoReturnable<Float> cir) {
        if (IrisGlue.wanted()) {
            cir.setReturnValue(IrisGlue.farPlane());
        }
    }

    @Inject(method = "getNearPlane", at = @At("HEAD"), cancellable = true)
    private static void farplane$nearPlane(CallbackInfoReturnable<Float> cir) {
        if (IrisGlue.wanted()) {
            cir.setReturnValue(IrisGlue.nearPlane());
        }
    }

    @Inject(method = "getRenderDistance", at = @At("HEAD"), cancellable = true)
    private static void farplane$renderDistance(CallbackInfoReturnable<Integer> cir) {
        if (IrisGlue.wanted()) {
            cir.setReturnValue(IrisGlue.renderDistance());
        }
    }

    @Inject(method = "getProjection()Lorg/joml/Matrix4f;", at = @At("HEAD"), cancellable = true)
    private static void farplane$projection(CallbackInfoReturnable<Matrix4f> cir) {
        if (IrisGlue.wanted()) {
            cir.setReturnValue(IrisGlue.projection(CapturedRenderingState.INSTANCE.getGbufferProjection(), new Matrix4f()));
        }
    }
}
