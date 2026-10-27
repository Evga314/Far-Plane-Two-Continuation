package com.Evga314.farplanetwocontinuation.mixin.iris;

import com.Evga314.farplanetwocontinuation.client.lod.IrisGlue;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Packs switch their Distant Horizons code on by the DISTANT_HORIZONS define: reading the depth of the distant terrain, its fog, the
 * seam with vanilla's blocks. Iris defines it only with DH installed; while the mod takes DH's place it defines it too (see IrisGlue).
 * The depth the pack then reads is cleared every frame, drawn or not, see IrisOutput.endSolid. DISTANT_HORIZONS_TEXTURES stays out:
 * the LOD terrain has no block atlas.
 */
@Mixin(value = StandardMacros.class, remap = false)
abstract class StandardMacrosMixin {
    @ModifyReturnValue(method = "createStandardEnvironmentDefines", at = @At("RETURN"))
    private static ImmutableList<StringPair> farplane$distantHorizons(ImmutableList<StringPair> defines) {
        boolean wanted = IrisGlue.wanted();
        IrisGlue.defined(wanted);
        if (!wanted) {
            return defines;
        }
        for (StringPair define : defines) {
            if (define.key().equals("DISTANT_HORIZONS")) {
                return defines;
            }
        }
        return ImmutableList.<StringPair>builder().addAll(defines).add(new StringPair("DISTANT_HORIZONS", "")).build();
    }
}
