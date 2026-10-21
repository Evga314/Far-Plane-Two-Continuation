package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Settings that are not about the terrain itself: fixes for vanilla and how the mod gets along with other mods. */
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
        OptionInstance<Boolean> forceLodWithShaders = OptionInstance.createBoolean(
            PREFIX + "force_lod_with_shaders", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "force_lod_with_shaders.tooltip")), config.forceLodWithShaders, value -> config.forceLodWithShaders = value
        );
        OptionInstance<Boolean> cloudFarPlane = OptionInstance.createBoolean(
            PREFIX + "cloud_far_plane", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "cloud_far_plane.tooltip")), config.extendCloudFarPlane, value -> config.extendCloudFarPlane = value
        );
        this.list.addBig(forceLodWithShaders);
        this.list.addBig(cloudFarPlane);
    }

    @Override
    public void removed() {
        super.removed();
        FarplanetwocontinuationClient.manager().config().save();
    }
}
