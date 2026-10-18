package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.config.ServerConfig;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.LoadOrder;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Accepts tile requests from players and generates tiles on a small pool of low priority background threads.
 * All bookkeeping happens on the server thread; workers only generate and hand the result back.
 */
public final class TileServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DETAIL_CACHE_MS = 60_000L;
    /** Tiles with chunks that are not generated yet change as soon as someone explores there, so they are kept only briefly. */
    private static final long INCOMPLETE_DETAIL_CACHE_MS = 5_000L;
    private static final int DETAIL_CACHE_TILES = 512;
    private static final int DIRTY_FLUSH_TICKS = 10;
    /** Added to the score of rough tiles that only refine terrain already drawn, so every hole goes first. */
    private static final double REFINE_PENALTY = 1.0E6;
    /** Horizontal speed in blocks per tick from which tiles load in the direction of movement. */
    private static final double FAST_MOTION = 0.4;

    private final MinecraftServer server;
    private final ServerConfig config;
    private final ThreadPoolExecutor executor;
    /**
     * Detailed tiles get their own threads. They mostly wait on region files, and sharing threads with the rough generator would put
     * the tiles right around the player behind hundreds of distant rough tiles.
     */
    private final ThreadPoolExecutor detailExecutor;
    private final ChunkSummaryCache chunkSummaries = new ChunkSummaryCache();
    private final VirtualChunkStore virtualChunks;
    private final @Nullable TileDiskCache diskCache;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<CacheKey, byte[]> memoryCache;
    private final AtomicBoolean clearing = new AtomicBoolean();
    private int ticks;
    private @Nullable Pregen pregen;
    private final Map<CacheKey, DetailEntry> detailCache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, DetailEntry> eldest) {
            return this.size() > DETAIL_CACHE_TILES;
        }
    };

    public TileServer(MinecraftServer server, ServerConfig config) {
        this.server = server;
        this.config = config;
        int threads = config.resolvedThreads();
        // Detailed tiles are what the player sees up close, so they get the CPU before distant terrain; both stay below the game.
        this.executor = createExecutor("FarPlane tile generator #", threads, Thread.MIN_PRIORITY);
        this.detailExecutor = createExecutor("FarPlane detail reader #", config.resolvedDetailThreads(), Thread.NORM_PRIORITY - 1);
        this.diskCache = config.diskCache ? new TileDiskCache(server.getWorldPath(LevelResource.ROOT), server.overworld().getSeed()) : null;
        this.virtualChunks = new VirtualChunkStore(server.getWorldPath(LevelResource.ROOT), server.overworld().getSeed());
        int memoryLimit = config.memoryCacheTiles;
        this.memoryCache = new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, byte[]> eldest) {
                return this.size() > memoryLimit;
            }
        };
        LOGGER.info("FarPlane tile server started with {} generator and {} detail thread(s)", threads, config.resolvedDetailThreads());
        if (ServerPerf.ON) {
            LOGGER.info("[FarPlane settings] server: {}", com.Evga314.farplanetwocontinuation.config.ConfigFiles.toCompactJson(config));
        }
    }

    private static ThreadPoolExecutor createExecutor(String name, int threads, int priority) {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, name + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught error in {}", t.getName(), e));
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public void sendHello(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, FarPlanePayloads.Hello.TYPE)) {
            ServerPlayNetworking.send(player, new FarPlanePayloads.Hello(
                FarPlaneConstants.PROTOCOL_VERSION, this.config.enabled, this.config.maxDistance, this.config.minLevel,
                this.config.detailEnabled, this.config.detailMaxRadiusChunks
            ));
        }
    }

    /**
     * Forgets every cached tile of the dimension the player is in, both in memory and on disk; other dimensions keep theirs. Deleting
     * files can take a while, so it runs on its own thread and the player is answered once it is done, which keeps the client from
     * loading stale tiles in the meantime.
     */
    public void onClearCache(ServerPlayer player) {
        boolean allowed = this.server.isSingleplayerOwner(player.nameAndId()) || player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
        if (!allowed) {
            ServerPlayNetworking.send(player, new FarPlanePayloads.CacheCleared(false));
            return;
        }
        UUID playerId = player.getUUID();
        Identifier dimension = player.level().dimension().identifier();
        if (!this.clearing.compareAndSet(false, true)) {
            return;
        }
        this.forget(dimension);
        Thread cleaner = new Thread(() -> {
            try {
                if (this.diskCache != null) {
                    this.diskCache.clear(dimension);
                }
                this.virtualChunks.clear(dimension);
            } finally {
                this.server.execute(() -> {
                    // Tiles finished while the files were being deleted came from the old cache.
                    this.forget(dimension);
                    this.clearing.set(false);
                    ServerPlayer target = this.server.getPlayerList().getPlayer(playerId);
                    if (target != null && ServerPlayNetworking.canSend(target, FarPlanePayloads.CacheCleared.TYPE)) {
                        ServerPlayNetworking.send(target, new FarPlanePayloads.CacheCleared(true));
                    }
                });
            }
        }, "FarPlane cache cleaner");
        cleaner.setDaemon(true);
        cleaner.setPriority(Thread.MIN_PRIORITY);
        cleaner.start();
        LOGGER.info("{} cleared the FarPlane LOD tile cache of {}", player.getName().getString(), dimension);
    }

    private void forget(Identifier dimension) {
        synchronized (this.memoryCache) {
            this.memoryCache.keySet().removeIf(key -> key.dimension().equals(dimension));
        }
        synchronized (this.detailCache) {
            this.detailCache.keySet().removeIf(key -> key.dimension().equals(dimension));
        }
        this.chunkSummaries.clear(dimension);
    }

    /**
     * Called every server tick. Chunks where blocks changed are collected by {@link DirtyChunks}; twice a second their cached
     * summaries and detailed tiles are dropped and nearby players are told, so they load those tiles again once vanilla stops
     * drawing that place. Nothing is stored for this: the changed blocks are read from the world itself.
     */
    public void tick() {
        this.pumpPregen();
        if (ServerPerf.ON && this.ticks % 100 == 0) {
            int rough = 0;
            int detail = 0;
            for (PlayerState state : this.players.values()) {
                rough += state.roughQueue.size() + state.roughInFlight;
                detail += state.detailQueue.size() + state.detailInFlight;
            }
            ServerPerf.summary(this.server.getAverageTickTimeNanos() / 1e6, rough, detail);
        }
        if (++this.ticks % DIRTY_FLUSH_TICKS != 0) {
            return;
        }
        Map<ResourceKey<Level>, long[]> dirty = DirtyChunks.drain();
        if (dirty.isEmpty()) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, long[]> entry : dirty.entrySet()) {
            Identifier dimension = entry.getKey().identifier();
            long[] chunks = entry.getValue();
            synchronized (this.detailCache) {
                for (long chunk : chunks) {
                    int chunkX = ChunkPos.getX(chunk);
                    int chunkZ = ChunkPos.getZ(chunk);
                    for (int level = FarPlaneConstants.DETAIL_MIN_LEVEL; level <= FarPlaneConstants.DETAIL_MAX_LEVEL; level++) {
                        long size = FarPlaneConstants.tileSize(level);
                        TilePos tile = new TilePos(level, (int) Math.floorDiv(chunkX * 16L, size), (int) Math.floorDiv(chunkZ * 16L, size));
                        this.detailCache.remove(new CacheKey(dimension, tile.pack()));
                    }
                }
            }
            for (long chunk : chunks) {
                this.chunkSummaries.remove(dimension, chunk);
                RegionFiles.markReal(entry.getKey(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            }
            double radius = this.config.detailMaxRadiusChunks * 16.0 + 64.0;
            for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
                if (player.level().dimension() != entry.getKey() || !ServerPlayNetworking.canSend(player, FarPlanePayloads.DetailDirty.TYPE)) {
                    continue;
                }
                long[] near = Arrays.stream(chunks).filter(chunk -> {
                    double dx = ChunkPos.getX(chunk) * 16.0 + 8.0 - player.getX();
                    double dz = ChunkPos.getZ(chunk) * 16.0 + 8.0 - player.getZ();
                    return dx * dx + dz * dz <= radius * radius;
                }).toArray();
                for (int start = 0; start < near.length; start += FarPlanePayloads.MAX_DIRTY_CHUNKS) {
                    long[] part = Arrays.copyOfRange(near, start, Math.min(near.length, start + FarPlanePayloads.MAX_DIRTY_CHUNKS));
                    ServerPlayNetworking.send(player, new FarPlanePayloads.DetailDirty(dimension, part));
                }
            }
        }
    }

    /**
     * Starts generating the detailed terrain of a circle of chunks ahead of time. It is done one coarsest detailed tile (16 x 16
     * chunks) at a time from the centre outwards, through the same queue as the tiles players ask for, so the game keeps running
     * smoothly; chunks that already exist or were made before are skipped.
     */
    boolean startPregen(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius) {
        if (!this.config.detailEnabled || !this.config.detailGenerateUnexplored || !this.virtualChunks.supports(level)) {
            source.sendFailure(FarPlaneCommand.message("disabled",
                "Detailed terrain for unexplored places is turned off in the server config or not possible in this dimension"));
            return false;
        }
        if (this.pregen != null) {
            source.sendFailure(FarPlaneCommand.message("busy",
                "Detailed terrain is already being generated (%s%%). Stop it first with /farplane pregen stop", this.pregen.percent()));
            return false;
        }
        int size = FarPlaneConstants.tileSize(FarPlaneConstants.DETAIL_MAX_LEVEL) / 16;
        List<TilePos> tiles = new ArrayList<>();
        int minTileX = Math.floorDiv(centerX - radius, size);
        int maxTileX = Math.floorDiv(centerX + radius, size);
        int minTileZ = Math.floorDiv(centerZ - radius, size);
        int maxTileZ = Math.floorDiv(centerZ + radius, size);
        double centerBlockX = centerX * 16.0 + 8.0;
        double centerBlockZ = centerZ * 16.0 + 8.0;
        for (int z = minTileZ; z <= maxTileZ; z++) {
            for (int x = minTileX; x <= maxTileX; x++) {
                TilePos tile = new TilePos(FarPlaneConstants.DETAIL_MAX_LEVEL, x, z);
                if (tile.isInsideWorld() && tile.distanceTo(centerBlockX, centerBlockZ) <= radius * 16.0) {
                    tiles.add(tile);
                }
            }
        }
        tiles.sort(Comparator.comparingDouble(tile -> tile.distanceTo(centerBlockX, centerBlockZ)));
        // An area is one region of generated chunks. Where they were generated before or the world has real chunks, it is only read.
        boolean[] ready = new boolean[tiles.size()];
        int half = size / 2;
        for (int i = 0; i < tiles.size(); i++) {
            TilePos tile = tiles.get(i);
            ready[i] = this.virtualChunks.hasStored(level, tile.x(), tile.z())
                || RegionFiles.mayContain(level, tile.x() * size + half, tile.z() * size + half);
        }
        this.pregen = new Pregen(source, level, tiles, ready, Util.getMillis());
        this.pregen.showProgressBar(this.server);
        source.sendSuccess(() -> FarPlaneCommand.message("started",
            "Generating detailed terrain within %s chunks around chunk %s %s: %s areas", radius, centerX, centerZ, tiles.size()), true);
        return true;
    }

    void stopPregen(CommandSourceStack source) {
        Pregen running = this.pregen;
        if (running == null) {
            source.sendFailure(FarPlaneCommand.message("none", "No detailed terrain is being generated"));
            return;
        }
        this.pregen = null;
        source.sendSuccess(() -> FarPlaneCommand.message("stopped", "Detailed terrain generation stopped at %s%%", running.percent()), true);
    }

    void pregenStatus(CommandSourceStack source) {
        Pregen running = this.pregen;
        if (running == null) {
            source.sendSuccess(() -> FarPlaneCommand.message("none", "No detailed terrain is being generated"), false);
            return;
        }
        source.sendSuccess(() -> running.progressMessage(), false);
    }

    private void pumpPregen() {
        Pregen running = this.pregen;
        if (running == null) {
            return;
        }
        if (this.ticks % 20 == 0) {
            running.showProgressBar(this.server);
        }
        if (running.busy) {
            return;
        }
        if (running.next >= running.tiles.size()) {
            this.pregen = null;
            running.showProgressBar(this.server);
            long seconds = (Util.getMillis() - running.started) / 1000L;
            running.tell(FarPlaneCommand.message("done", "Detailed terrain generated: %s areas in %s min %s s",
                running.tiles.size(), seconds / 60L, seconds % 60L));
            return;
        }
        TilePos tile = running.tiles.get(running.next++);
        Long2ObjectMap<LevelChunk> loaded = this.loadedChunks(running.level, tile);
        running.busy = true;
        try {
            this.detailExecutor.execute(() -> {
                try {
                    this.produceDetail(running.level, tile, loaded, this.virtualChunks);
                } catch (Exception e) {
                    LOGGER.warn("Failed to pre-generate detailed tile {}", tile, e);
                }
                this.server.execute(() -> {
                    running.busy = false;
                    running.finishedOne();
                    if (this.pregen == running && running.shouldReport()) {
                        running.tell(running.progressMessage());
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            this.pregen = null;
        }
    }

    public void onDisconnect(UUID player) {
        this.players.remove(player);
    }

    public void onRequest(ServerPlayer player, int[] requests) {
        this.accept(player, requests, false);
    }

    public void onDetailRequest(ServerPlayer player, int[] requests, boolean generate, float urgency) {
        PlayerState state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
        state.generateVirtual = generate;
        state.urgency = urgency;
        this.accept(player, requests, true);
    }

    private void accept(ServerPlayer player, int[] requests, boolean detail) {
        if (!this.config.enabled || detail && !this.config.detailEnabled || this.executor.isShutdown() || this.detailExecutor.isShutdown()) {
            return;
        }
        ServerLevel level = player.level();
        if (!TileGenerator.supports(level)) {
            return;
        }
        PlayerState state = this.players.computeIfAbsent(player.getUUID(), uuid -> new PlayerState());
        if (state.dimension != level.dimension()) {
            state.dimension = level.dimension();
            state.roughQueue.clear();
            state.detailQueue.clear();
            state.queued.clear();
        }
        int minLevel = detail ? FarPlaneConstants.DETAIL_MIN_LEVEL : this.config.minLevel;
        int maxLevel = detail ? FarPlaneConstants.DETAIL_MAX_LEVEL : FarPlaneConstants.MAX_LEVEL;
        double detailRadius = this.config.detailMaxRadiusChunks * 16.0;
        boolean wholeWorld = this.config.maxDistance >= FarPlaneConstants.MAX_LOD_DISTANCE;
        for (int i = 0; i + 2 < requests.length; i += 3) {
            if (state.queue(detail).size() >= this.config.maxQueuedRequestsPerPlayer) {
                break;
            }
            boolean hole = (requests[i] & FarPlanePayloads.REQUEST_HOLE) != 0;
            int tileLevel = requests[i] & ~FarPlanePayloads.REQUEST_HOLE;
            if (tileLevel < minLevel || tileLevel > maxLevel) {
                continue;
            }
            TilePos pos = new TilePos(tileLevel, requests[i + 1], requests[i + 2]);
            if (!pos.isInsideWorld()) {
                continue;
            }
            double distance = pos.distanceTo(player.getX(), player.getZ());
            if (detail ? distance > detailRadius : !wholeWorld && distance > this.config.maxDistance) {
                continue;
            }
            Job job = new Job(pos, detail, hole && !detail);
            if (state.queued.add(job.key())) {
                state.queue(detail).add(job);
            }
        }
        this.pump(player, state);
    }

    /**
     * Rough and detailed tiles have separate queues and limits. Detailed tiles can be slow because they read chunks from disk or
     * generate them, and without the split they would hold back the cheap rough tiles that fill the view first. Both share the CPU in
     * this order: rough tiles that fill a hole, detailed tiles, rough tiles that only refine distant terrain. While any player waits
     * for detailed tiles, refining goes on with a single tile at a time.
     */
    private void pump(ServerPlayer player, PlayerState state) {
        boolean detailBusy = false;
        for (PlayerState other : this.players.values()) {
            detailBusy |= !other.detailQueue.isEmpty() || other.detailInFlight > 0;
        }
        this.pump(player, state, false, this.config.maxInFlightPerPlayer, detailBusy);
        // As many detailed tiles at once as loading is urgent for this player: one or a few while walking, all threads while flying.
        int calm = this.config.resolvedCalmDetailThreads();
        int all = this.config.resolvedDetailThreads();
        this.pump(player, state, true, calm + Math.round((all - calm) * state.urgency), false);
    }

    private void pump(ServerPlayer player, PlayerState state, boolean detail, int limit, boolean refineSlowly) {
        UUID playerId = player.getUUID();
        List<Job> queue = state.queue(detail);
        double detailRadius = this.config.detailMaxRadiusChunks * 16.0 + 64.0;
        while (state.inFlight(detail) < limit && !queue.isEmpty()) {
            Identifier dimensionId = state.dimension == null ? null : state.dimension.identifier();
            int best = mostImportant(queue, player, detail,
                detail && dimensionId != null ? pos -> this.virtualChunks.busy(dimensionId, pos) : pos -> false);
            if (best < 0) {
                // Every waiting tile needs a region that is being made right now; the next one starts when it is done.
                return;
            }
            if (refineSlowly && !queue.get(best).hole() && state.inFlight(detail) > 0) {
                return;
            }
            Job job = queue.get(best);
            queue.set(best, queue.get(queue.size() - 1));
            queue.remove(queue.size() - 1);
            state.queued.remove(job.key());
            // The player moves on while tiles wait; detailed tiles left far behind are not worth reading any more.
            if (detail && job.pos().distanceTo(player.getX(), player.getZ()) > detailRadius) {
                continue;
            }
            ResourceKey<Level> dimension = state.dimension;
            ServerLevel level = dimension == null ? null : this.server.getLevel(dimension);
            if (level == null) {
                continue;
            }
            // Loaded chunks are looked up here on the server thread; the worker only reads blocks from them.
            Long2ObjectMap<LevelChunk> loaded = job.detail() ? this.loadedChunks(level, job.pos()) : null;
            VirtualChunkStore virtual = job.detail() && state.generateVirtual && this.config.detailGenerateUnexplored ? this.virtualChunks : null;
            state.changeInFlight(detail, 1);
            try {
                (job.detail() ? this.detailExecutor : this.executor).execute(() -> {
                    byte[] result = null;
                    try {
                        long start = ServerPerf.ON ? System.nanoTime() : 0L;
                        result = job.detail() ? this.produceDetail(level, job.pos(), loaded, virtual) : this.produce(level, job.pos());
                        if (ServerPerf.ON) {
                            (job.detail() ? ServerPerf.DETAIL : ServerPerf.ROUGH).add(System.nanoTime() - start);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to generate tile {} in {}", job.pos(), dimension.identifier(), e);
                    }
                    byte[] finalResult = result;
                    this.server.execute(() -> this.complete(playerId, dimension, job, finalResult));
                });
            } catch (RejectedExecutionException e) {
                state.changeInFlight(detail, -1);
                return;
            }
        }
    }

    /**
     * Finds the job that matters most where the player is now and looks now, not where they were when they asked, in the same order
     * the client asks in: rough tiles that fill a hole first, then big and close ones; detailed tiles around the player first and then
     * in view. Tiles that would only wait for a region another thread is making are left for later. Queues are short, so a plain scan
     * is cheap. Returns -1 when every tile has to wait.
     */
    private static int mostImportant(List<Job> queue, ServerPlayer player, boolean detail, java.util.function.Predicate<TilePos> blocked) {
        double x = player.getX();
        double z = player.getZ();
        // Flying fast, the way the player moves matters more than where they look.
        Vec3 motion = player.getDeltaMovement();
        Vec3 look = motion.x * motion.x + motion.z * motion.z > FAST_MOTION * FAST_MOTION ? motion : player.getLookAngle();
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double lookX = horizontal > 0.05 ? look.x / horizontal : 0.0;
        double lookZ = horizontal > 0.05 ? look.z / horizontal : 0.0;
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < queue.size(); i++) {
            Job job = queue.get(i);
            TilePos pos = job.pos();
            if (blocked.test(pos)) {
                continue;
            }
            double score = detail ? LoadOrder.score(pos, x, z, lookX, lookZ) : pos.distanceTo(x, z) / pos.size() + (job.hole() ? 0.0 : REFINE_PENALTY);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * A chunk that is in the server's memory. After players leave, a chunk stops being fully loaded but stays in memory, unsaved,
     * for a while; its region file still holds the old blocks then, so what players just built would be missed if it was read from
     * disk.
     */
    private static @Nullable LevelChunk inMemory(ServerLevel level, int chunkX, int chunkZ, long key) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk != null) {
            return chunk;
        }
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key);
        ChunkAccess latest = holder == null ? null : holder.getLatestChunk();
        if (latest instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        return latest instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : null;
    }

    private Long2ObjectMap<LevelChunk> loadedChunks(ServerLevel level, TilePos pos) {
        Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
        int minChunkX = (int) Math.floorDiv(pos.minX(), 16L);
        int minChunkZ = (int) Math.floorDiv(pos.minZ(), 16L);
        int count = Math.max(1, pos.size() / 16);
        for (int dz = 0; dz < count; dz++) {
            for (int dx = 0; dx < count; dx++) {
                long key = ChunkPos.pack(minChunkX + dx, minChunkZ + dz);
                LevelChunk chunk = inMemory(level, minChunkX + dx, minChunkZ + dz, key);
                if (chunk != null) {
                    chunks.put(key, chunk);
                }
            }
        }
        return chunks;
    }

    /**
     * Detailed tiles follow the real world, which players change, so they are only kept in memory for a short while and never
     * written to disk.
     */
    private byte[] produceDetail(ServerLevel level, TilePos pos, Long2ObjectMap<LevelChunk> loaded, @Nullable VirtualChunkStore virtual) {
        CacheKey key = new CacheKey(level.dimension().identifier(), pos.pack());
        long now = Util.getMillis();
        synchronized (this.detailCache) {
            DetailEntry cached = this.detailCache.get(key);
            if (cached != null && now - cached.created() < (cached.complete() ? DETAIL_CACHE_MS : INCOMPLETE_DETAIL_CACHE_MS)) {
                return cached.data();
            }
        }
        DetailData data = DetailGenerator.generate(level, pos, loaded, this.config.detailReadSavedChunks ? this.chunkSummaries : null, virtual);
        byte[] encoded = data.encode();
        synchronized (this.detailCache) {
            this.detailCache.put(key, new DetailEntry(encoded, now, data.isComplete()));
        }
        return encoded;
    }

    private byte[] produce(ServerLevel level, TilePos pos) {
        Identifier dimension = level.dimension().identifier();
        CacheKey key = new CacheKey(dimension, pos.pack());
        synchronized (this.memoryCache) {
            byte[] cached = this.memoryCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        byte[] encoded = null;
        if (this.diskCache != null) {
            encoded = this.diskCache.read(dimension, pos);
        }
        if (encoded == null) {
            encoded = TileGenerator.generate(level, pos).encode();
            if (this.diskCache != null) {
                this.diskCache.write(dimension, pos, encoded);
            }
        }
        synchronized (this.memoryCache) {
            this.memoryCache.put(key, encoded);
        }
        return encoded;
    }

    private void complete(UUID playerId, ResourceKey<Level> dimension, Job job, byte @Nullable [] data) {
        PlayerState state = this.players.get(playerId);
        if (state == null) {
            return;
        }
        state.changeInFlight(job.detail(), -1);
        ServerPlayer player = this.server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            this.players.remove(playerId);
            return;
        }
        TilePos pos = job.pos();
        if (data != null && player.level().dimension() == dimension) {
            if (job.detail()) {
                if (ServerPlayNetworking.canSend(player, FarPlanePayloads.DetailDataPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new FarPlanePayloads.DetailDataPayload(dimension.identifier(), pos.level(), pos.x(), pos.z(), data));
                }
            } else if (ServerPlayNetworking.canSend(player, FarPlanePayloads.TileDataPayload.TYPE)) {
                ServerPlayNetworking.send(player, new FarPlanePayloads.TileDataPayload(dimension.identifier(), pos.level(), pos.x(), pos.z(), data));
            }
        }
        this.pump(player, state);
    }

    public void close() {
        this.executor.shutdownNow();
        this.detailExecutor.shutdownNow();
        this.players.clear();
        this.chunkSummaries.clear();
        this.virtualChunks.clear();
        synchronized (this.memoryCache) {
            this.memoryCache.clear();
        }
        synchronized (this.detailCache) {
            this.detailCache.clear();
        }
    }

    /** A running pre-generation. Only touched on the server thread. */
    private static final class Pregen {
        private static final long REPORT_MS = 30_000L;
        /** How fast a new measurement of an area changes the average time of its kind. */
        private static final double AVERAGE_WEIGHT = 0.2;
        /** Until areas of a kind have been measured, new areas count as this many times slower than ready ones. */
        private static final double NEW_PER_READY = 15.0;
        /**
         * Below this share of the areas done the time left is not shown yet: the first areas say too little about the rest, and an
         * early guess would jump around. Running dots show that it is being worked out.
         */
        private static final int ESTIMATE_FROM_PERCENT = 15;
        /** How fast the shown time follows a lower estimate; a higher one is followed much slower, so the time keeps counting down. */
        private static final double FOLLOW_DOWN = 0.15;
        private static final double FOLLOW_UP = 0.02;

        private final CommandSourceStack source;
        private final ServerLevel level;
        private final List<TilePos> tiles;
        /** Areas only read from disk, a small part of the work of areas generated from scratch. */
        private final boolean[] ready;
        /** Number of new areas from each index to the end. */
        private final int[] newFrom;
        private final long started;
        /** The player who started it, shown the progress above the hotbar; null when started from the console. */
        private final @Nullable UUID player;
        private int next;
        private int done;
        private boolean busy;
        private int lastReportedTenth;
        private long lastReport;
        private long lastFinished;
        private double readySeconds = -1.0;
        private double newSeconds = -1.0;
        private double shownSeconds = -1.0;
        private long shownAt;

        Pregen(CommandSourceStack source, ServerLevel level, List<TilePos> tiles, boolean[] ready, long started) {
            this.source = source;
            this.level = level;
            this.tiles = tiles;
            this.ready = ready;
            this.newFrom = new int[tiles.size() + 1];
            for (int i = tiles.size() - 1; i >= 0; i--) {
                this.newFrom[i] = this.newFrom[i + 1] + (ready[i] ? 0 : 1);
            }
            this.started = started;
            this.lastFinished = started;
            this.lastReport = started;
            ServerPlayer player = source.getPlayer();
            this.player = player == null ? null : player.getUUID();
        }

        /** Areas are done one after another, so the time since the last one is what this one took. */
        void finishedOne() {
            long now = Util.getMillis();
            double seconds = (now - this.lastFinished) / 1000.0;
            this.lastFinished = now;
            if (this.done < this.ready.length) {
                if (this.ready[this.done]) {
                    this.readySeconds = this.readySeconds < 0.0 ? seconds : this.readySeconds + (seconds - this.readySeconds) * AVERAGE_WEIGHT;
                } else {
                    this.newSeconds = this.newSeconds < 0.0 ? seconds : this.newSeconds + (seconds - this.newSeconds) * AVERAGE_WEIGHT;
                }
            }
            this.done++;
        }

        /** A short progress line at the bottom of the screen of the player who started it, refreshed every second. */
        void showProgressBar(MinecraftServer server) {
            this.updateEstimate();
            ServerPlayer target = this.player == null ? null : server.getPlayerList().getPlayer(this.player);
            if (target == null) {
                return;
            }
            long left = this.secondsLeft();
            if (left < 0L || this.percent() < ESTIMATE_FROM_PERCENT) {
                String dots = ".".repeat((int) (Util.getMillis() / 1000L % 3L) + 1);
                target.sendOverlayMessage(FarPlaneCommand.message("bar_estimating", "Detailed terrain: %s%% (%s of %s), estimating the time left%s",
                    this.percent(), this.done, this.tiles.size(), dots));
                return;
            }
            String time = String.format(Locale.ROOT, "%d:%02d", left / 60L, left % 60L);
            target.sendOverlayMessage(FarPlaneCommand.message("bar", "Detailed terrain: %s%% (%s of %s), %s minutes left",
                this.percent(), this.done, this.tiles.size(), time));
        }

        /** Seconds left, or -1 while there is no estimate yet. */
        private long secondsLeft() {
            if (this.done >= this.tiles.size()) {
                return 0L;
            }
            return this.shownSeconds < 0.0 ? -1L : Math.round(this.shownSeconds);
        }

        /**
         * Areas near the center are often ready already, read in a fraction of the time new land takes to generate, so measuring the
         * speed of the first areas would promise far too little time and then keep adding to it. Both kinds are timed separately and
         * the areas left of each kind are counted ahead. The shown value counts down every second and follows the estimate smoothly:
         * quickly down, slowly up.
         */
        private void updateEstimate() {
            int total = this.tiles.size();
            if (this.done >= total || this.readySeconds < 0.0 && this.newSeconds < 0.0) {
                return;
            }
            double newSeconds = this.newSeconds >= 0.0 ? this.newSeconds : this.readySeconds * NEW_PER_READY;
            double readySeconds = this.readySeconds >= 0.0 ? this.readySeconds : this.newSeconds / NEW_PER_READY;
            int newLeft = this.newFrom[this.done];
            int readyLeft = total - this.done - newLeft;
            double estimate = newLeft * newSeconds + readyLeft * readySeconds;
            // The area being worked on has been running for a while already.
            double running = (Util.getMillis() - this.lastFinished) / 1000.0;
            double current = this.ready[this.done] ? readySeconds : newSeconds;
            estimate += Math.max(0.0, running - current);
            long now = Util.getMillis();
            if (this.shownSeconds < 0.0) {
                this.shownSeconds = estimate;
            } else {
                this.shownSeconds = Math.max(0.0, this.shownSeconds - (now - this.shownAt) / 1000.0);
                double follow = estimate < this.shownSeconds ? FOLLOW_DOWN : FOLLOW_UP;
                this.shownSeconds += (estimate - this.shownSeconds) * follow;
            }
            this.shownAt = now;
        }

        int percent() {
            return this.tiles.isEmpty() ? 100 : this.done * 100 / this.tiles.size();
        }

        /** Every 10% and at least every half minute. */
        boolean shouldReport() {
            int tenth = this.percent() / 10;
            long now = Util.getMillis();
            if (tenth > this.lastReportedTenth || now - this.lastReport > REPORT_MS) {
                this.lastReportedTenth = tenth;
                this.lastReport = now;
                return this.done < this.tiles.size();
            }
            return false;
        }

        Component progressMessage() {
            long left = this.secondsLeft();
            if (left < 0L || this.percent() < ESTIMATE_FROM_PERCENT) {
                return FarPlaneCommand.message("progress_estimating", "Detailed terrain: %s%% (%s of %s areas), estimating the time left",
                    this.percent(), this.done, this.tiles.size());
            }
            return FarPlaneCommand.message("progress", "Detailed terrain: %s%% (%s of %s areas), about %s min %s s left",
                this.percent(), this.done, this.tiles.size(), left / 60L, left % 60L);
        }

        void tell(Component message) {
            try {
                this.source.sendSuccess(() -> message, false);
            } catch (RuntimeException e) {
                LOGGER.debug("Could not report pre-generation progress", e);
            }
        }
    }

    /** {@code hole}: a rough tile where nothing is drawn yet, see {@link FarPlanePayloads#REQUEST_HOLE}. */
    private record Job(TilePos pos, boolean detail, boolean hole) {
        long key() {
            return this.pos.pack() ^ (this.detail ? Long.MIN_VALUE : 0L);
        }
    }

    private record DetailEntry(byte[] data, long created, boolean complete) {
    }

    private static final class PlayerState {
        private @Nullable ResourceKey<Level> dimension;
        private final List<Job> roughQueue = new ArrayList<>();
        private final List<Job> detailQueue = new ArrayList<>();
        private final LongOpenHashSet queued = new LongOpenHashSet();
        private boolean generateVirtual;
        private int roughInFlight;
        private int detailInFlight;
        private float urgency;

        List<Job> queue(boolean detail) {
            return detail ? this.detailQueue : this.roughQueue;
        }

        int inFlight(boolean detail) {
            return detail ? this.detailInFlight : this.roughInFlight;
        }

        void changeInFlight(boolean detail, int delta) {
            if (detail) {
                this.detailInFlight = Math.max(0, this.detailInFlight + delta);
            } else {
                this.roughInFlight = Math.max(0, this.roughInFlight + delta);
            }
        }
    }

    private record CacheKey(Identifier dimension, long pos) {
    }
}
