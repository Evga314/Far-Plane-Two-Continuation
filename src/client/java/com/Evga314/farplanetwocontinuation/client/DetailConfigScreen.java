package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Settings of the detailed ring built from real chunks around the player. */
public class DetailConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.detailed.";

    private final String initialSignature = FarplanetwocontinuationClient.manager().config().tileSignature();

    public DetailConfigScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig config = FarplanetwocontinuationClient.manager().config();

        OptionInstance<Boolean> enabled = OptionInstance.createBoolean(
            PREFIX + "enabled", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "enabled.tooltip")), config.detailEnabled,
            value -> config.detailEnabled = value
        );
        OptionInstance<Boolean> generate = OptionInstance.createBoolean(
            PREFIX + "generate", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "generate.tooltip")), config.detailGenerate,
            value -> config.detailGenerate = value
        );
        OptionInstance<Integer> radius = new OptionInstance<>(
            PREFIX + "radius",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "radius.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "chunks", value)),
            new OptionInstance.IntRange(0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS),
            config.detailRadiusChunks,
            value -> config.detailRadiusChunks = value
        );
        OptionInstance<Integer> detail = new OptionInstance<>(
            PREFIX + "detail",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "detail.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(FarPlaneConstants.MOD_ID + ".options.detail.level." + (value - 1))),
            new OptionInstance.IntRange(FarPlaneConstants.DETAIL_MIN_LEVEL, 3),
            Mth.clamp(config.detailMinLevel, FarPlaneConstants.DETAIL_MIN_LEVEL, 3),
            value -> config.detailMinLevel = value
        );
        OptionInstance<Integer> quality = new OptionInstance<>(
            PREFIX + "quality",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "quality.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%.1f", value / 10.0F))),
            new OptionInstance.IntRange(10, 60),
            Mth.clamp(Math.round(config.detailQuality * 10.0F), 10, 60),
            value -> config.detailQuality = value / 10.0F
        );
        OptionInstance<Integer> memory = new OptionInstance<>(
            PREFIX + "memory",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "memory.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "megabytes", GpuMemory.STEPS_MB[value])),
            new OptionInstance.IntRange(0, GpuMemory.STEPS_MB.length - 1),
            GpuMemory.step(config.detailGpuMegabytes),
            value -> config.detailGpuMegabytes = GpuMemory.STEPS_MB[value]
        );

        this.list.addBig(enabled);
        this.list.addBig(generate);
        this.list.addBig(radius);
        this.list.addSmall(detail, quality);
        this.list.addBig(memory);
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
}
