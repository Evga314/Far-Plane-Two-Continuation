package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"
        )
    )
    private void farplane$addLodPass(
        GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline,
        CameraRenderState cameraState,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        boolean consistentDepthRequired,
        CallbackInfo ci,
        @Local FrameGraphBuilder frame
    ) {
        LodRenderer renderer = FarplanetwocontinuationClient.renderer();
        if (renderer == null) {
            return;
        }
        FarplanetwocontinuationClient.manager().uploadBuiltMeshes();
        if (!renderer.shouldRender(cameraState)) {
            return;
        }
        FramePass pass = frame.addPass("farplane_lod");
        ResourceHandle<RenderTarget> main = pass.readsAndWrites(this.targets.main);
        this.targets.main = main;
        pass.executes(() -> renderer.render(main.get(), cameraState, terrainFog));
    }
}
