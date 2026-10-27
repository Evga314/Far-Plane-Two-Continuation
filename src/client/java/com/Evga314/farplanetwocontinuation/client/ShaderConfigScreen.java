package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Everything about shader packs (Iris): whether they show the LOD terrain, and how the terrain is built for them. */
public class ShaderConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    public ShaderConfigScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "shaders.title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig config = FarplanetwocontinuationClient.manager().config();
        this.list.addBig(OptionInstance.createBoolean(
            PREFIX + "force_lod_with_shaders", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "force_lod_with_shaders.tooltip")), config.forceLodWithShaders, value -> config.forceLodWithShaders = value
        ));
        this.list.addBig(OptionInstance.createBoolean(
            PREFIX + "shader_soft_borders", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "shader_soft_borders.tooltip")), config.shaderSoftBorders, value -> config.shaderSoftBorders = value
        ));
    }

    @Override
    public void removed() {
        super.removed();
        FarplanetwocontinuationClient.manager().config().save();
    }
}
