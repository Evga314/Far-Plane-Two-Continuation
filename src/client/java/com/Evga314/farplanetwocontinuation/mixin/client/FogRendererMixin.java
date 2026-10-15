package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla fades terrain into fog at the edge of the render distance, which would hide the LOD terrain behind it. While LOD terrain
 * is visible the distance fog is moved out to the LOD distance. Fog from water, lava, blindness, bad weather and similar effects is
 * left alone because it is closer than the vanilla render distance.
 * <p>
 * With the "No Fog" option every kind of fog is removed, except blindness and darkness, which are gameplay effects.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    /** Far enough that the linear fog never starts, while staying a normal float for every GPU. */
    private static final float NO_FOG = 1.0E9F;

    private static boolean hasVisionEffect(Camera camera) {
        return camera.entity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void farplane$extendFog(
        Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir
    ) {
        LodManager manager = FarplanetwocontinuationClient.manager();
        if (manager == null) {
            return;
        }
        FogData fog = cir.getReturnValue();
        if (manager.config().disableFog && !hasVisionEffect(camera)) {
            fog.environmentalStart = NO_FOG;
            fog.environmentalEnd = NO_FOG;
            fog.renderDistanceStart = NO_FOG;
            fog.renderDistanceEnd = NO_FOG;
            fog.skyEnd = NO_FOG;
            fog.cloudEnd = NO_FOG;
            return;
        }
        if (!manager.hasVisibleTerrain() || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        float end = manager.activeLodDistance();
        float start = end * manager.config().fogStart;
        float vanillaRange = renderDistanceInChunks * 16.0F;
        fog.renderDistanceStart = Math.max(fog.renderDistanceStart, start);
        fog.renderDistanceEnd = Math.max(fog.renderDistanceEnd, end);
        if (fog.environmentalEnd >= vanillaRange) {
            fog.environmentalStart = Math.max(fog.environmentalStart, start);
            fog.environmentalEnd = Math.max(fog.environmentalEnd, end);
        }
    }
}
