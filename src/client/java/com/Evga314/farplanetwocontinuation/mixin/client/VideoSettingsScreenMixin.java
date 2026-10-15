package com.Evga314.farplanetwocontinuation.mixin.client;

import com.Evga314.farplanetwocontinuation.client.LodConfigScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a button to Video Settings. On touch screens there is usually no convenient key to open the settings, so the button is the main
 * way to reach them.
 */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void farplane$addButton(CallbackInfo ci) {
        if (this.list != null) {
            this.list.addBig(Button.builder(Component.translatable("farplanetwocontinuation.options.open"), button -> {
                if (this.minecraft != null) {
                    this.minecraft.gui.setScreen(new LodConfigScreen(this));
                }
            }).build());
        }
    }
}
