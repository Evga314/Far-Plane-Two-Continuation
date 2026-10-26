package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.lod.LodFog;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's fog of the atmosphere. Its own, before other mods change it at the same point (a lower priority than the default runs
 * first): this mod's fog puts it back over theirs, and tells their fog from vanilla's by it. See FogRendererMixin.
 * <p>
 * With this mod's fog the fog color is the color of the sky, as Smooth Skies (from Clear Skies) does: vanilla mixes it towards a
 * paler color the smaller the render distance is, which shows as a pale band over the horizon, and the LOD terrain fading into it
 * stands out against the sky above. The sunrise and sunset tint vanilla adds after this stays.
 */
@Mixin(value = AtmosphericFogEnvironment.class, priority = 500)
abstract class AtmosphericFogEnvironmentMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void farplane$captureAtmosphere(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        LodFog.captureAtmosphere(fog.environmentalStart, fog.environmentalEnd, fog.skyEnd);
    }

    @Definition(id = "srgbLerp", method = "Lnet/minecraft/util/ARGB;srgbLerp(FLorg/joml/Vector3fc;Lorg/joml/Vector3fc;)Lorg/joml/Vector3fc;")
    @Definition(id = "skyColorMixFactor", local = @Local(type = float.class, name = "skyColorMixFactor"))
    @Definition(id = "fogColor", local = @Local(type = Vector3fc.class, name = "fogColor"))
    @Expression("srgbLerp(skyColorMixFactor, @(fogColor), ?)")
    @ModifyExpressionValue(method = "getBaseColor", at = @At("MIXINEXTRAS:EXPRESSION"), require = 0)
    private Vector3fc farplane$skyColoredFog(Vector3fc fogColor, @Local(name = "level", argsOnly = true) ClientLevel level, @Local(name = "skyColor") Vector3fc skyColor) {
        return LodFog.ownFog && level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD ? skyColor : fogColor;
    }
}
