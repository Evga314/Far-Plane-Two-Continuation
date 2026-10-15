package com.Evga314.farplanetwocontinuation.mixin.common;

import com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Surface rules run down every column to the bottom of the chunk. For chunks made only to be looked at from far away, the blocks
 * deeper than a few layers under the surface are never seen, so there the rules stop early. Real world generation is not affected.
 */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemMixin {
    @ModifyExpressionValue(
        method = "buildSurface",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getMinY()I")
    )
    private int farplane$shallowSurface(int minY, @Local(name = "height") int height, @Local(name = "surfaceBiome") Holder<Biome> surfaceBiome) {
        if (!VirtualTerrain.isBuildingSurface()) {
            return minY;
        }
        // Badlands show their terracotta bands down whole cliffs, everywhere else the top layers are all that differ from stone.
        int depth = surfaceBiome.is(BiomeTags.IS_BADLANDS) ? VirtualTerrain.BADLANDS_SURFACE_RULE_DEPTH : VirtualTerrain.SURFACE_RULE_DEPTH;
        return Math.max(minY, height - depth);
    }
}
