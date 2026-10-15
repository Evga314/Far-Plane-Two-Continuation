package com.Evga314.farplanetwocontinuation.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Reads and writes the mod's JSON configs. Files only ever live directly inside the loader's config directory.
 */
public final class ConfigFiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_CONFIG_SIZE = 64 * 1024;

    private ConfigFiles() {
    }

    private static Path resolve(String fileName) {
        Path dir = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir)) {
            throw new IllegalArgumentException("Config file outside of config dir: " + fileName);
        }
        return file;
    }

    public static <T> T load(String fileName, Class<T> type, Supplier<T> defaults) {
        Path file = resolve(fileName);
        if (Files.isRegularFile(file)) {
            try {
                if (Files.size(file) <= MAX_CONFIG_SIZE) {
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        T value = GSON.fromJson(reader, type);
                        if (value != null) {
                            return value;
                        }
                    }
                } else {
                    LOGGER.warn("Config {} is too large, using defaults", fileName);
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.warn("Failed to read config {}, using defaults", fileName, e);
            }
        }
        T value = defaults.get();
        save(fileName, value);
        return value;
    }

    public static void save(String fileName, Object value) {
        Path file = resolve(fileName);
        Path temp = file.resolveSibling(fileName + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Failed to save config {}", fileName, e);
        }
    }
}
