package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's far plane is a horizontal distance, so from high up the edge of the cloud layer falls past it and the clouds are cut
 * off. With the setting on, the far plane grows to the slant distance to that edge right after vanilla sets it. Off by default.
 * LodView.cloudReach holds the same formula for the mod's own copy of vanilla's reach.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    private float depthFar;

    @Inject(
        method = "update",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F", ordinal = 0, shift = At.Shift.AFTER)
    )
    private void farplane$extendCloudFarPlane(CallbackInfo ci) {
        LodManager manager = FarplanetwocontinuationClient.manager();
        if (manager == null || !manager.config().extendCloudFarPlane) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        double cloudRange = minecraft.options.cloudRange().get() * 16.0;
        double camY = minecraft.gameRenderer.mainCamera().position().y;
        double dy = Math.abs(camY) + 192.0;
        double need = Math.sqrt(cloudRange * cloudRange + dy * dy) * 1.02 + 32.0;
        if (need > this.depthFar) {
            this.depthFar = (float) need;
        }
    }
}
