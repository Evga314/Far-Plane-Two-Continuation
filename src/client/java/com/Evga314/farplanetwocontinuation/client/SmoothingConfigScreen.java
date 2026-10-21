package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** How much each kind of border on the distant terrain is smoothed, or how much it is blurred. */
public class SmoothingConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    private final String initialSignature = FarplanetwocontinuationClient.manager().config().tileSignature();
    private final ClientConfig.Smoothing smoothing;
    private final boolean blur;

    public SmoothingConfigScreen(Screen lastScreen, ClientConfig.Smoothing smoothing, boolean blur) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + (blur ? "blur" : "smoothing") + ".title"));
        this.smoothing = smoothing;
        this.blur = blur;
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig.Smoothing s = this.smoothing;
        if (this.blur) {
            this.list.addSmall(this.blurOption("coasts", s.coastsBlur, value -> s.coastsBlur = value), this.blurOption("snow", s.snowBlur, value -> s.snowBlur = value));
            this.list.addSmall(this.blurOption("forests", s.forestsBlur, value -> s.forestsBlur = value), this.blurOption("biomes", s.biomesBlur, value -> s.biomesBlur = value));
            this.list.addSmall(this.blurOption("water", s.waterBlur, value -> s.waterBlur = value), null);
        } else {
            this.list.addSmall(this.smoothingOption("coasts", s.coasts, value -> s.coasts = value), this.smoothingOption("snow", s.snow, value -> s.snow = value));
            this.list.addSmall(this.smoothingOption("forests", s.forests, value -> s.forests = value), this.smoothingOption("biomes", s.biomes, value -> s.biomes = value));
            this.list.addSmall(this.smoothingOption("water", s.water, value -> s.water = value), null);
        }
    }

    private OptionInstance<Integer> smoothingOption(String name, int value, IntConsumer setter) {
        return new OptionInstance<>(
            PREFIX + "borders." + name,
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "smoothing." + name + ".tooltip")),
            (caption, current) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(current))),
            new OptionInstance.IntRange(1, ClientConfig.Smoothing.MAX),
            value,
            setter::accept
        );
    }

    private OptionInstance<Integer> blurOption(String name, int value, IntConsumer setter) {
        return new OptionInstance<>(
            PREFIX + "borders." + name,
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "blur.value.tooltip")),
            (caption, current) -> Options.genericValueLabel(
                caption, current == 0 ? Component.translatable(PREFIX + "blur.none") : Component.literal(String.valueOf(current))
            ),
            new OptionInstance.IntRange(0, ClientConfig.Smoothing.MAX_BLUR),
            value,
            setter::accept
        );
    }

    @Override
    public void removed() {
        super.removed();
        ClientConfig config = FarplanetwocontinuationClient.manager().config();
        config.save();
        // Tiles built with the old settings would otherwise stay mixed with new ones, so everything is loaded again.
        if (!config.tileSignature().equals(this.initialSignature)) {
            FarplanetwocontinuationClient.manager().resetTiles();
        }
    }
}
