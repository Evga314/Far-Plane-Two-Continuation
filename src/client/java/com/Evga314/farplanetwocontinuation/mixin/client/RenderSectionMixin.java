package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freshly loaded chunk sections fade in from the fog color. With fog behind them that looks smooth, but in front of LOD terrain a
 * loading section shows up as a fog-colored patch at the edge of the render distance. While LOD terrain is on screen sections
 * appear at once instead; without LOD terrain vanilla behaviour is untouched.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionMixin {
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void farplane$skipFadeInFrontOfLod(long now, long fadeDuration, CallbackInfoReturnable<Float> cir) {
        LodManager manager = FarplanetwocontinuationClient.manager();
        if (manager != null && manager.hasVisibleTerrain()) {
            cir.setReturnValue(1.0F);
        }
    }
}
