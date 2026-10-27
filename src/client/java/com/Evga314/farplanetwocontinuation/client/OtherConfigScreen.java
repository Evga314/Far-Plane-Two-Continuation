package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Settings that are not about the terrain itself: fixes for vanilla, and the mod's cache. Shader packs have their own, see ShaderConfigScreen. */
public class OtherConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    public OtherConfigScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "other.title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig config = FarplanetwocontinuationClient.manager().config();
        OptionInstance<Boolean> cloudFarPlane = OptionInstance.createBoolean(
            PREFIX + "cloud_far_plane", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "cloud_far_plane.tooltip")), config.extendCloudFarPlane, value -> config.extendCloudFarPlane = value
        );
        this.list.addBig(cloudFarPlane);
        OptionInstance<Boolean> newRenderer = OptionInstance.createBoolean(
            PREFIX + "new_renderer", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "new_renderer.tooltip")), config.newRenderer, value -> config.newRenderer = value
        );
        this.list.addBig(newRenderer);
        this.list.addBig(OptionInstance.createBoolean(
            PREFIX + "high_entity_light", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "high_entity_light.tooltip")), config.fixHighEntityLight, value -> config.fixHighEntityLight = value
        ));
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "clear_cache"), button -> this.confirmClearCache()).build());
    }

    private void confirmClearCache() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                FarplanetwocontinuationClient.manager().reloadAll();
            }
            this.minecraft.gui.setScreen(this);
        }, Component.translatable(PREFIX + "clear_cache.title"), Component.translatable(PREFIX + "clear_cache.message")));
    }

    @Override
    public void removed() {
        super.removed();
        FarplanetwocontinuationClient.manager().config().save();
    }
}
