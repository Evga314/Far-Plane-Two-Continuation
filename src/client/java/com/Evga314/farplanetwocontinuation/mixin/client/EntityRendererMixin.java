package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entities high above the world turn black in vanilla: a block position keeps its height in 12 bits, so past 2047 it wraps around to
 * deep under the ground, where there is no light. Above the top of the world nothing shades them, so their light is taken right over
 * it instead, where the sky's is full. See ClientConfig#fixHighEntityLight.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
    @ModifyExpressionValue(
        method = "getPackedLightCoords",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;containing(Lnet/minecraft/core/Position;)Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos farplane$lightAboveWorld(BlockPos pos, @Local(argsOnly = true) Entity entity) {
        int above = entity.level().getMaxY() + 1;
        if (pos.getY() <= above) {
            return pos;
        }
        LodManager manager = FarplanetwocontinuationClient.manager();
        return manager != null && manager.config().fixHighEntityLight ? pos.atY(above) : pos;
    }
}
