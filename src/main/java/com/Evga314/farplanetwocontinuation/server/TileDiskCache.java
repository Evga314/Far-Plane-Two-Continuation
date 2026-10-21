package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.Evga314.farplanetwocontinuation.tile.Zip;
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
    static final int FORMAT = 14;
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

    /** Whether a tile is cached, without reading it. */
    public boolean has(Identifier dimension, TilePos pos) {
        Path file = this.file(dimension, pos);
        return file != null && Files.isRegularFile(file);
    }

    /** A cached tile in its encoded form, checked to decode; null when there is none or it is damaged. */
    public byte @Nullable [] read(Identifier dimension, TilePos pos) {
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
            try (InputStream body = Zip.decompress(in)) {
                byte[] data = body.readNBytes(TileData.MAX_ENCODED_SIZE + 1);
                TileData.decode(data);
                return data;
            }
        } catch (IOException e) {
            LOGGER.debug("Dropping unreadable cached tile {}", file, e);
            return null;
        }
    }

    /** Deletes every cached tile of one dimension of this world. */
    public void clear(Identifier dimension) {
        this.createdDirectories.clear();
        deleteTree(this.root.resolve(sanitize(dimension.getNamespace() + "_" + dimension.getPath())).normalize(), this.root);
    }

    /**
     * Clears the caches of the mod in a world made by another version of their files, on the first start with this one, so everything
     * is made again the new way at once instead of region by region as old files turn up. The tiles of the distant terrain and the
     * chunks made for detailed terrain have versions of their own, and only the outdated one is cleared: the chunks take long to make
     * again. A small file in the cache directory says which versions made it.
     */
    static void clearIfOutdated(Path worldRoot) {
        Path root = worldRoot.toAbsolutePath().normalize().resolve(FarPlaneConstants.MOD_ID).normalize();
        Path virtual = root.resolve(VIRTUAL_DIRECTORY).normalize();
        Path marker = root.resolve(VERSION_FILE);
        String tiles = "tiles " + FORMAT;
        String chunks = "virtual chunks " + VirtualChunkStore.FORMAT;
        String version = tiles + ", " + chunks;
        String old = "";
        try {
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                old = Files.readString(marker).trim();
            }
        } catch (IOException e) {
            LOGGER.debug("Could not read the cache version", e);
        }
        if (old.equals(version)) {
            return;
        }
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!(old + ",").contains(tiles + ",")) {
                LOGGER.info("Distant terrain tiles were cached by another version, clearing them");
                try (java.util.stream.Stream<Path> children = Files.list(root)) {
                    for (Path child : children.toList()) {
                        if (!child.normalize().equals(virtual)) {
                            deleteTree(child.normalize(), root);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to clear the LOD tile cache", e);
                }
            }
            if (!old.endsWith(chunks)) {
                LOGGER.info("Chunks for detailed terrain were made by another version, clearing them");
                deleteTree(virtual, root);
            }
        }
        try {
            Files.createDirectories(root);
            Files.writeString(marker, version);
        } catch (IOException e) {
            LOGGER.warn("Could not write the cache version", e);
        }
    }

    /** Where VirtualChunkStore keeps its files, inside the cache directory. */
    private static final String VIRTUAL_DIRECTORY = "virtual";

    private static final String VERSION_FILE = "cache-version.txt";

    /**
     * Deletes a directory with everything in it. Only regular files and directories inside {@code root} are removed; symbolic links
     * are never followed.
     */
    static void deleteTree(Path directory, Path root) {
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile() && file.normalize().startsWith(root)) {
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
                    if (dir.normalize().startsWith(root)) {
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

    /** Directories made by {@link #write}, so each is made once instead of for every tile; forgotten when the cache is cleared. */
    private final java.util.Set<Path> createdDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            try (DeflaterOutputStream body = Zip.compress(bytes)) {
                body.write(encoded);
            }
            Path directory = file.getParent();
            if (!this.createdDirectories.contains(directory)) {
                Files.createDirectories(directory);
                this.createdDirectories.add(directory);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.write(temp, bytes.toByteArray());
            } catch (java.nio.file.NoSuchFileException e) {
                // The directory went away meanwhile (the cache was cleared): made again once.
                Files.createDirectories(directory);
                Files.write(temp, bytes.toByteArray());
            }
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
