package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.lod.PerfLog;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * For the log of the debug build: the fog as it finally is, after every mod changed it (this runs after the others, see the priority),
 * next to the fog right after FogRendererMixin. Shows which mod changes what, and in which order.
 */
@Mixin(value = FogRenderer.class, priority = 2000)
abstract class FogLogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void farplane$logFinalFog(
        Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir
    ) {
        if (PerfLog.ON) {
            PerfLog.fog("final", cir.getReturnValue(), camera.getFluidInCamera().name());
        }
    }
}
