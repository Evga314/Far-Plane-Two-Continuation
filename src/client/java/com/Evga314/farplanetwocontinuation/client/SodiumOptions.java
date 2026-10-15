package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.TileMeshBuilder;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The settings of the mod in Sodium's video settings, as two pages: distant terrain and detailed terrain. Sodium finds this class
 * through the {@code sodium:config_api_user} entrypoint, so it is only loaded when Sodium is installed. The values are the same ones
 * the mod's own settings screen changes.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    private static final String OPTIONS = FarPlaneConstants.MOD_ID + ".options.";
    private static final String DETAILED = OPTIONS + "detailed.";
    private static final long ROUGH_TILE_BYTES = (long) TileMeshBuilder.VERTEX_COUNT * TileMeshBuilder.VERTEX_SIZE;

    /** Tile settings when the pages were last saved, to load all tiles again only when something that shapes them changed. */
    private String savedSignature = "";

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ClientConfig config = FarplanetwocontinuationClient.manager().config();
        ClientConfig defaults = new ClientConfig();
        this.savedSignature = config.tileSignature();
        StorageEventHandler storage = () -> {
            config.sanitize();
            config.save();
            String signature = config.tileSignature();
            if (!signature.equals(this.savedSignature)) {
                this.savedSignature = signature;
                FarplanetwocontinuationClient.manager().resetTiles();
            }
        };

        OptionGroupBuilder distant = builder.createOptionGroup()
            .addOption(builder.createBooleanOption(id("enabled"))
                .setName(Component.translatable(OPTIONS + "enabled"))
                .setTooltip(Component.translatable(OPTIONS + "enabled.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setDefaultValue(defaults.enabled)
                .setBinding(value -> config.enabled = value, () -> config.enabled)
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("distance"))
                .setName(Component.translatable(OPTIONS + "distance"))
                .setTooltip(Component.translatable(OPTIONS + "distance.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setRange(0, LodConfigScreen.DISTANCE_STEPS, 1)
                .setValueFormatter(value -> Component.translatable(OPTIONS + "blocks",
                    LodConfigScreen.formatNumber(LodConfigScreen.sliderToDistance(value))))
                .setDefaultValue(LodConfigScreen.distanceToSlider(defaults.lodDistance))
                .setBinding(value -> config.lodDistance = LodConfigScreen.sliderToDistance(value),
                    () -> LodConfigScreen.distanceToSlider(config.lodDistance))
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("detail"))
                .setName(Component.translatable(OPTIONS + "detail"))
                .setTooltip(Component.translatable(OPTIONS + "detail.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(0, FarPlaneConstants.MAX_MIN_LEVEL, 1)
                .setValueFormatter(value -> Component.translatable(OPTIONS + "detail.level." + value))
                .setDefaultValue(defaults.detailLevel)
                .setBinding(value -> config.detailLevel = value, () -> config.detailLevel)
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("quality"))
                .setName(Component.translatable(OPTIONS + "quality"))
                .setTooltip(Component.translatable(OPTIONS + "quality.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setRange(10, 60, 1)
                .setValueFormatter(SodiumOptions::tenths)
                .setDefaultValue(Math.round(defaults.quality * 10.0F))
                .setBinding(value -> config.quality = value / 10.0F, () -> Mth.clamp(Math.round(config.quality * 10.0F), 10, 60))
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("memory"))
                .setName(Component.translatable(OPTIONS + "memory"))
                .setTooltip(Component.translatable(OPTIONS + "memory.tooltip"))
                .setImpact(OptionImpact.VARIES)
                .setRange(0, GpuMemory.STEPS_MB.length - 1, 1)
                .setValueFormatter(value -> Component.translatable(OPTIONS + "memory.value", GpuMemory.STEPS_MB[value]))
                .setDefaultValue(GpuMemory.step(roughMegabytes(defaults.maxGpuTiles)))
                .setBinding(value -> config.maxGpuTiles = (int) (GpuMemory.STEPS_MB[value] * 1024L * 1024L / ROUGH_TILE_BYTES),
                    () -> GpuMemory.step(roughMegabytes(config.maxGpuTiles)))
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("fog"))
                .setName(Component.translatable(OPTIONS + "fog"))
                .setTooltip(Component.translatable(OPTIONS + "fog.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setRange(10, 98, 1)
                .setValueFormatter(value -> Component.literal(value + "%"))
                .setDefaultValue(Math.round(defaults.fogStart * 100.0F))
                .setBinding(value -> config.fogStart = value / 100.0F, () -> Mth.clamp(Math.round(config.fogStart * 100.0F), 10, 98))
                .setStorageHandler(storage))
            .addOption(builder.createBooleanOption(id("no_fog"))
                .setName(Component.translatable(OPTIONS + "no_fog"))
                .setTooltip(Component.translatable(OPTIONS + "no_fog.tooltip"))
                .setImpact(OptionImpact.LOW)
                .setDefaultValue(defaults.disableFog)
                .setBinding(value -> config.disableFog = value, () -> config.disableFog)
                .setStorageHandler(storage));

        OptionGroupBuilder cache = builder.createOptionGroup()
            .addOption(builder.createExternalButtonOption(id("clear_cache"))
                .setName(Component.translatable(OPTIONS + "clear_cache"))
                .setTooltip(Component.translatable(OPTIONS + "clear_cache.message"))
                .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        FarplanetwocontinuationClient.manager().reloadAll();
                    }
                    Minecraft.getInstance().gui.setScreen(parent);
                }, Component.translatable(OPTIONS + "clear_cache.title"), Component.translatable(OPTIONS + "clear_cache.message")))));

        OptionGroupBuilder detailed = builder.createOptionGroup()
            .addOption(builder.createBooleanOption(id("detailed_enabled"))
                .setName(Component.translatable(DETAILED + "enabled"))
                .setTooltip(Component.translatable(DETAILED + "enabled.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setDefaultValue(defaults.detailEnabled)
                .setBinding(value -> config.detailEnabled = value, () -> config.detailEnabled)
                .setStorageHandler(storage))
            .addOption(builder.createBooleanOption(id("detailed_generate"))
                .setName(Component.translatable(DETAILED + "generate"))
                .setTooltip(Component.translatable(DETAILED + "generate.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setDefaultValue(defaults.detailGenerate)
                .setBinding(value -> config.detailGenerate = value, () -> config.detailGenerate)
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("detailed_radius"))
                .setName(Component.translatable(DETAILED + "radius"))
                .setTooltip(Component.translatable(DETAILED + "radius.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(0, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS, 1)
                .setValueFormatter(value -> Component.translatable(DETAILED + "chunks", value))
                .setDefaultValue(defaults.detailRadiusChunks)
                .setBinding(value -> config.detailRadiusChunks = value, () -> config.detailRadiusChunks)
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("detailed_detail"))
                .setName(Component.translatable(DETAILED + "detail"))
                .setTooltip(Component.translatable(DETAILED + "detail.tooltip"))
                .setImpact(OptionImpact.HIGH)
                .setRange(FarPlaneConstants.DETAIL_MIN_LEVEL, FarPlaneConstants.DETAIL_MAX_LEVEL, 1)
                .setValueFormatter(value -> Component.translatable(OPTIONS + "detail.level." + (value - 1)))
                .setDefaultValue(defaults.detailMinLevel)
                .setBinding(value -> config.detailMinLevel = value,
                    () -> Mth.clamp(config.detailMinLevel, FarPlaneConstants.DETAIL_MIN_LEVEL, FarPlaneConstants.DETAIL_MAX_LEVEL))
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("detailed_quality"))
                .setName(Component.translatable(DETAILED + "quality"))
                .setTooltip(Component.translatable(DETAILED + "quality.tooltip"))
                .setImpact(OptionImpact.MEDIUM)
                .setRange(10, 60, 1)
                .setValueFormatter(SodiumOptions::tenths)
                .setDefaultValue(Math.round(defaults.detailQuality * 10.0F))
                .setBinding(value -> config.detailQuality = value / 10.0F,
                    () -> Mth.clamp(Math.round(config.detailQuality * 10.0F), 10, 60))
                .setStorageHandler(storage))
            .addOption(builder.createIntegerOption(id("detailed_memory"))
                .setName(Component.translatable(DETAILED + "memory"))
                .setTooltip(Component.translatable(DETAILED + "memory.tooltip"))
                .setImpact(OptionImpact.VARIES)
                .setRange(0, GpuMemory.STEPS_MB.length - 1, 1)
                .setValueFormatter(value -> Component.translatable(DETAILED + "megabytes", GpuMemory.STEPS_MB[value]))
                .setDefaultValue(GpuMemory.step(defaults.detailGpuMegabytes))
                .setBinding(value -> config.detailGpuMegabytes = GpuMemory.STEPS_MB[value], () -> GpuMemory.step(config.detailGpuMegabytes))
                .setStorageHandler(storage));

        builder.registerOwnModOptions()
            .setNonTintedIcon(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "icon.png"))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable(OPTIONS + "enabled"))
                .addOptionGroup(distant)
                .addOptionGroup(cache))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable(DETAILED + "title"))
                .addOptionGroup(detailed));
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, name);
    }

    private static Component tenths(int value) {
        return Component.literal(String.format(Locale.ROOT, "%.1f", value / 10.0F));
    }

    private static int roughMegabytes(int tiles) {
        return (int) (tiles * ROUGH_TILE_BYTES / (1024 * 1024));
    }
}
