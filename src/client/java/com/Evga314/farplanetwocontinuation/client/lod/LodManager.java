package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.client.LoadSpeed;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import com.Evga314.farplanetwocontinuation.dimension.DimensionKind;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Client side of the LOD system. Drives two layers every tick: detailed tiles built from real chunks in a ring around the player,
 * and rough terrain from the world generator everywhere else up to the edge of the world.
 */
public final class LodManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CLEAR_TIMEOUT_MS = 30_000L;
    private static final long UPLOAD_TIME_NS = 3_000_000L;
    /** Frames faster than this never count as late. */
    private static final long LATE_FRAME_MIN_NS = 12_000_000L;
    private long lastFrame;
    /** How far under the surface the camera has to be to count as underground. */
    private static final int UNDERGROUND_DEPTH = 4;
    private boolean enclosedUnderground;
    /** A step longer than this in one tick is a teleport, not movement. */
    private static final double MAX_STEP = 100.0;
    /** Blocks per second from which tiles load in the direction of movement. */
    private static final double FAST_SPEED = 8.0;
    private double lastCamX;
    private double lastCamZ;
    private double speed;
    private double moveX;
    private double moveZ;
    private double averageFrame = 16_000_000.0;

    private final Minecraft minecraft;
    private final ClientConfig config;
    private double detailRingRadius;
    private double canopyStart;
    /** Terrain occlusion culling runs on a thread of its own, see {@link #cullHidden}. */
    private final ExecutorService cullExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FarPlane occlusion culling");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private @Nullable CompletableFuture<Horizon.Result> cullJob;
    private Horizon.@Nullable Result cullResult;
    /** Camera position of the last tick, to know how fast it moves. */
    private double cullCamX;
    private double cullCamY;
    private double cullCamZ;
    private final ThreadPoolExecutor meshExecutor;
    private final LodView view;
    private final RoughLayer rough;
    private final DetailLayer detail;

    private FarPlanePayloads.@Nullable Hello hello;
    private @Nullable ResourceKey<Level> dimension;
    private long waitingForClearUntil;
    private long tick;
    private List<TileMesh> drawList = List.of();
    private int activeLodDistance;
    /** See {@link LodView#fullyDrawnRadius}; updated a few times per second, it changes slowly. */
    private double fullyDrawnRadius;
    private static final int FULLY_DRAWN_INTERVAL = 5;

    public LodManager(Minecraft minecraft, ClientConfig config) {
        this.minecraft = minecraft;
        this.config = config;
        int threads = config.meshThreads;
        AtomicInteger counter = new AtomicInteger();
        this.meshExecutor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "FarPlane mesh builder #" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught error in {}", t.getName(), e));
            return thread;
        });
        this.meshExecutor.allowCoreThreadTimeOut(true);
        this.view = new LodView(minecraft);
        this.rough = new RoughLayer(config, this.meshExecutor);
        this.detail = new DetailLayer(config, this.meshExecutor);
    }

    public ClientConfig config() {
        return this.config;
    }

    public boolean isActive() {
        ClientLevel level = this.minecraft.level;
        return this.config.enabled
            && this.hello != null
            && this.hello.enabled()
            && this.hello.protocol() == FarPlaneConstants.PROTOCOL_VERSION
            && level != null
            && DimensionKind.of(level).hasLod();
    }

    /** True when there is LOD terrain on screen, which is when vanilla's render distance fog has to move out of the way. */
    public boolean hasVisibleTerrain() {
        return this.isActive() && !this.drawList.isEmpty();
    }

    public int activeLodDistance() {
        return this.activeLodDistance;
    }

    /** Radius of the ring of detailed tiles around the player, 0 when there is none. */
    double detailRingRadius() {
        return this.detailRingRadius;
    }

    /** Where tree tops of the rough terrain start to rise: the inner edge of the fading band of the ring, or vanilla's edge. */
    double canopyStart() {
        return this.canopyStart;
    }

    /**
     * The camera is deep in the ground with no opening to the sky nearby (a cave or a mine). No LOD terrain can be seen from there, but
     * drawing it would still cost the GPU all of its pixels, because it is drawn before vanilla's blocks cover it.
     */
    public boolean enclosedUnderground() {
        return this.enclosedUnderground;
    }

    private void checkUnderground(ClientLevel level) {
        BlockPos camera = BlockPos.containing(this.view.camX, this.view.camY, this.view.camZ);
        this.enclosedUnderground = level.dimensionType().hasSkyLight()
            && camera.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, camera.getX(), camera.getZ()) - UNDERGROUND_DEPTH
            && level.getBrightness(LightLayer.SKY, camera) == 0;
    }

    public List<TileMesh> drawList() {
        return this.drawList;
    }

    public String debugStats() {
        Horizon.Result result = this.cullResult;
        return this.rough.stats() + " | " + this.detail.stats() + " | " + (result == null ? "hidden none" : result.stats());
    }

    public int meshCount() {
        return this.rough.meshCount() + this.detail.meshCount();
    }

    public int detailMeshCount() {
        return this.detail.meshCount();
    }

    public int pendingCount() {
        return this.rough.pendingCount() + this.detail.pendingCount();
    }
    public void onHello(FarPlanePayloads.Hello hello) {
        this.hello = hello;
        this.resetTiles();
        if (hello.protocol() != FarPlaneConstants.PROTOCOL_VERSION) {
            LOGGER.warn("Server uses FarPlane protocol {}, this client uses {}. LOD terrain is disabled.", hello.protocol(), FarPlaneConstants.PROTOCOL_VERSION);
        }
    }

    /**
     * Drops every tile on this client and asks the server to clear its cache too. Until the server answers no tiles are requested,
     * so nothing stale is loaded back while the cache files are being deleted.
     */
    public void reloadAll() {
        this.resetTiles();
        if (this.hello != null && ClientPlayNetworking.canSend(FarPlanePayloads.ClearCache.TYPE)) {
            ClientPlayNetworking.send(FarPlanePayloads.ClearCache.INSTANCE);
            this.waitingForClearUntil = Util.getMillis() + CLEAR_TIMEOUT_MS;
            this.message("reload.started");
        } else {
            this.message("reload.local");
        }
    }

    public void onCacheCleared(FarPlanePayloads.CacheCleared payload) {
        this.waitingForClearUntil = 0L;
        this.resetTiles();
        this.message(payload.cleared() ? "reload.done" : "reload.denied");
    }

    private void message(String key) {
        if (this.minecraft.player != null) {
            this.minecraft.player.sendOverlayMessage(Component.translatable(FarPlaneConstants.MOD_ID + "." + key));
        }
    }

    public void onDisconnect() {
        this.waitingForClearUntil = 0L;
        this.hello = null;
        this.dimension = null;
        this.resetTiles();
    }

    public void resetTiles() {
        this.rough.reset();
        this.detail.reset();
        this.drawList = List.of();
        this.cullResult = null;
    }

    public void onTileData(FarPlanePayloads.TileDataPayload payload) {
        this.onData(this.rough, payload.dimension(), payload.level(), payload.x(), payload.z(), payload.data());
    }

    public void onDetailData(FarPlanePayloads.DetailDataPayload payload) {
        if (payload.level() < FarPlaneConstants.DETAIL_MIN_LEVEL) {
            return;
        }
        this.onData(this.detail, payload.dimension(), payload.level(), payload.x(), payload.z(), payload.data());
    }

    /** Blocks changed in these chunks: their detailed tiles are loaded again the next time they are drawn. */
    public void onDetailDirty(FarPlanePayloads.DetailDirty payload) {
        ClientLevel clientLevel = this.minecraft.level;
        if (!this.isActive() || clientLevel == null || !payload.dimension().equals(clientLevel.dimension().identifier())) {
            return;
        }
        for (long chunk : payload.chunks()) {
            this.detail.markChanged(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
    }

    private void onData(LodLayer layer, Identifier dimension, int level, int x, int z, byte[] data) {
        ClientLevel clientLevel = this.minecraft.level;
        if (!this.isActive() || clientLevel == null || !dimension.equals(clientLevel.dimension().identifier())) {
            return;
        }
        layer.onData(level, x, z, data, clientLevel.registryAccess().lookupOrThrow(Registries.BIOME), clientLevel.getSeaLevel());
    }

    /** Called on the render thread right before the frame is drawn. Uploads a limited number of finished meshes per frame. */
    public void uploadBuiltMeshes() {
        this.watchFrame();
        if (PerfLog.ON) {
            PerfLog.frameStart();
        }
        long start = PerfLog.ON ? System.nanoTime() : 0L;
        // Uploading a mesh can take milliseconds on phones (OpenGL ES through a translation layer), so uploads also stop after a few
        // milliseconds of a frame instead of only after a number of meshes.
        // Flying fast, terrain coming in matters more than an even frame time.
        long deadline = System.nanoTime() + (long) (UPLOAD_TIME_NS * (1.0F + 2.0F * WorkThrottle.urgency()));
        int budget = this.detail.upload(this.config.maxUploadsPerFrame, deadline);
        if (budget > 0 && System.nanoTime() < deadline) {
            this.rough.upload(budget, deadline);
        }
        if (PerfLog.ON) {
            PerfLog.uploadTime(System.nanoTime() - start);
        }
    }

    /**
     * Frames running late (much longer than the frames before, or slower than the frame rate limit) make the mod's background work
     * yield for a moment, see {@link WorkThrottle}.
     */
    private void watchFrame() {
        long now = System.nanoTime();
        if (this.lastFrame != 0L) {
            long frame = now - this.lastFrame;
            int limit = this.minecraft.options.framerateLimit().get();
            double target = limit > 0 && limit < 260 ? 1.15e9 / limit : Double.MAX_VALUE;
            if (frame > LATE_FRAME_MIN_NS && (frame > this.averageFrame * 1.3 || frame > target)) {
                WorkThrottle.frameLate();
            }
            this.averageFrame = this.averageFrame * 0.95 + Math.min(frame, 100_000_000L) * 0.05;
        }
        this.lastFrame = now;
    }

    /**
     * The player's horizontal speed, smoothed over about half a second, sets how urgent loading is ({@link WorkThrottle}). Moving
     * fast, tiles load in the direction of movement instead of the direction the player looks.
     */
    private void trackSpeed() {
        double dx = this.view.camX - this.lastCamX;
        double dz = this.view.camZ - this.lastCamZ;
        this.lastCamX = this.view.camX;
        this.lastCamZ = this.view.camZ;
        double step = Math.sqrt(dx * dx + dz * dz);
        // Teleports are not movement.
        if (step < MAX_STEP) {
            this.speed = this.speed * 0.9 + step * 20.0 * 0.1;
            if (step > 0.4) {
                this.moveX = this.moveX * 0.8 + dx / step * 0.2;
                this.moveZ = this.moveZ * 0.8 + dz / step * 0.2;
            }
        }
        WorkThrottle.setUrgency(LoadSpeed.of(this.config.loadSpeed).urgency(this.speed));
        double moveLength = Math.sqrt(this.moveX * this.moveX + this.moveZ * this.moveZ);
        if (this.speed > FAST_SPEED && moveLength > 0.5) {
            this.view.lookX = this.moveX / moveLength;
            this.view.lookZ = this.moveZ / moveLength;
        }
    }

    public void tick() {
        if (!PerfLog.ON) {
            this.tickLod();
            return;
        }
        long start = System.nanoTime();
        this.tickLod();
        PerfLog.tickTime(System.nanoTime() - start);
        if (this.tick % 20 == 1) {
            PerfLog.checkSettings(this.config, this.minecraft);
        }
    }

    private void tickLod() {
        this.tick++;
        ClientLevel level = this.minecraft.level;
        if (level != null) {
            // Settings of the dimension the player is in apply even while LOD terrain is off, the fog ones for example.
            this.config.dimension = DimensionKind.of(level);
            this.rough.setFloating(this.config.dimension == DimensionKind.END);
        }
        if (!this.isActive() || level == null) {
            if (this.rough.hasWork() || this.detail.hasWork()) {
                this.resetTiles();
            }
            this.activeLodDistance = 0;
            return;
        }
        if (level.dimension() != this.dimension) {
            this.dimension = level.dimension();
            this.resetTiles();
        }

        long now = Util.getMillis();
        this.view.update(level);
        this.trackSpeed();
        this.checkUnderground(level);
        this.rough.expirePending(now);
        this.detail.expirePending(now);

        DetailCoverage coverage = null;
        double ringRadius = this.detail.configure(this.hello, this.view);
        this.detailRingRadius = ringRadius;
        // A wider ring fades out over a wider band, so the band never takes much of a small ring.
        double fade = Mth.clamp(ringRadius / 8.0, 16.0, 256.0);
        if (ringRadius > 0.0) {
            this.detail.select(this.view, this.tick, now);
            coverage = new DetailCoverage(this.view, ringRadius, fade, this.detail.drawList());
            for (TileMesh mesh : this.detail.drawList()) {
                boolean edge = mesh.pos.farthestDistanceTo(this.view.camX, this.view.camZ) > ringRadius - fade;
                mesh.keepRadius = edge ? (float) ringRadius : 0.0F;
                mesh.fadeWidth = edge ? (float) fade : 0.0F;
            }
        } else if (this.detail.hasWork()) {
            this.detail.reset();
        }

        this.rough.configure(this.hello, this.view, coverage, ringRadius > 0.0 ? ringRadius : this.view.vanillaRadius);
        this.canopyStart = ringRadius > 0.0 ? ringRadius - fade : this.view.vanillaRadius;
        this.rough.select(this.view, this.tick, now);
        this.rough.applyDetailCutout(ringRadius, fade);
        if (this.tick % FULLY_DRAWN_INTERVAL == 0) {
            this.fullyDrawnRadius = this.view.fullyDrawnRadius(this.minecraft.options.getEffectiveRenderDistance());
        }
        for (TileMesh mesh : this.rough.drawList()) {
            mesh.discardRadius = Math.max(mesh.discardRadius, (float) this.fullyDrawnRadius);
        }
        this.cullHidden(ringRadius);
        if (ringRadius > 0.0) {
            for (TileMesh mesh : this.detail.drawList()) {
                this.vanillaFreeRanges(mesh);
            }
        }
        this.activeLodDistance = (int) Math.min(this.rough.reach(), Integer.MAX_VALUE);

        List<TileMesh> combined = new ArrayList<>(this.detail.drawList().size() + this.rough.drawList().size());
        if (ringRadius > 0.0) {
            combined.addAll(this.detail.drawList());
        }
        combined.addAll(this.rough.drawList());
        this.drawList = List.copyOf(combined);

        if (now >= this.waitingForClearUntil) {
            if (ringRadius > 0.0) {
                this.detail.sendRequests(now);
            }
            this.rough.sendRequests(now);
        }
        this.detail.evict();
        this.rough.evict();
    }

    /**
     * Leaves out what terrain closer to the camera hides, see {@link Horizon}. Not in the End: its islands float over the void, and
     * the ground under them does not hide anything.
     * <p>
     * The culling runs on a background thread from a copy of this tick; its result is used from the next tick on, as long as the
     * camera stays within the margin it was made with. Its margin covers twice the distance of the last tick: until the next result
     * comes, and until the frame drawn just before the next tick.
     */
    private void cullHidden(double ringRadius) {
        double moved = Math.sqrt(Mth.square(this.view.camX - this.cullCamX) + Mth.square(this.view.camY - this.cullCamY)
            + Mth.square(this.view.camZ - this.cullCamZ));
        this.cullCamX = this.view.camX;
        this.cullCamY = this.view.camY;
        this.cullCamZ = this.view.camZ;
        List<TileMesh> detailTiles = ringRadius > 0.0 ? this.detail.drawList() : List.of();
        boolean off = this.config.dimension == DimensionKind.END || PerfLog.occlusionOff();
        if (this.cullJob != null && this.cullJob.isDone()) {
            this.cullResult = this.cullJob.getNow(null);
            this.cullJob = null;
            if (PerfLog.ON && this.cullResult != null) {
                PerfLog.horizonTime(this.cullResult.nanos());
            }
        }
        Horizon.Result result = off ? null : this.cullResult;
        if (result != null) {
            double away = Math.sqrt(Mth.square(this.view.camX - result.camX()) + Mth.square(this.view.camY - result.camY())
                + Mth.square(this.view.camZ - result.camZ()));
            // Until the next tick the camera moves about as far again.
            if (away + moved > result.margin()) {
                result = null;
            }
        }
        for (TileMesh mesh : detailTiles) {
            mesh.occluded = result != null && result.occluded().containsKey(mesh);
            mesh.hiddenChunks = result == null ? null : result.hiddenChunks().get(mesh);
        }
        for (TileMesh mesh : this.rough.drawList()) {
            mesh.occluded = result != null && result.occluded().containsKey(mesh);
        }
        if (off) {
            this.cullResult = null;
            return;
        }
        if (this.cullJob == null) {
            Horizon.Job job = Horizon.snapshot(this.view, detailTiles, this.rough.drawList(), ringRadius, HIDDEN_MARGIN + 4.0 * Math.min(moved, 64.0));
            try {
                this.cullJob = CompletableFuture.supplyAsync(job::run, this.cullExecutor);
            } catch (RejectedExecutionException e) {
                this.cullJob = null;
            }
        }
    }

    /** Blocks the camera may move before culling is done again, on top of twice the distance of the last tick. */
    private static final double HIDDEN_MARGIN = 2.0;

    /**
     * Detailed blocks under vanilla chunks would show through the gaps of grass and leaves as ghost blocks, so the chunks of a tile
     * that vanilla already draws are left out. Chunks vanilla draws only partly, seen from high above for example, are drawn without
     * the layer vanilla draws.
     */
    private void vanillaFreeRanges(TileMesh mesh) {
        mesh.drawRanges = null;
        mesh.clippedChunks = null;
        int[] segments = mesh.segments;
        int[] surfaces = mesh.chunkSurfaces;
        int[] plants = mesh.chunkPlants;
        boolean[] hidden = mesh.hiddenChunks;
        if (segments == null || surfaces == null) {
            return;
        }
        boolean anyHidden = false;
        if (hidden != null) {
            for (boolean chunkHidden : hidden) {
                anyHidden |= chunkHidden;
            }
        }
        boolean nearVanilla = mesh.pos.distanceTo(this.view.camX, this.view.camZ) <= this.view.vanillaRadius + 32.0;
        // Grass and flowers are only drawn a little beyond vanilla's chunks; farther out they are too small to see.
        double plantReach = Math.max(PLANT_MIN_REACH, this.view.vanillaRadius + PLANT_REACH_BEYOND_VANILLA);
        boolean cutPlants = plants != null && mesh.pos.farthestDistanceTo(this.view.camX, this.view.camZ) > plantReach;
        if (!nearVanilla && !cutPlants && !anyHidden) {
            return;
        }
        int side = mesh.chunksPerSide();
        int baseX = (int) Math.floorDiv(mesh.pos.minX(), 16L);
        int baseZ = (int) Math.floorDiv(mesh.pos.minZ(), 16L);
        LodView.Coverage[] coverage = new LodView.Coverage[side * side];
        int[] masks = new int[side * side];
        int[] lowest = new int[side * side];
        int[] ends = new int[side * side];
        boolean allNone = true;
        boolean anyPartial = false;
        boolean anyCut = false;
        for (int chunk = 0; chunk < side * side; chunk++) {
            int chunkX = baseX + chunk % side;
            int chunkZ = baseZ + chunk / side;
            if (nearVanilla) {
                coverage[chunk] = this.view.coverage(chunkX, chunkZ, surfaces[chunk * 2], surfaces[chunk * 2 + 1]);
                masks[chunk] = this.view.drawnSections;
                lowest[chunk] = this.view.lowestSection;
            } else {
                coverage[chunk] = LodView.Coverage.NONE;
            }
            if (hidden != null && hidden[chunk]) {
                // Hidden behind terrain: drawn neither in full nor clipped.
                coverage[chunk] = LodView.Coverage.FULL;
                anyCut = true;
            }
            allNone &= coverage[chunk] == LodView.Coverage.NONE;
            anyPartial |= coverage[chunk] == LodView.Coverage.PARTIAL;
            ends[chunk] = segments[TileMesh.GROUP_OTHER * side * side + chunk + 1];
            if (cutPlants) {
                double dx = chunkX * 16.0 + 8.0 - this.view.camX;
                double dz = chunkZ * 16.0 + 8.0 - this.view.camZ;
                if (Math.sqrt(dx * dx + dz * dz) - 8.0 > plantReach) {
                    ends[chunk] = plants[chunk];
                    anyCut = true;
                }
            }
        }
        if (allNone && !anyCut) {
            return;
        }
        mesh.drawRanges = ranges(segments, ends, coverage, LodView.Coverage.NONE);
        if (anyPartial) {
            int chunks = side * side;
            int[] clipped = new int[chunks * TileMesh.GROUPS * 4];
            int count = 0;
            for (int group = 0; group < TileMesh.GROUPS; group++) {
                for (int chunk = 0; chunk < chunks; chunk++) {
                    int segment = group * chunks + chunk;
                    if (coverage[chunk] != LodView.Coverage.PARTIAL || segments[segment + 1] <= segments[segment]) {
                        continue;
                    }
                    int first = segments[segment] * 6;
                    int length = (segments[segment + 1] - segments[segment]) * 6;
                    if (count > 0 && clipped[count - 2] == masks[chunk] && clipped[count - 1] == lowest[chunk]
                        && clipped[count - 4] + clipped[count - 3] == first) {
                        // The next chunk in the buffer with the same sections drawn: one draw for both.
                        clipped[count - 3] += length;
                        continue;
                    }
                    clipped[count++] = first;
                    clipped[count++] = length;
                    clipped[count++] = masks[chunk];
                    clipped[count++] = lowest[chunk];
                }
            }
            mesh.clippedChunks = Arrays.copyOf(clipped, count);
        }
    }

    /**
     * Index ranges, as pairs of first index and count, of the chunks with the given coverage in every group; in the group with the
     * plants each chunk ends at {@code plantEnds} (before its plants when they are left out). Parts that follow each other in the
     * buffer are joined.
     */
    private static int[] ranges(int[] segments, int[] plantEnds, LodView.Coverage[] coverage, LodView.Coverage wanted) {
        int chunks = coverage.length;
        int[] ranges = new int[chunks * TileMesh.GROUPS * 2];
        int count = 0;
        for (int group = 0; group < TileMesh.GROUPS; group++) {
            for (int chunk = 0; chunk < chunks; chunk++) {
                int start = segments[group * chunks + chunk];
                int end = group == TileMesh.GROUP_OTHER ? plantEnds[chunk] : segments[group * chunks + chunk + 1];
                if (coverage[chunk] != wanted || end <= start) {
                    continue;
                }
                int first = start * 6;
                int length = (end - start) * 6;
                if (count > 0 && ranges[count - 2] + ranges[count - 1] == first) {
                    ranges[count - 1] += length;
                } else {
                    ranges[count++] = first;
                    ranges[count++] = length;
                }
            }
        }
        return Arrays.copyOf(ranges, count);
    }

    /** Grass and flowers of detailed tiles are drawn this far beyond vanilla's chunks, and at least {@link #PLANT_MIN_REACH} blocks. */
    private static final double PLANT_REACH_BEYOND_VANILLA = 64.0;
    private static final double PLANT_MIN_REACH = 128.0;

    /** Queued meshes are dropped; running ones finish. Nothing is interrupted, see {@link WorkThrottle.Worker}. */
    public void close() {
        this.resetTiles();
        this.meshExecutor.getQueue().clear();
        this.meshExecutor.shutdown();
        this.cullExecutor.shutdown();
    }
}
