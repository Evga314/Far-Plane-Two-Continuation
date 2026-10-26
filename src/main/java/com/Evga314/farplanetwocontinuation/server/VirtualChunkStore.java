package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain;
import com.Evga314.farplanetwocontinuation.tile.BlockLook;
import com.Evga314.farplanetwocontinuation.tile.Zip;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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
    static final int FORMAT = 28;
    private static final int REGION = VirtualTerrain.REGION;
    /**
     * About 2 MB per region in memory, one region per 256 MB of heap (16 with 4 GB): enough for the regions around a player with a
     * wide ring of detailed tiles. Everything else is read back from disk when needed.
     */
    private static final int MEMORY_REGIONS = (int) Math.clamp(Runtime.getRuntime().maxMemory() >> 28, 6L, 24L);
    private static final int MAX_FILE_SIZE = 4 * 1024 * 1024;
    private static final long BUSY_TICK_NANOS = 35_000_000L;
    private static final long WAIT_MS = 100L;
    /** Never wait longer than this in a row, so a server that is always busy still gets its distant terrain eventually. */
    private static final int MAX_WAITS = 50;
    /**
     * When at least this many chunks of a region are asked for at once (a detailed tile or more), the whole region is made. Every batch
     * is generated with a margin around it, so a whole region costs about half as much per chunk as the chunks of a single tile, and
     * the tiles around a requested one are almost always wanted right after it.
     */
    private static final int WHOLE_REGION = 16;

    private final Path root;
    private final long seed;
    private final Map<Key, ChunkSummary[]> memory = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ChunkSummary[]> eldest) {
            return this.size() > MEMORY_REGIONS;
        }
    };
    /**
     * Locks for regions, so two threads never generate the same chunks. A fixed set shared by hash: a lock per region would pile up
     * while flying around, and two regions rarely share one.
     */
    private final Object[] locks = new Object[64];
    /** Regions being generated right now. */
    private final java.util.Set<Key> generating = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<ResourceKey<Level>, @Nullable VirtualTerrain> terrains = new HashMap<>();

    VirtualChunkStore(Path worldRoot, long seed) {
        for (int i = 0; i < this.locks.length; i++) {
            this.locks[i] = new Object();
        }
        this.root = worldRoot.toAbsolutePath().normalize().resolve(FarPlaneConstants.MOD_ID).resolve("virtual").normalize();
        this.seed = seed;
    }

    /**
     * Whether the region under a detailed tile is being generated right now. A tile started now would only wait for it, holding a
     * thread that could make another region meanwhile.
     */
    boolean busy(Identifier dimension, com.Evga314.farplanetwocontinuation.tile.TilePos pos) {
        if (this.generating.isEmpty()) {
            return false;
        }
        int chunkX = (int) Math.floorDiv(pos.minX(), 16L);
        int chunkZ = (int) Math.floorDiv(pos.minZ(), 16L);
        return this.generating.contains(new Key(dimension, Math.floorDiv(chunkX, REGION), Math.floorDiv(chunkZ, REGION)));
    }

    /** Whether virtual chunks can be made in a level at all. */
    boolean supports(ServerLevel level) {
        return this.terrain(level) != null;
    }

    /**
     * Summaries of chunks nobody has generated yet. A few chunks at the edge of explored land are made on their own; where more are
     * missing, the whole region is made at once, see {@link #WHOLE_REGION}.
     */
    Long2ObjectMap<ChunkSummary> chunks(ServerLevel level, LongCollection wanted, ChunkSummary.Scan scan) {
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
            ChunkSummary[] region = this.region(level, terrain, entry.getKey(), entry.getValue(), scan);
            for (long chunk : entry.getValue()) {
                ChunkSummary summary = region[index(chunk)];
                if (summary != null) {
                    result.put(chunk, summary);
                }
            }
        }
        return result;
    }

    /**
     * Summaries of chunks made before, kept in memory or on disk; nothing is generated. For the ring around a detailed tile, which only
     * uses what is there already. Regions being made right now are skipped: they are written again in a moment.
     */
    Long2ObjectMap<ChunkSummary> stored(ServerLevel level, LongCollection wanted, ChunkSummary.Scan scan) {
        Long2ObjectOpenHashMap<ChunkSummary> result = new Long2ObjectOpenHashMap<>();
        if (wanted.isEmpty() || this.terrain(level) == null) {
            return result;
        }
        Map<Key, LongArrayList> byRegion = new HashMap<>();
        for (long chunk : wanted) {
            Key key = new Key(level.dimension().identifier(), Math.floorDiv(ChunkPos.getX(chunk), REGION), Math.floorDiv(ChunkPos.getZ(chunk), REGION));
            byRegion.computeIfAbsent(key, k -> new LongArrayList()).add(chunk);
        }
        for (Map.Entry<Key, LongArrayList> entry : byRegion.entrySet()) {
            Key key = entry.getKey();
            if (this.generating.contains(key)) {
                continue;
            }
            ChunkSummary[] region;
            synchronized (this) {
                region = this.memory.get(key);
            }
            if (region == null || !scannedWith(region, scan)) {
                region = this.read(level, key, scan);
                if (region == null) {
                    continue;
                }
                ChunkSummary[] read = region;
                synchronized (this) {
                    // A region made meanwhile holds more chunks than the file read before; it stays.
                    this.memory.compute(key, (k, old) -> old != null && scannedWith(old, scan) ? old : read);
                }
            }
            for (long chunk : entry.getValue()) {
                ChunkSummary summary = region[index(chunk)];
                if (summary != null) {
                    result.put(chunk, summary);
                }
            }
        }
        return result;
    }

    /** Whether a virtual chunk was made for a place, as far as memory knows; for the performance log only. */
    boolean madeInMemory(Identifier dimension, long chunk, ChunkSummary.Scan scan) {
        Key key = new Key(dimension, Math.floorDiv(ChunkPos.getX(chunk), REGION), Math.floorDiv(ChunkPos.getZ(chunk), REGION));
        ChunkSummary[] region;
        synchronized (this) {
            region = this.memory.get(key);
        }
        return region != null && scannedWith(region, scan) && region[index(chunk)] != null;
    }

    private static int index(long chunk) {
        return Math.floorMod(ChunkPos.getX(chunk), REGION) + Math.floorMod(ChunkPos.getZ(chunk), REGION) * REGION;
    }

    /**
     * One region with at least the wanted chunks made. Threads asking for the same region take turns, so nothing is made twice. A
     * region scanned to another depth is made again.
     */
    private ChunkSummary[] region(ServerLevel level, VirtualTerrain terrain, Key key, LongArrayList wanted, ChunkSummary.Scan scan) {
        synchronized (this.locks[(key.hashCode() & Integer.MAX_VALUE) % this.locks.length]) {
            ChunkSummary[] region;
            synchronized (this) {
                region = this.memory.get(key);
            }
            if (region != null && !scannedWith(region, scan)) {
                region = null;
            }
            if (region == null) {
                region = this.read(level, key, scan);
                if (region == null) {
                    region = new ChunkSummary[REGION * REGION];
                }
            }
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int missing = 0;
            for (long chunk : wanted) {
                if (region[index(chunk)] == null) {
                    minX = Math.min(minX, ChunkPos.getX(chunk));
                    minZ = Math.min(minZ, ChunkPos.getZ(chunk));
                    maxX = Math.max(maxX, ChunkPos.getX(chunk));
                    maxZ = Math.max(maxZ, ChunkPos.getZ(chunk));
                    missing++;
                }
            }
            if (missing >= WHOLE_REGION) {
                minX = key.x * REGION;
                minZ = key.z * REGION;
                maxX = minX + REGION - 1;
                maxZ = minZ + REGION - 1;
            }
            if (minX <= maxX) {
                waitForServer(level);
                long start = ServerPerf.ON ? System.nanoTime() : 0L;
                ProtoChunk[] made;
                this.generating.add(key);
                try {
                    made = terrain.generate(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
                } finally {
                    this.generating.remove(key);
                }
                if (ServerPerf.ON) {
                    ServerPerf.VIRTUAL_REGIONS.add(System.nanoTime() - start);
                    ServerPerf.VIRTUAL_CHUNKS.addAndGet(made.length);
                    ServerPerf.VIRTUAL_CHUNKS_TOTAL.addAndGet(made.length);
                }
                region = region.clone();
                int width = maxX - minX + 1;
                for (int i = 0; i < made.length; i++) {
                    ProtoChunk chunk = made[i];
                    region[index(ChunkPos.pack(minX + i % width, minZ + i / width))] =
                        ChunkSummary.saved(chunk.getSections(), chunk.getMinSectionY(), null, null, scan, level.getMinY(), level.getMaxY());
                }
                this.write(level, key, region, scan);
            }
            synchronized (this) {
                this.memory.put(key, region);
            }
            return region;
        }
    }

    /** Whether the summaries of a region were made with a scan; all of them always are made with the same one. */
    private static boolean scannedWith(ChunkSummary[] region, ChunkSummary.Scan scan) {
        for (ChunkSummary summary : region) {
            if (summary != null) {
                return summary.scan().equals(scan);
            }
        }
        return true;
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

    /** Forgets everything kept in memory; the files stay. */
    synchronized void clear() {
        this.memory.clear();
    }

    /** Forgets the generated chunks of one dimension, in memory and on disk. Deleting the files can take a while. */
    void clear(Identifier dimension) {
        synchronized (this) {
            this.memory.keySet().removeIf(key -> key.dimension.equals(dimension));
        }
        TileDiskCache.deleteTree(this.root.resolve(sanitize(dimension.getNamespace() + "_" + dimension.getPath())).normalize(), this.root);
    }

    /** Whether chunks of a region were generated before and are kept on disk, so they are only read, not generated. */
    boolean hasStored(ServerLevel level, int regionX, int regionZ) {
        Path file = this.file(new Key(level.dimension().identifier(), regionX, regionZ));
        return file != null && Files.isRegularFile(file);
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

    /** A region kept on disk; {@code null} when there is none, it is broken, or it was scanned to another depth. */
    private ChunkSummary @Nullable [] read(ServerLevel level, Key key, ChunkSummary.Scan scan) {
        Path file = this.file(key);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return null;
            }
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)));
            if (header.readInt() != MAGIC || header.readInt() != FORMAT || header.readLong() != this.seed || header.readShort() != scan.depth()) {
                return null;
            }
            try (InputStream body = Zip.decompress(header)) {
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
                int blockCount = in.readUnsignedShort();
                int[] blocks = new int[blockCount];
                for (int i = 0; i < blockCount; i++) {
                    byte[] text = new byte[in.readUnsignedShort()];
                    in.readFully(text);
                    Identifier id = Identifier.tryParse(new String(text, StandardCharsets.UTF_8));
                    // A block that no longer exists (a removed mod) makes the whole region stale.
                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                        return null;
                    }
                    int raw = BuiltInRegistries.BLOCK.getId(BuiltInRegistries.BLOCK.getValue(id));
                    // Whether it stands in water, see BlockLook#segment.
                    blocks[i] = in.readBoolean() && raw < BlockLook.WET ? raw | BlockLook.WET : raw;
                }
                long[] present = new long[REGION * REGION / 64];
                for (int i = 0; i < present.length; i++) {
                    present[i] = in.readLong();
                }
                ChunkSummary[] summaries = new ChunkSummary[REGION * REGION];
                for (int i = 0; i < summaries.length; i++) {
                    if ((present[i >> 6] & 1L << (i & 63)) != 0) {
                        summaries[i] = ChunkSummary.read(in, palette, blocks, scan, level.getMinY(), level.getMaxY());
                    }
                }
                return summaries;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Ignoring broken virtual region {}", file, e);
            return null;
        }
    }

    private void write(ServerLevel level, Key key, ChunkSummary[] summaries, ChunkSummary.Scan scan) {
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
            Int2IntOpenHashMap blockPalette = new Int2IntOpenHashMap();
            IntArrayList blocks = new IntArrayList();
            for (ChunkSummary summary : summaries) {
                if (summary != null) {
                    summary.collectBlocks(block -> {
                        if (!blockPalette.containsKey(block)) {
                            blockPalette.put(block, blocks.size());
                            blocks.add(block);
                        }
                    });
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
            DataOutputStream header = new DataOutputStream(bytes);
            header.writeInt(MAGIC);
            header.writeInt(FORMAT);
            header.writeLong(this.seed);
            header.writeShort(scan.depth());
            header.flush();
            try (DataOutputStream out = new DataOutputStream(Zip.compress(bytes))) {
                out.writeShort(ordered.size());
                for (Holder<Biome> biome : ordered) {
                    byte[] text = biome.unwrapKey().orElseThrow().identifier().toString().getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                }
                out.writeShort(blocks.size());
                for (int block : blocks) {
                    byte[] text = BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(BlockLook.segmentBlock(block))).toString()
                        .getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                    out.writeBoolean(BlockLook.segmentWet(block));
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
                        summary.write(out, palette, blockPalette);
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
