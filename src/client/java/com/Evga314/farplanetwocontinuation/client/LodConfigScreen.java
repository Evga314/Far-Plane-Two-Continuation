package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.TileMeshBuilder;
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
        OptionInstance<Integer> distance = new OptionInstance<>(
            PREFIX + "distance",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "distance.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "blocks", formatNumber(sliderToDistance(value)))),
            new OptionInstance.IntRange(0, DISTANCE_STEPS),
            distanceToSlider(config.lodDistance),
            value -> config.lodDistance = sliderToDistance(value)
        );
        OptionInstance<Integer> detail = new OptionInstance<>(
            PREFIX + "detail",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "detail.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "detail.level." + value)),
            new OptionInstance.IntRange(0, FarPlaneConstants.MAX_MIN_LEVEL),
            config.detailLevel,
            value -> config.detailLevel = value
        );
        OptionInstance<Integer> quality = new OptionInstance<>(
            PREFIX + "quality",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "quality.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%.1f", value / 10.0F))),
            new OptionInstance.IntRange(10, 60),
            Mth.clamp(Math.round(config.quality * 10.0F), 10, 60),
            value -> config.quality = value / 10.0F
        );
        long tileBytes = (long) TileMeshBuilder.VERTEX_COUNT * TileMeshBuilder.VERTEX_SIZE;
        OptionInstance<Integer> memory = new OptionInstance<>(
            PREFIX + "memory",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "memory.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "memory.value", GpuMemory.STEPS_MB[value])),
            new OptionInstance.IntRange(0, GpuMemory.STEPS_MB.length - 1),
            GpuMemory.step((int) (config.maxGpuTiles * tileBytes / (1024 * 1024))),
            value -> config.maxGpuTiles = (int) (GpuMemory.STEPS_MB[value] * 1024L * 1024L / tileBytes)
        );
        OptionInstance<Integer> fog = new OptionInstance<>(
            PREFIX + "fog",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "fog.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(value + "%")),
            new OptionInstance.IntRange(10, 98),
            Mth.clamp(Math.round(config.fogStart * 100.0F), 10, 98),
            value -> config.fogStart = value / 100.0F
        );
        OptionInstance<Boolean> noFog = OptionInstance.createBoolean(
            PREFIX + "no_fog", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "no_fog.tooltip")), config.disableFog, value -> config.disableFog = value
        );

        this.list.addBig(enabled);
        this.list.addBig(distance);
        this.list.addSmall(detail, quality);
        this.list.addSmall(memory, fog);
        this.list.addBig(noFog);
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "detail_settings"), button -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new DetailConfigScreen(this));
            }
        }).build());
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
