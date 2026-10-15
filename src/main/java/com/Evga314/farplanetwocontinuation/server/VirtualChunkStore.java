package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Summaries of chunks nobody has visited yet, made by {@link VirtualTerrain}. Generating them is the expensive part, so every region is
 * kept on disk once made and never generated twice, also across restarts. The world seed is part of every file, so files of another
 * world are never mixed in. When a chunk is really generated later its real blocks take over.
 */
final class VirtualChunkStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x46505643;
    private static final int FORMAT = 7;
    private static final int REGION = VirtualTerrain.REGION;
    /** About 1.6 MB per region in memory; everything else is read back from disk when needed. */
    private static final int MEMORY_REGIONS = 12;
    private static final int MAX_FILE_SIZE = 4 * 1024 * 1024;
    private static final long BUSY_TICK_NANOS = 35_000_000L;
    private static final long WAIT_MS = 100L;
    /** Never wait longer than this in a row, so a server that is always busy still gets its distant terrain eventually. */
    private static final int MAX_WAITS = 50;

    private final Path root;
    private final long seed;
    private final Map<Key, ChunkSummary[]> memory = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ChunkSummary[]> eldest) {
            return this.size() > MEMORY_REGIONS;
        }
    };
    /** One lock per region, so two threads never generate the same chunks. */
    private final Map<Key, Object> locks = new HashMap<>();
    private final Map<ResourceKey<Level>, @Nullable VirtualTerrain> terrains = new HashMap<>();
    /** While rough terrain is still loading only one region is generated at a time, so the view fills up first. */
    private final Semaphore roughFirst = new Semaphore(1);
    private volatile boolean roughBusy;

    VirtualChunkStore(Path worldRoot, long seed) {
        this.root = worldRoot.toAbsolutePath().normalize().resolve(FarPlaneConstants.MOD_ID).resolve("virtual").normalize();
        this.seed = seed;
    }

    void setRoughBusy(boolean busy) {
        this.roughBusy = busy;
    }

    /** Whether virtual chunks can be made in a level at all. */
    boolean supports(ServerLevel level) {
        return this.terrain(level) != null;
    }

    /**
     * Summaries of chunks nobody has generated yet. Of every region only the rectangle around the chunks that are asked for and not
     * made before is generated: around places players explored, most chunks are real and never need a virtual copy.
     */
    Long2ObjectMap<ChunkSummary> chunks(ServerLevel level, LongCollection wanted) {
        Long2ObjectOpenHashMap<ChunkSummary> result = new Long2ObjectOpenHashMap<>();
        VirtualTerrain terrain = this.terrain(level);
        if (terrain == null || wanted.isEmpty()) {
            return result;
        }
        Map<Key, LongArrayList> byRegion = new HashMap<>();
        for (long chunk : wanted) {
            Key key = new Key(level.dimension().identifier(), Math.floorDiv(ChunkPos.getX(chunk), REGION), Math.floorDiv(ChunkPos.getZ(chunk), REGION));
            byRegion.computeIfAbsent(key, k -> new LongArrayList()).add(chunk);
        }
        for (Map.Entry<Key, LongArrayList> entry : byRegion.entrySet()) {
            ChunkSummary[] region = this.region(level, terrain, entry.getKey(), entry.getValue());
            for (long chunk : entry.getValue()) {
                ChunkSummary summary = region[index(chunk)];
                if (summary != null) {
                    result.put(chunk, summary);
                }
            }
        }
        return result;
    }

    private static int index(long chunk) {
        return Math.floorMod(ChunkPos.getX(chunk), REGION) + Math.floorMod(ChunkPos.getZ(chunk), REGION) * REGION;
    }

    /** One region with at least the wanted chunks made. Threads asking for the same region take turns, so nothing is made twice. */
    private ChunkSummary[] region(ServerLevel level, VirtualTerrain terrain, Key key, LongArrayList wanted) {
        Object lock;
        synchronized (this) {
            lock = this.locks.computeIfAbsent(key, k -> new Object());
        }
        synchronized (lock) {
            ChunkSummary[] region;
            synchronized (this) {
                region = this.memory.get(key);
            }
            if (region == null) {
                region = this.read(level, key);
                if (region == null) {
                    region = new ChunkSummary[REGION * REGION];
                }
            }
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (long chunk : wanted) {
                if (region[index(chunk)] == null) {
                    minX = Math.min(minX, ChunkPos.getX(chunk));
                    minZ = Math.min(minZ, ChunkPos.getZ(chunk));
                    maxX = Math.max(maxX, ChunkPos.getX(chunk));
                    maxZ = Math.max(maxZ, ChunkPos.getZ(chunk));
                }
            }
            if (minX <= maxX) {
                waitForServer(level);
                boolean gated = this.roughBusy;
                if (gated) {
                    this.roughFirst.acquireUninterruptibly();
                }
                ProtoChunk[] made;
                try {
                    made = terrain.generate(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
                } finally {
                    if (gated) {
                        this.roughFirst.release();
                    }
                }
                region = region.clone();
                int width = maxX - minX + 1;
                for (int i = 0; i < made.length; i++) {
                    ProtoChunk chunk = made[i];
                    region[index(ChunkPos.pack(minX + i % width, minZ + i / width))] =
                        ChunkSummary.saved(chunk.getSections(), chunk.getMinSectionY(), null, level.getMinY(), level.getMaxY());
                }
                this.write(level, key, region);
            }
            synchronized (this) {
                this.memory.put(key, region);
            }
            return region;
        }
    }

    /**
     * Generating regions is heavy work in the background. While the server itself is short of time (a tick takes longer than
     * {@link #BUSY_TICK_NANOS}), new regions wait a moment, so the game never stutters because of distant terrain.
     */
    private static void waitForServer(ServerLevel level) {
        for (int i = 0; i < MAX_WAITS && level.getServer().getAverageTickTimeNanos() > BUSY_TICK_NANOS && level.getServer().isRunning(); i++) {
            try {
                Thread.sleep(WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized @Nullable VirtualTerrain terrain(ServerLevel level) {
        return this.terrains.computeIfAbsent(level.dimension(), dimension -> VirtualTerrain.create(level));
    }

    synchronized void clear() {
        this.memory.clear();
        this.locks.clear();
    }

    private @Nullable Path file(Key key) {
        String dimension = sanitize(key.dimension.getNamespace() + "_" + key.dimension.getPath());
        Path path = this.root.resolve(dimension).resolve(key.x + "_" + key.z + ".bin").normalize();
        return path.startsWith(this.root) ? path : null;
    }

    private static String sanitize(String name) {
        StringBuilder result = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return result.toString();
    }

    private ChunkSummary @Nullable [] read(ServerLevel level, Key key) {
        Path file = this.file(key);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return null;
            }
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)));
            if (header.readInt() != MAGIC || header.readInt() != FORMAT || header.readLong() != this.seed) {
                return null;
            }
            try (InputStream body = new InflaterInputStream(header)) {
                DataInputStream in = new DataInputStream(body);
                Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
                int paletteSize = in.readUnsignedShort();
                List<Holder<Biome>> palette = new ArrayList<>(paletteSize);
                for (int i = 0; i < paletteSize; i++) {
                    byte[] text = new byte[in.readUnsignedShort()];
                    in.readFully(text);
                    Identifier id = Identifier.tryParse(new String(text, StandardCharsets.UTF_8));
                    // A biome that no longer exists (a removed data pack) makes the whole region stale.
                    Holder<Biome> biome = id == null ? null : biomes.get(id).orElse(null);
                    if (biome == null) {
                        return null;
                    }
                    palette.add(biome);
                }
                long[] present = new long[REGION * REGION / 64];
                for (int i = 0; i < present.length; i++) {
                    present[i] = in.readLong();
                }
                ChunkSummary[] summaries = new ChunkSummary[REGION * REGION];
                for (int i = 0; i < summaries.length; i++) {
                    if ((present[i >> 6] & 1L << (i & 63)) != 0) {
                        summaries[i] = ChunkSummary.read(in, palette, level.getMinY(), level.getMaxY());
                    }
                }
                return summaries;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Ignoring broken virtual region {}", file, e);
            return null;
        }
    }

    private void write(ServerLevel level, Key key, ChunkSummary[] summaries) {
        Path file = this.file(key);
        if (file == null) {
            return;
        }
        try {
            Object2IntOpenHashMap<Holder<Biome>> palette = new Object2IntOpenHashMap<>();
            List<Holder<Biome>> ordered = new ArrayList<>();
            for (ChunkSummary summary : summaries) {
                if (summary == null) {
                    continue;
                }
                summary.collectBiomes(biome -> {
                    if (!palette.containsKey(biome) && biome.unwrapKey().isPresent()) {
                        palette.put(biome, ordered.size());
                        ordered.add(biome);
                    }
                });
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            DataOutputStream header = new DataOutputStream(bytes);
            header.writeInt(MAGIC);
            header.writeInt(FORMAT);
            header.writeLong(this.seed);
            header.flush();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                out.writeShort(ordered.size());
                for (Holder<Biome> biome : ordered) {
                    byte[] text = biome.unwrapKey().orElseThrow().identifier().toString().getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                }
                long[] present = new long[REGION * REGION / 64];
                for (int i = 0; i < summaries.length; i++) {
                    if (summaries[i] != null) {
                        present[i >> 6] |= 1L << (i & 63);
                    }
                }
                for (long word : present) {
                    out.writeLong(word);
                }
                for (ChunkSummary summary : summaries) {
                    if (summary != null) {
                        summary.write(out, palette);
                    }
                }
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, bytes.toByteArray());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not save virtual region {}", file, e);
        }
    }

    private record Key(Identifier dimension, int x, int z) {
    }
}
