package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Settings of the detailed ring built from real chunks around the player, for one kind of dimension. */
public class DetailConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.detailed.";

    private final String initialSignature = FarplanetwocontinuationClient.manager().config().tileSignature();
    private final DimensionKind dimension;

    public DetailConfigScreen(Screen lastScreen, DimensionKind dimension) {
        super(lastScreen, Minecraft.getInstance().options, title(dimension));
        this.dimension = dimension;
    }

    public static Component title(DimensionKind dimension) {
        return Component.translatable(PREFIX + "title." + dimension.id());
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig.DetailSettings config = FarplanetwocontinuationClient.manager().config().detail(this.dimension);

        OptionInstance<Boolean> enabled = OptionInstance.createBoolean(
            PREFIX + "enabled", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "enabled.tooltip")), config.enabled,
            value -> config.enabled = value
        );
        OptionInstance<Boolean> generate = OptionInstance.createBoolean(
            PREFIX + "generate", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "generate.tooltip")), config.generate,
            value -> config.generate = value
        );
        OptionInstance<Integer> radius = new OptionInstance<>(
            PREFIX + "radius",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "radius.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "chunks", value)),
            new OptionInstance.IntRange(0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS),
            config.radiusChunks,
            value -> config.radiusChunks = value
        );
        OptionInstance<Integer> memory = new OptionInstance<>(
            PREFIX + "memory",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "memory.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "megabytes", GpuMemory.STEPS_MB[value])),
            new OptionInstance.IntRange(0, GpuMemory.STEPS_MB.length - 1),
            GpuMemory.step(config.gpuMegabytes),
            value -> config.gpuMegabytes = GpuMemory.STEPS_MB[value]
        );

        OptionInstance<Integer> blocks = new OptionInstance<>(
            PREFIX + "blocks",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "blocks.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "blocks.level." + value)),
            new OptionInstance.IntRange(0, BlockDetail.values().length - 1),
            config.blocks().ordinal(),
            value -> config.blockDetail = value
        );

        int step = FarPlaneConstants.SCAN_DEPTH_STEP;
        OptionInstance<Integer> depth = new OptionInstance<>(
            PREFIX + "depth",
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "depth.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable(PREFIX + "depth.blocks", value * step)),
            new OptionInstance.IntRange(FarPlaneConstants.MIN_SCAN_DEPTH / step, FarPlaneConstants.MAX_SCAN_DEPTH / step),
            config.scanDepth / step,
            value -> config.scanDepth = value * step
        );

        this.list.addBig(enabled);
        this.list.addBig(generate);
        this.list.addBig(radius);
        this.list.addBig(blocks);
        this.list.addBig(memory);
        this.list.addBig(depth);
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
