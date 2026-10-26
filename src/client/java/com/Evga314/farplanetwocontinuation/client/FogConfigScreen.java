package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Fog settings for one kind of dimension: out of water and under water, each on a screen of its own. See LodFog. */
public class FogConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    private final DimensionKind dimension;

    public FogConfigScreen(Screen lastScreen, DimensionKind dimension) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "fog.title." + dimension.id()));
        this.dimension = dimension;
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "fog_surface"), button -> this.open(new Surface(this, this.dimension))).build());
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "fog_water"), button -> this.open(new Water(this, this.dimension))).build());
    }

    private void open(Screen screen) {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(screen);
        }
    }

    @Override
    public void removed() {
        super.removed();
        FarplanetwocontinuationClient.manager().config().save();
    }

    /** The fog out of water: whose it is, and this mod's own. */
    static class Surface extends OptionsSubScreen {
        private final DimensionKind dimension;
        private OptionInstance<Integer> start;
        private OptionInstance<Integer> end;
        private OptionInstance<Integer> shape;

        Surface(Screen lastScreen, DimensionKind dimension) {
            super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "fog_surface.title." + dimension.id()));
            this.dimension = dimension;
        }

        @Override
        protected void addOptions() {
            if (this.list == null) {
                return;
            }
            ClientConfig.DistantSettings config = FarplanetwocontinuationClient.manager().config().distant(this.dimension);

            OptionInstance<Integer> mode = new OptionInstance<>(
                PREFIX + "fog_mode",
                value -> Tooltip.create(Component.translatable(PREFIX + "fog_mode." + value + ".tooltip")),
                // A switch puts its caption in front of the value itself.
                (caption, value) -> Component.translatable(PREFIX + "fog_mode." + value),
                new OptionInstance.Enum<>(List.of(ClientConfig.FOG_AUTO, ClientConfig.FOG_OWN, ClientConfig.FOG_OTHER, ClientConfig.FOG_OFF), Codec.INT),
                config.fogMode,
                value -> {
                    config.fogMode = value;
                    this.updateActive(value);
                }
            );
            // 0 is the LOD distance; above it the same steps as the LOD distance itself.
            this.end = new OptionInstance<>(
                PREFIX + "fog_end",
                OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "fog_end.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, value == 0
                    ? Component.translatable(PREFIX + "fog_end.auto")
                    : Component.translatable(PREFIX + "blocks", LodConfigScreen.formatNumber(LodConfigScreen.sliderToDistance(value - 1)))),
                new OptionInstance.IntRange(0, LodConfigScreen.DISTANCE_STEPS + 1),
                config.fogEnd <= 0 ? 0 : LodConfigScreen.distanceToSlider(config.fogEnd) + 1,
                value -> config.fogEnd = value == 0 ? 0 : LodConfigScreen.sliderToDistance(value - 1)
            );
            this.start = new OptionInstance<>(
                PREFIX + "fog",
                OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "fog.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(value + "%")),
                new OptionInstance.IntRange(0, 100),
                Mth.clamp(Math.round(config.fogStart * 100.0F), 0, 100),
                value -> config.fogStart = value / 100.0F
            );
            this.shape = new OptionInstance<>(
                PREFIX + "fog_shape",
                OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "fog_shape.tooltip")),
                (caption, value) -> Component.translatable(PREFIX + "fog_shape." + value),
                new OptionInstance.Enum<>(List.of(0, 1), Codec.INT),
                config.fogShape,
                value -> config.fogShape = value
            );

            this.list.addBig(mode);
            this.list.addSmall(this.end, this.start);
            this.list.addBig(this.shape);
            this.updateActive(config.fogMode);
        }

        /** This mod's own fog only counts in its own mode and the automatic one. */
        private void updateActive(int mode) {
            boolean own = mode == ClientConfig.FOG_AUTO || mode == ClientConfig.FOG_OWN;
            for (OptionInstance<?> option : List.of(this.start, this.end, this.shape)) {
                AbstractWidget widget = this.list == null ? null : this.list.findOption(option);
                if (widget != null) {
                    widget.active = own;
                }
            }
        }

        @Override
        public void removed() {
            super.removed();
            FarplanetwocontinuationClient.manager().config().save();
        }
    }

    /** The fog under water. */
    static class Water extends OptionsSubScreen {
        private final DimensionKind dimension;

        Water(Screen lastScreen, DimensionKind dimension) {
            super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "fog_water.title." + dimension.id()));
            this.dimension = dimension;
        }

        @Override
        protected void addOptions() {
            if (this.list == null) {
                return;
            }
            ClientConfig.DistantSettings config = FarplanetwocontinuationClient.manager().config().distant(this.dimension);
            this.list.addBig(OptionInstance.createBoolean(
                PREFIX + "water_fog", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "water_fog.tooltip")),
                config.waterFog, value -> config.waterFog = value
            ));
        }

        @Override
        public void removed() {
            super.removed();
            FarplanetwocontinuationClient.manager().config().save();
        }
    }
}
