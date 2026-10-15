package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes "clear LOD cache and reload" an F3 combination like vanilla's F3 + A. Vanilla only calls this method while F3 is held and
 * treats a {@code true} result as a used combination, so releasing F3 afterwards does not open the debug screen.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
    private void farplane$reloadCombination(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        KeyMapping reloadKey = FarplanetwocontinuationClient.reloadKey();
        if (reloadKey != null && !reloadKey.isUnbound() && reloadKey.matches(event)) {
            FarplanetwocontinuationClient.manager().reloadAll();
            cir.setReturnValue(true);
        }
    }
}
