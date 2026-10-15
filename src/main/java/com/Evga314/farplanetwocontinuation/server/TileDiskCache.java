package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Stores generated tiles inside the world folder so they do not have to be generated again. Every path is built from numbers and a
 * sanitized dimension name and is checked to stay inside the cache root.
 */
public final class TileDiskCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x46503243;
    private static final int FORMAT = 6;
    private static final long MAX_FILE_SIZE = TileData.MAX_ENCODED_SIZE;

    private final Path root;
    private final long seed;

    public TileDiskCache(Path worldRoot, long seed) {
        this.root = worldRoot.toAbsolutePath().normalize().resolve(FarPlaneConstants.MOD_ID).normalize();
        this.seed = seed;
    }

    private @Nullable Path file(Identifier dimension, TilePos pos) {
        String dimensionDir = sanitize(dimension.getNamespace() + "_" + dimension.getPath());
        Path path = this.root
            .resolve(dimensionDir)
            .resolve("L" + pos.level())
            .resolve(pos.x() + "_" + pos.z() + ".bin")
            .normalize();
        return path.startsWith(this.root) ? path : null;
    }

    private static String sanitize(String name) {
        StringBuilder result = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return result.toString();
    }

    public @Nullable TileData read(Identifier dimension, TilePos pos) {
        Path file = this.file(dimension, pos);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return null;
            }
            byte[] raw = Files.readAllBytes(file);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readLong() != this.seed) {
                return null;
            }
            try (InputStream body = new InflaterInputStream(in)) {
                byte[] data = body.readNBytes(TileData.MAX_ENCODED_SIZE + 1);
                return TileData.decode(data);
            }
        } catch (IOException e) {
            LOGGER.debug("Dropping unreadable cached tile {}", file, e);
            return null;
        }
    }

    /**
     * Deletes every cached tile of this world. Only regular files and directories inside the cache root are removed; symbolic links
     * are never followed.
     */
    public void clear() {
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(this.root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile() && file.normalize().startsWith(TileDiskCache.this.root)) {
                        Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exception) {
                    if (dir.normalize().startsWith(TileDiskCache.this.root)) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (IOException e) {
                            LOGGER.debug("Could not remove cache directory {}", dir, e);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to clear the LOD tile cache", e);
        }
    }

    public void write(Identifier dimension, TilePos pos, byte[] encoded) {
        Path file = this.file(dimension, pos);
        if (file == null) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length / 2 + 32);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(this.seed);
            out.flush();
            try (DeflaterOutputStream body = new DeflaterOutputStream(bytes)) {
                body.write(encoded);
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, bytes.toByteArray());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to cache tile {}", file, e);
        }
    }
}
