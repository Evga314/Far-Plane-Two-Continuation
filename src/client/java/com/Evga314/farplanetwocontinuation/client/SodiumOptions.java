package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The mod in Sodium's video settings: one button that opens the mod's own settings screen. Sodium finds this class through the
 * {@code sodium:config_api_user} entrypoint, so it is only loaded when Sodium is installed.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    private static final String OPTIONS = FarPlaneConstants.MOD_ID + ".options.";

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setNonTintedIcon(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "icon.png"))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable(OPTIONS + "general"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "open_settings"))
                        .setName(Component.translatable(OPTIONS + "open_settings"))
                        .setTooltip(Component.translatable(OPTIONS + "open_settings.tooltip"))
                        .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new LodConfigScreen(parent))))));
    }
}
