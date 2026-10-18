package com.Evga314.farplanetwocontinuation.mixin.common;

import com.Evga314.farplanetwocontinuation.server.virtual.VirtualRegion;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla logs an error for every block a feature reads from a chunk farther away than it may write to. Virtual chunks are made a
 * whole region at a time, so with mods whose features reach that far it would write thousands of lines per region; on phones the
 * log alone makes the game stutter. Real world generation still logs them.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Inject(method = "warnIfReadOutsideWriteZone", at = @At("HEAD"), cancellable = true)
    private void farplane$quietVirtualChunks(int chunkX, int chunkZ, CallbackInfo ci) {
        if ((Object) this instanceof VirtualRegion) {
            ci.cancel();
        }
    }
}
