package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class LodConfigScreen extends OptionsSubScreen {
    static final int DISTANCE_STEPS = 200;
    private static final double DISTANCE_RATIO = (double) FarPlaneConstants.MAX_LOD_DISTANCE / FarPlaneConstants.MIN_LOD_DISTANCE;
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    private final String initialSignature = FarplanetwocontinuationClient.manager().config().tileSignature();

    public LodConfigScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig config = FarplanetwocontinuationClient.manager().config();

        OptionInstance<Boolean> enabled = OptionInstance.createBoolean(
            PREFIX + "enabled", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "enabled.tooltip")), config.enabled, value -> config.enabled = value
        );
        OptionInstance<Integer> loadSpeed = new OptionInstance<>(
            PREFIX + "load_speed",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "load_speed.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "load_speed." + value)),
            new OptionInstance.IntRange(0, LoadSpeed.values().length - 1),
            LoadSpeed.of(config.loadSpeed).ordinal(),
            value -> config.loadSpeed = value
        );
        this.list.addBig(enabled);
        this.list.addBig(loadSpeed);
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "distant_settings"), button -> this.open(new DimensionChoiceScreen(
            this, Component.translatable(PREFIX + "distant.title"), DistantConfigScreen::new
        ))).build());
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "detail_settings"), button -> this.open(new DimensionChoiceScreen(
            this, Component.translatable(PREFIX + "detailed.title"), DetailConfigScreen::new
        ))).build());
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "other_settings"), button -> this.open(new OtherConfigScreen(this))).build());
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "clear_cache"), button -> this.confirmClearCache()).build());
    }

    private void open(Screen screen) {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(screen);
        }
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
        ClientConfig config = FarplanetwocontinuationClient.manager().config();
        config.save();
        // Tiles built for the old settings would otherwise stay mixed with new ones, so everything is loaded again.
        if (!config.tileSignature().equals(this.initialSignature)) {
            FarplanetwocontinuationClient.manager().resetTiles();
        }
    }

    static int sliderToDistance(int slider) {
        if (slider >= DISTANCE_STEPS) {
            return FarPlaneConstants.MAX_LOD_DISTANCE;
        }
        double raw = FarPlaneConstants.MIN_LOD_DISTANCE * Math.pow(DISTANCE_RATIO, slider / (double) DISTANCE_STEPS);
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)) - 1.0);
        int rounded = (int) (Math.round(raw / magnitude) * magnitude);
        return Mth.clamp(rounded, FarPlaneConstants.MIN_LOD_DISTANCE, FarPlaneConstants.MAX_LOD_DISTANCE);
    }

    static int distanceToSlider(int distance) {
        double t = Math.log(Math.max(distance, FarPlaneConstants.MIN_LOD_DISTANCE) / (double) FarPlaneConstants.MIN_LOD_DISTANCE) / Math.log(DISTANCE_RATIO);
        return Mth.clamp((int) Math.round(t * DISTANCE_STEPS), 0, DISTANCE_STEPS);
    }

    static String formatNumber(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
