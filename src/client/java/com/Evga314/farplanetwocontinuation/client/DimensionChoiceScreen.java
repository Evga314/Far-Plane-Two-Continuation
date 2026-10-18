package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import java.util.Arrays;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** One button per kind of dimension with LOD terrain, each opening the settings of a terrain layer for that dimension. */
public class DimensionChoiceScreen extends OptionsSubScreen {
    private static final String PREFIX = FarPlaneConstants.MOD_ID + ".options.";

    private final BiFunction<Screen, DimensionKind, Screen> settings;

    public DimensionChoiceScreen(Screen lastScreen, Component title, BiFunction<Screen, DimensionKind, Screen> settings) {
        super(lastScreen, Minecraft.getInstance().options, title);
        this.settings = settings;
    }

    /** Dimensions with settings of their own; the nether has no LOD terrain yet. */
    public static DimensionKind[] dimensions() {
        return Arrays.stream(DimensionKind.values()).filter(DimensionKind::hasLod).toArray(DimensionKind[]::new);
    }

    public static Component dimensionName(DimensionKind dimension) {
        return Component.translatable(PREFIX + "dimension." + dimension.id());
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        for (DimensionKind dimension : dimensions()) {
            this.list.addBig(Button.builder(dimensionName(dimension), button -> {
                if (this.minecraft != null) {
                    this.minecraft.gui.setScreen(this.settings.apply(this, dimension));
                }
            }).build());
        }
    }
}
