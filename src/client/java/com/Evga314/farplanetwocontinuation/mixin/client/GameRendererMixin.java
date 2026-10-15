package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        )
    )
    private Matrix4f farplane$captureProjection(Matrix4f projection) {
        LodRenderer renderer = FarplanetwocontinuationClient.renderer();
        if (renderer != null) {
            renderer.captureLevelProjection(projection);
        }
        return projection;
    }
}
