package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The sky disc does not reach the horizon: the last couple of degrees above it show the color the frame was cleared with, which is
 * the fog color. Without fog that strip would stand out as a pale band over the terrain, so the frame is cleared with
 * the sky color instead.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererClearColorMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private Vector4f farplane$skyColoredClear(Vector4f fogColor) {
        LodManager manager = FarplanetwocontinuationClient.manager();
        SkyRenderState sky = this.levelRenderState.skyRenderState;
        if (manager == null || manager.config().distant().fogMode != ClientConfig.FOG_OFF || sky.skybox != DimensionType.Skybox.OVERWORLD
            || this.levelRenderState.cameraRenderState.fogType != FogType.NONE) {
            return fogColor;
        }
        Vector3fc skyColor = sky.skyColor;
        return skyColor == null ? fogColor : new Vector4f(skyColor.x(), skyColor.y(), skyColor.z(), fogColor.w);
    }
}
