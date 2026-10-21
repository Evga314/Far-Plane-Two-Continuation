package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.TileMeshBuilder;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Settings of the rough distant terrain, for one kind of dimension. */
public class DistantConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";
    private static final long TILE_BYTES = (long) TileMeshBuilder.VERTEX_COUNT * TileMeshBuilder.ROUGH_VERTEX_SIZE;

    private final String initialSignature = FarplanetwocontinuationClient.manager().config().tileSignature();
    private final DimensionKind dimension;

    public DistantConfigScreen(Screen lastScreen, DimensionKind dimension) {
        super(lastScreen, Minecraft.getInstance().options, title(dimension));
        this.dimension = dimension;
    }

    public static Component title(DimensionKind dimension) {
        return Component.translatable(PREFIX + "distant.title." + dimension.id());
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig.DistantSettings config = FarplanetwocontinuationClient.manager().config().distant(this.dimension);

        OptionInstance<Integer> distance = new OptionInstance<>(
            PREFIX + "distance",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "distance.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "blocks", LodConfigScreen.formatNumber(LodConfigScreen.sliderToDistance(value)))),
            new OptionInstance.IntRange(0, LodConfigScreen.DISTANCE_STEPS),
            LodConfigScreen.distanceToSlider(config.lodDistance),
            value -> config.lodDistance = LodConfigScreen.sliderToDistance(value)
        );
        OptionInstance<Integer> start = new OptionInstance<>(
            PREFIX + "start",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "start.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, startLabel(value)),
            new OptionInstance.IntRange(0, ClientConfig.MAX_START_LEVEL),
            config.startLevel,
            value -> config.startLevel = value
        );
        OptionInstance<Integer> sharpness = new OptionInstance<>(
            PREFIX + "sharpness",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "sharpness.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "sharpness." + value)),
            new OptionInstance.IntRange(0, ClientConfig.SHARPNESS.length - 1),
            config.sharpness,
            value -> config.sharpness = value
        );
        OptionInstance<Integer> memory = new OptionInstance<>(
            PREFIX + "memory",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "memory.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "memory.value", GpuMemory.STEPS_MB[value])),
            new OptionInstance.IntRange(0, GpuMemory.STEPS_MB.length - 1),
            GpuMemory.step((int) (config.maxGpuTiles * TILE_BYTES / (1024 * 1024))),
            value -> config.maxGpuTiles = (int) (GpuMemory.STEPS_MB[value] * 1024L * 1024L / TILE_BYTES)
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

        this.list.addBig(distance);
        this.list.addSmall(start, sharpness);
        this.list.addSmall(memory, fog);
        this.list.addBig(noFog);
        if (this.dimension == DimensionKind.OVERWORLD) {
            this.list.addSmall(this.bordersButton("smoothing", config, false), this.bordersButton("blur", config, true));
        }
    }

    /** A button opening the smoothing or the blur of borders, see {@link SmoothingConfigScreen}. */
    private Button bordersButton(String name, ClientConfig.DistantSettings config, boolean blur) {
        return Button.builder(Component.translatable(PREFIX + name), button -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new SmoothingConfigScreen(this, config.smoothing, blur));
            }
        }).tooltip(Tooltip.create(Component.translatable(PREFIX + name + ".tooltip"))).build();
    }

    /** The cell size of a start level, "4 blocks" for 2. */
    static Component startLabel(int level) {
        return Component.translatable(PREFIX + "start.level." + level);
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
