package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodFog;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's fog, by the fog mode (see ClientConfig.DistantSettings#fogMode). With this mod's fog vanilla's terrain gets the fog of the LOD
 * terrain at the end of the view (vanilla fades it into fog at the edge of its render distance, which while LOD terrain is visible beyond
 * is not the end of the view), and the fog of the atmosphere goes back to vanilla's own, over what other mods have set (Sodium Extra puts
 * its fog distance there). With another mod's fog vanilla's fog is left as it is, and goes on over the LOD terrain. Without fog all of it
 * is removed out of water. Under water only the water fog setting applies; lava and powder snow keep vanilla's fog, and blindness and
 * darkness are gameplay effects and always stay.
 * <p>
 * In the automatic mode the fog is another mod's when it differs from vanilla's: at the end of the view other than at the edge of the
 * render distance, or turned off, or the fog of the atmosphere changed. A mod that only changes how soon vanilla's fog starts at the edge
 * still gets this mod's fog.
 * <p>
 * Sodium copies the fog for its own terrain at the same point, so this runs first (a lower priority than the default): otherwise
 * Sodium's chunks keep vanilla's fog and fade into the sky color at the edge of the render distance.
 */
@Mixin(value = FogRenderer.class, priority = 500)
abstract class FogRendererMixin {
    /** Far enough that the linear fog never starts, while staying a normal float for every GPU. */
    private static final float NO_FOG = 1.0E9F;
    /**
     * The end of the sky's fog that makes the pale band over the horizon about 2.5 degrees high: the sky disc lies 16 blocks above the
     * camera, and is all fog color where it is farther than this. It has to be well inside the disc's edge (512 blocks out), or the
     * band would end in a step there against the fog color the frame is cleared with.
     */
    private static final float SKY_BAND_END = 16.0F / (float) Math.sin(Math.toRadians(2.5));

    private static boolean hasVisionEffect(Camera camera) {
        return camera.entity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }

    @Inject(method = "setupFog", at = @At("HEAD"))
    private void farplane$startFog(
        Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir
    ) {
        LodFog.startFrame();
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
        if (com.Evga314.farplanetwocontinuation.client.lod.PerfLog.ON) {
            com.Evga314.farplanetwocontinuation.client.lod.PerfLog.fog("vanilla", fog, camera.getFluidInCamera().name());
        }
        ClientConfig.DistantSettings settings = manager.config().distant();
        FogType fluid = camera.getFluidInCamera();
        float vanillaRange = renderDistanceInChunks * 16.0F;
        int mode = settings.fogMode;
        LodFog.followVanilla = mode == ClientConfig.FOG_OTHER || mode == ClientConfig.FOG_AUTO && fluid == FogType.NONE
            && (Math.abs(fog.renderDistanceEnd - vanillaRange) >= 1.0F || LodFog.atmosphereChanged(fog));
        LodFog.ownFog = fluid == FogType.NONE && !LodFog.followVanilla && mode != ClientConfig.FOG_OFF;
        if (hasVisionEffect(camera)) {
            return;
        }
        if (fluid == FogType.WATER) {
            // The sky and the clouds keep vanilla's fog: the sky turns into the color of the water and the clouds are not seen through
            // its surface, as in vanilla.
            if (!settings.waterFog) {
                fog.environmentalStart = NO_FOG;
                fog.environmentalEnd = NO_FOG;
                fog.renderDistanceStart = NO_FOG;
                fog.renderDistanceEnd = NO_FOG;
            } else if (Math.abs(fog.renderDistanceEnd - vanillaRange) < 1.0F && manager.hasVisibleTerrain()) {
                // Vanilla's fog at the edge of its chunks is not the end of the view under water either: the water fog goes on over
                // the LOD terrain instead of the terrain past vanilla's chunks turning into flat fog color.
                fog.renderDistanceStart = NO_FOG;
                fog.renderDistanceEnd = NO_FOG;
            }
            return;
        }
        if (fluid != FogType.NONE || LodFog.followVanilla) {
            return;
        }
        if (mode == ClientConfig.FOG_OFF) {
            fog.environmentalStart = NO_FOG;
            fog.environmentalEnd = NO_FOG;
            fog.renderDistanceStart = NO_FOG;
            fog.renderDistanceEnd = NO_FOG;
            fog.skyEnd = NO_FOG;
            fog.cloudEnd = NO_FOG;
            return;
        }
        LodFog.restoreAtmosphere(fog);
        if (!manager.hasVisibleTerrain()) {
            // Vanilla's own fog at the edge of its chunks, over what other mods have set.
            fog.renderDistanceStart = vanillaRange - Mth.clamp(vanillaRange / 10.0F, 4.0F, 64.0F);
            fog.renderDistanceEnd = vanillaRange;
            return;
        }
        // Vanilla's terrain gets the fog of the LOD terrain, see LodFog.
        float end = settings.fogEnd > 0 ? settings.fogEnd : manager.activeLodDistance();
        float start = settings.fogStart(end);
        fog.renderDistanceStart = start;
        fog.renderDistanceEnd = end;
        // The far fog of the atmosphere and the End's own fog (which would turn the islands into flat fog color long before the LOD
        // distance) give way to this mod's: they are always measured round, and would hide its shape. Rain fog, closer, stays.
        if (fog.environmentalEnd >= vanillaRange || DimensionKind.of(level) == DimensionKind.END) {
            fog.environmentalStart = NO_FOG;
            fog.environmentalEnd = NO_FOG;
        }
        // The band of the sky over the horizon in the fog color (the sky's own, see AtmosphericFogEnvironmentMixin, with the sunset tint)
        // keeps the same angle whatever the render distance: vanilla's goes by it, and at a small one is wide.
        fog.skyEnd = SKY_BAND_END;
    }

    /** Vanilla's fog as it is written for the frame, after every mod that changes it (Sodium Extra only decodes its own here). */
    @Inject(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"))
    private void farplane$captureFog(
        ByteBuffer buffer, int offset, Vector4f color, float environmentalStart, float environmentalEnd, float renderDistanceStart,
        float renderDistanceEnd, float skyEnd, float cloudEnd, CallbackInfo ci
    ) {
        LodFog.captureVanilla(color, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, skyEnd);
    }
}
