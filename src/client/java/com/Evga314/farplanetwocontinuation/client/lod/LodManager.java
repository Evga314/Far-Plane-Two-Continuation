package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.FarPlaneDebug;
import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.client.LoadSpeed;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.mojang.blaze3d.systems.RenderSystem;
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
    /** Which chunks vanilla draws, see {@link LodView#chunkMask}, updated with the radius, around the chunk {@link #drawnX}, {@link #drawnZ}. */
    private byte[] drawnChunks = new byte[0];
    private int drawnHalf = -1;
    private int drawnX;
    private int drawnZ;
    /** What the rough terrain leaves to vanilla and the detailed tiles this tick, read by the renderer, see {@link #updateChunkMask}. */
    private volatile ChunkMask chunkMask = new ChunkMask(new int[0], -1, 0, 0, 0.0F, 0, 0);
    /** The values of the mask being made, swapped with those of {@link #chunkMask} only when they differ. */
    private int[] nextMaskValues = new int[0];
    private int maskVersion;

    public LodManager(Minecraft minecraft, ClientConfig config) {
        this.minecraft = minecraft;
        this.config = config;
        int threads = config.resolvedMeshThreads();
        AtomicInteger counter = new AtomicInteger();
        this.meshExecutor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            // A worker, so its pauses for late frames go by the time it worked, see WorkThrottle.
            Thread thread = new WorkThrottle.Worker(runnable, "FarPlane mesh builder #" + counter.incrementAndGet());
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
            && (this.config.forceLodWithShaders || !IrisCompat.shadersActive())
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

    ChunkMask chunkMask() {
        return this.chunkMask;
    }

    /**
     * Per chunk up to {@code half} chunks from the one at {@code originX}, {@code originZ}, row by row from the north-west corner: where
     * the rough terrain gives way. In chunks vanilla draws it keeps no depth up to {@code reach} blocks above and below the camera, as
     * far as vanilla draws sections, so vanilla is drawn over it: {@link #VANILLA} marks those where only its water is still drawn
     * under vanilla's, {@link #VANILLA_EDGE} those next to chunks vanilla does not draw, where all of it is, to fill the view past the
     * edge under its surface. {@link #DETAILED} marks chunks the detailed tiles draw, where it is left out entirely, but for its water
     * in those along their edge ({@link #DETAILED_EDGE}): it fills the view under the edge of their water, which lies a little higher.
     * From bit {@link #FLOOR_SHIFT} on: a height under every block vanilla or the detailed tiles draw in the chunk, counted from
     * {@code minY} up from 1, 0 for none. The ground of the rough terrain is lowered under it there instead of cut out, so through a gap
     * at the edge of what they draw its slope down under them is seen, never a hole down to the bottom of the world.
     */
    record ChunkMask(int[] values, int half, int originX, int originZ, float reach, int minY, int version) {
        static final int VANILLA = 1;
        static final int VANILLA_EDGE = 2;
        static final int DETAILED = 256;
        static final int DETAILED_EDGE = 512;
        static final int FLOOR_SHIFT = 16;
        static final int MAX_FLOOR = 0xFFFF;
    }

    private int seenDetailVersion = -1;
    /** The floors of the chunks of the mask being made, see ChunkMask; kept from tick to tick. */
    private int[] maskFloors = new int[0];

    /**
     * Puts the chunks vanilla draws and those the detailed tiles draw together, see {@link ChunkMask}. A chunk is marked detailed while
     * a tile drawing it has all its blocks there, never in the band where the ring fades out. Tiles hidden behind terrain count as
     * drawn: the rough terrain there is behind the same terrain, and a mark that came and went with culling would make the rough
     * terrain flicker in and out. The mask is made into a second array and only becomes the new one, with a new version the renderer
     * sends to the GPU, when something in it changed.
     */
    private void updateChunkMask() {
        int half = this.drawnHalf;
        if (half < 0) {
            return;
        }
        int side = 2 * half + 1;
        byte[] drawn = this.drawnChunks;
        if (this.nextMaskValues.length != side * side) {
            this.nextMaskValues = new int[side * side];
        } else {
            Arrays.fill(this.nextMaskValues, 0);
        }
        int[] values = this.nextMaskValues;
        if (this.maskFloors.length != side * side) {
            this.maskFloors = new int[side * side];
        }
        int[] floors = this.maskFloors;
        Arrays.fill(floors, LodView.NO_FLOOR);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (drawn[x + z * side] == 0) {
                    continue;
                }
                floors[x + z * side] = this.view.chunkFloor(this.drawnX + x - half, this.drawnZ + z - half);
                boolean edge = false;
                for (int nz = z - 1; nz <= z + 1 && !edge; nz++) {
                    for (int nx = x - 1; nx <= x + 1; nx++) {
                        if (nx < 0 || nz < 0 || nx >= side || nz >= side || drawn[nx + nz * side] == 0) {
                            edge = true;
                            break;
                        }
                    }
                }
                values[x + z * side] = edge ? ChunkMask.VANILLA_EDGE : ChunkMask.VANILLA;
            }
        }
        double camX = this.view.camX;
        double camZ = this.view.camZ;
        for (TileMesh mesh : this.detail.drawList()) {
            if (mesh.vertices.isClosed()) {
                continue;
            }
            int squares = Math.max(1, mesh.pos.size() / DETAIL_SQUARE);
            for (int square = 0; square < squares * squares; square++) {
                if ((mesh.coverage & 1L << square) == 0) {
                    continue;
                }
                long squareX = mesh.pos.minX() + (long) (square % squares) * DETAIL_SQUARE;
                long squareZ = mesh.pos.minZ() + (long) (square / squares) * DETAIL_SQUARE;
                for (int c = 0; c < (DETAIL_SQUARE / 16) * (DETAIL_SQUARE / 16); c++) {
                    long chunkMinX = squareX + (c % (DETAIL_SQUARE / 16)) * 16L;
                    long chunkMinZ = squareZ + (c / (DETAIL_SQUARE / 16)) * 16L;
                    int x = (int) Math.floorDiv(chunkMinX, 16L) - this.drawnX + half;
                    int z = (int) Math.floorDiv(chunkMinZ, 16L) - this.drawnZ + half;
                    if (x < 0 || z < 0 || x >= side || z >= side) {
                        continue;
                    }
                    if (mesh.keepRadius > 0.0F) {
                        double fx = Math.max(Math.abs(chunkMinX - camX), Math.abs(chunkMinX + 16.0 - camX));
                        double fz = Math.max(Math.abs(chunkMinZ - camZ), Math.abs(chunkMinZ + 16.0 - camZ));
                        if (Math.sqrt(fx * fx + fz * fz) > mesh.keepRadius - mesh.fadeWidth) {
                            continue;
                        }
                    }
                    values[x + z * side] |= ChunkMask.DETAILED;
                    int[] surfaces = mesh.chunkSurfaces;
                    if (surfaces != null) {
                        int perSide = mesh.pos.size() / 16;
                        int chunk = (int) ((chunkMinX - mesh.pos.minX()) / 16 + (chunkMinZ - mesh.pos.minZ()) / 16 * perSide);
                        // The lowest block of the chunk the tile shows; nothing shown, nothing to lie under.
                        int low = chunk * 2 < surfaces.length ? surfaces[chunk * 2] : Integer.MAX_VALUE;
                        if (low != Integer.MAX_VALUE) {
                            int floor = low - 1;
                            int known = floors[x + z * side];
                            floors[x + z * side] = known == LodView.NO_FLOOR ? floor : Math.min(known, floor);
                        }
                    }
                }
            }
        }
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if ((values[x + z * side] & ChunkMask.DETAILED) == 0) {
                    continue;
                }
                boolean edge = false;
                for (int nz = z - 1; nz <= z + 1 && !edge; nz++) {
                    for (int nx = x - 1; nx <= x + 1; nx++) {
                        if (nx < 0 || nz < 0 || nx >= side || nz >= side || (values[nx + nz * side] & ChunkMask.DETAILED) == 0) {
                            edge = true;
                            break;
                        }
                    }
                }
                if (edge) {
                    values[x + z * side] |= ChunkMask.DETAILED_EDGE;
                }
            }
        }
        int minY = this.view.worldMinY;
        for (int i = 0; i < values.length; i++) {
            if (floors[i] != LodView.NO_FLOOR) {
                values[i] |= Mth.clamp(floors[i] - minY + 1, 1, ChunkMask.MAX_FLOOR) << ChunkMask.FLOOR_SHIFT;
            }
        }
        ChunkMask last = this.chunkMask;
        float reach = this.minecraft.options.getEffectiveRenderDistance() * 16.0F;
        if (last.half() == half && last.originX() == this.drawnX && last.originZ() == this.drawnZ && last.reach() == reach
            && last.minY() == minY && Arrays.equals(last.values(), values)) {
            return;
        }
        this.nextMaskValues = last.values().length == values.length ? last.values() : new int[values.length];
        this.chunkMask = new ChunkMask(values, half, this.drawnX, this.drawnZ, reach, minY, ++this.maskVersion);
    }

    /** Detailed tiles know which of their squares of this many blocks have all their blocks, see TileMesh.coverage. */
    private static final int DETAIL_SQUARE = 64;

    /** How far vanilla draws chunks around the camera, horizontally. */
    double vanillaRadius() {
        return this.minecraft.options.getEffectiveRenderDistance() * 16.0;
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
        // Deep in the sea there is no sky light either, and the heightmap stops at the surface of the water: in water the camera is
        // never underground, whatever lies over it.
        this.enclosedUnderground = level.dimensionType().hasSkyLight()
            && camera.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, camera.getX(), camera.getZ()) - UNDERGROUND_DEPTH
            && level.getBrightness(LightLayer.SKY, camera) == 0
            && level.getFluidState(camera).isEmpty();
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
        // Makes the GPU memory too, while the world is still loading, see reserveMemory.
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

    /**
     * GPU memory made while the world loads, where the stall of making it is not seen: three quarters of the limit of the detailed
     * tiles and what the rough ones usually take. The rest is made when needed, so a limit set higher than the GPU has does not stop
     * the world from loading. Done whenever all tiles were dropped, so a lower limit gives memory back at once.
     */
    private void reserveMemory() {
        if (this.hello == null) {
            return;
        }
        ClientConfig.DetailSettings detail = this.config.detail();
        this.detail.reserveMemory(detail.enabled ? detail.gpuMegabytes * 3 / 4 : 0);
        this.rough.reserveMemory(ROUGH_RESERVED_MB);
    }

    /** GPU memory of the rough tiles made while the world loads; they took 20 to 60 MB in the logs. */
    private static final int ROUGH_RESERVED_MB = 64;

    public void onDisconnect() {
        this.waitingForClearUntil = 0L;
        this.hello = null;
        this.dimension = null;
        this.resetTiles();
        // Leaving the world: its GPU memory is given back.
        this.rough.closeMemory();
        this.detail.closeMemory();
    }

    public void resetTiles() {
        this.rough.reset();
        this.detail.reset();
        this.reserveMemory();
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
        if (FarPlaneDebug.ENABLED && !RenderSystem.isOnRenderThread()) {
            // The layers and TileMesh are plain render-thread state; a tick from anywhere else would corrupt them silently, so a
            // debug build fails loudly instead. In a release build ENABLED is a constant and this check costs nothing.
            throw new IllegalStateException("FarPlane LOD tick runs off the render thread");
        }
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
        if (DebugToggles.refresh()) {
            this.resetTiles();
        }
        ClientLevel level = this.minecraft.level;
        if (level != null) {
            // Settings of the dimension the player is in apply even while LOD terrain is off, the fog ones for example.
            this.config.dimension = DimensionKind.of(level);
            this.rough.setFloating(this.config.dimension == DimensionKind.END);
            this.view.extendCloudFarPlane = this.config.extendCloudFarPlane;
            // A shader pack replaces vanilla's main pass with its own: the LOD terrain keeps its pass of its own then. So it does where
            // depth runs from -1 to 1 (OpenGL without clip control, as on phones through LTW): the far plane at infinity in vanilla's
            // depth buffer then leaves too little precision, and the LOD terrain and vanilla's blocks fought over it as the camera
            // turned.
            LodRenderer.mainPassPrototype = this.config.newRenderer && !IrisCompat.shadersActive() && !DebugToggles.noMainPass && !DebugToggles.surface
                && RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
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
        long phaseStart = PerfLog.ON ? System.nanoTime() : 0L;
        this.view.update(level);
        this.trackSpeed();
        this.checkUnderground(level);
        this.rough.expirePending(now);
        this.detail.expirePending(now);
        phaseStart = this.tickPhase(0, phaseStart);

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
        phaseStart = this.tickPhase(1, phaseStart);

        this.rough.configure(this.hello, this.view, coverage, ringRadius > 0.0 ? ringRadius : this.view.vanillaRadius);
        this.canopyStart = ringRadius > 0.0 ? ringRadius - fade : this.view.vanillaRadius;
        // Rough tiles drop out where detailed tiles cover the ground: when those change, the rough tree is walked again.
        if (this.detail.drawListVersion() != this.seenDetailVersion) {
            this.seenDetailVersion = this.detail.drawListVersion();
            this.rough.walkAgain();
        }
        this.rough.select(this.view, this.tick, now);
        phaseStart = this.tickPhase(2, phaseStart);
        if (this.tick % FULLY_DRAWN_INTERVAL == 0) {
            double lastFullyDrawn = this.fullyDrawnRadius;
            this.fullyDrawnRadius = this.view.fullyDrawnRadius(this.minecraft.options.getEffectiveRenderDistance());
            int half = this.minecraft.options.getEffectiveRenderDistance() + 1;
            if (PerfLog.ON) {
                this.watchMask(lastFullyDrawn);
            }
            this.drawnChunks = this.view.chunkMask(half, this.drawnChunks);
            this.drawnHalf = half;
            this.drawnX = Mth.floor(this.view.camX) >> 4;
            this.drawnZ = Mth.floor(this.view.camZ) >> 4;
            this.findMaskEdge();
            if (PerfLog.ON && this.tick % 100 == 0) {
                int drawn = 0;
                for (byte value : this.drawnChunks) {
                    drawn += value;
                }
                int detailed = 0;
                for (int value : this.chunkMask.values()) {
                    detailed += (value & ChunkMask.DETAILED) != 0 ? 1 : 0;
                }
                LOGGER.info("[FarPlane chunks] half={} chunks={} drawn by vanilla={} detailed={} fullyDrawnRadius={} vanillaRadius={} detailRing={} nearest not drawn: {}",
                    half, this.drawnChunks.length, drawn, detailed, Math.round(this.fullyDrawnRadius), Math.round(this.vanillaRadius()), Math.round(this.detailRingRadius),
                    this.view.fullyDrawnReason);
            }
        }
        // The rough terrain gives way to vanilla's chunks by the chunk mask alone, chunk by chunk as they come: cut away within the
        // radius vanilla draws fully as well, its edge jumped with that radius whenever Sodium built a section near the camera.
        this.updateChunkMask();
        phaseStart = this.tickPhase(3, phaseStart);
        this.cullHidden(ringRadius);
        phaseStart = this.tickPhase(4, phaseStart);
        this.edgeMeshes.clear();
        this.edgeChunks.clear();
        if (ringRadius > 0.0) {
            for (TileMesh mesh : this.detail.drawList()) {
                this.vanillaFreeRanges(mesh, true);
            }
        }
        phaseStart = this.tickPhase(5, phaseStart);
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
        this.tickPhase(6, phaseStart);
    }

    // Debug build: the chunk mask of the last check, to see chunks inside vanilla's area drop out of it for a moment.
    private byte[] watchedMask = new byte[0];

    /**
     * Debug build, before the chunk mask is worked out again: logs when chunks well inside what vanilla draws stop counting as drawn by
     * vanilla in the new one, or when the radius within which vanilla draws everything shrinks a lot. Either lets the rough terrain
     * through over vanilla's chunks, which looks like a flash of it over the whole sea around.
     */
    private void watchMask(double lastFullyDrawn) {
        byte[] before = this.drawnChunks;
        int beforeHalf = this.drawnHalf;
        int beforeX = this.drawnX;
        int beforeZ = this.drawnZ;
        // The new mask, worked out here once more only for this check; the tick goes on with its own below.
        int half = this.minecraft.options.getEffectiveRenderDistance() + 1;
        this.watchedMask = this.view.chunkMask(half, this.watchedMask);
        int side = 2 * half + 1;
        int centerX = Mth.floor(this.view.camX) >> 4;
        int centerZ = Mth.floor(this.view.camZ) >> 4;
        double inner = (this.minecraft.options.getEffectiveRenderDistance() - 3) * 16.0;
        int lost = 0;
        int drawn = 0;
        int beforeSide = 2 * beforeHalf + 1;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (this.watchedMask[x + z * side] != 0) {
                    drawn++;
                    continue;
                }
                int chunkX = centerX + x - half;
                int chunkZ = centerZ + z - half;
                double dx = chunkX * 16.0 + 8.0 - this.view.camX;
                double dz = chunkZ * 16.0 + 8.0 - this.view.camZ;
                if (dx * dx + dz * dz > inner * inner) {
                    continue;
                }
                int bx = chunkX - beforeX + beforeHalf;
                int bz = chunkZ - beforeZ + beforeHalf;
                if (bx >= 0 && bz >= 0 && bx < beforeSide && bz < beforeSide && bx + bz * beforeSide < before.length && before[bx + bz * beforeSide] != 0) {
                    lost++;
                }
            }
        }
        if (lost >= 4 || this.fullyDrawnRadius < lastFullyDrawn - 48.0) {
            PerfLog.flicker(String.format(java.util.Locale.ROOT,
                "tick %d: %d chunks inside vanilla's area no longer count as drawn (%d drawn now), fully drawn radius %.0f -> %.0f (%s), camera y %.1f",
                this.tick, lost, drawn, lastFullyDrawn, this.fullyDrawnRadius, this.view.fullyDrawnReason, this.view.camY));
        }
    }

    /** Debug build, every frame: logs when what Sodium draws drops sharply from one frame to the next. */
    void watchSodiumFrame() {
        int sections = this.view.sodiumFrameSections();
        if (sections >= 0 && this.lastSodiumSections > 50 && sections < this.lastSodiumSections * 0.7) {
            PerfLog.flicker("Sodium draws " + sections + " sections this frame, " + this.lastSodiumSections + " the frame before");
        }
        this.lastSodiumSections = sections;
    }

    private int lastSodiumSections = -1;

    /** Adds the time since {@code start} to a part of the tick in the debug build, see PerfLog.TICK_PHASES; returns the time now. */
    private long tickPhase(int phase, long start) {
        if (!PerfLog.ON) {
            return 0L;
        }
        long now = System.nanoTime();
        PerfLog.tickPhase(phase, now - start);
        return now;
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
            // Rough terrain within vanilla's distance lies under vanilla's blocks (see the vertex shader) and hides nothing behind it,
            // with or without the detailed tiles: counted as hiding, its guessed shape hid and showed the tiles right beyond vanilla's
            // edge as the camera moved.
            double cut = Math.max(ringRadius, this.view.vanillaRadius + 16.0);
            Horizon.Job job = Horizon.snapshot(this.view, detailTiles, this.rough.drawList(), cut, HIDDEN_MARGIN + 4.0 * Math.min(moved, 64.0));
            try {
                this.cullJob = CompletableFuture.supplyAsync(job::run, this.cullExecutor);
            } catch (RejectedExecutionException e) {
                this.cullJob = null;
            }
        }
    }

    /** Whether the middle of a chunk lies near the edge of vanilla's render distance, where it starts or stops drawing chunks. */
    private boolean alongVanillaEdge(int chunkX, int chunkZ) {
        double dx = chunkX * 16.0 + 8.0 - this.view.camX;
        double dz = chunkZ * 16.0 + 8.0 - this.view.camZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double edge = this.minecraft.options.getEffectiveRenderDistance() * 16.0;
        return distance > edge - EDGE_INSIDE && distance < edge + EDGE_OUTSIDE;
    }

    /** Blocks the camera may move before culling is done again, on top of twice the distance of the last tick. */
    private static final double HIDDEN_MARGIN = 2.0;

    /**
     * The chunks of detailed tiles along the edge of what vanilla draws, as they were last worked out, see {@link #frameCheck}: per
     * chunk its tile, then {@link #EDGE_FIELDS} ints: the chunk, the lowest and highest block the tile has in it, and what vanilla drew
     * of it (the coverage, its sections and the lowest of them).
     */
    private final ArrayList<TileMesh> edgeMeshes = new ArrayList<>();
    private final it.unimi.dsi.fastutil.ints.IntArrayList edgeChunks = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final int EDGE_FIELDS = 7;
    /** How far inside and outside vanilla's render distance chunks count as along its edge, in blocks. */
    private static final double EDGE_INSIDE = 40.0;
    private static final double EDGE_OUTSIDE = 24.0;
    private final java.util.ArrayList<TileMesh> changedEdgeMeshes = new java.util.ArrayList<>();

    /**
     * The chunks of the mask along the edge of what vanilla draws, as they were last worked out (see {@link #frameMask}): per chunk its
     * index in {@link #drawnChunks}, how many surface sections it has and the sections.
     */
    private final it.unimi.dsi.fastutil.ints.IntArrayList maskEdge = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private int[] sectionScratch = new int[8];

    /** Finds the chunks of the mask along the edge of what vanilla draws, with the sections of their surfaces, see frameMask. */
    private void findMaskEdge() {
        this.maskEdge.clear();
        int half = this.drawnHalf;
        int side = 2 * half + 1;
        int[] sections = this.sectionScratch;
        for (int i = 0; i < this.drawnChunks.length; i++) {
            int chunkX = this.drawnX + i % side - half;
            int chunkZ = this.drawnZ + i / side - half;
            if (!this.alongVanillaEdge(chunkX, chunkZ)) {
                continue;
            }
            int count = this.view.surfaceSections(chunkX, chunkZ, sections, 0);
            if (count == 0) {
                continue;
            }
            this.maskEdge.add(i);
            this.maskEdge.add(count);
            for (int k = 0; k < count; k++) {
                this.maskEdge.add(sections[k]);
            }
        }
    }

    /**
     * Once a frame: the chunks along the edge of what vanilla draws are asked again by what Sodium draws in this very frame (see
     * LodView#frameColumn), and the rough terrain gives way to a chunk, or takes it back, in the same frame vanilla starts or stops
     * drawing it, not up to a few ticks later. On phones Sodium culls on a thread of its own and draws a section a frame or more
     * after it is built; worked out by ticks, the rough terrain was cut away over nothing there for a moment, or drawn over vanilla.
     */
    private void frameMask() {
        byte[] drawn = this.drawnChunks;
        int half = this.drawnHalf;
        int side = 2 * half + 1;
        int[] edge = this.maskEdge.elements();
        int changed = 0;
        for (int at = 0; at < this.maskEdge.size(); at += 2 + edge[at + 1]) {
            int index = edge[at];
            if (index >= drawn.length) {
                continue;
            }
            int state = this.view.frameColumn(this.drawnX + index % side - half, this.drawnZ + index / side - half, edge, at + 2, edge[at + 1]);
            byte now = state > 0 ? (byte) 1 : state < 0 ? (byte) 0 : drawn[index];
            if (now != drawn[index]) {
                drawn[index] = now;
                changed++;
            }
        }
        if (changed > 0) {
            // Debug build: chunks along the edge given to vanilla or taken back within a frame, to tell such changes from others.
            if (PerfLog.ON) {
                PerfLog.flicker("tick " + this.tick + ": " + changed + " chunks along vanilla's edge changed hands");
            }
            this.updateChunkMask();
        }
    }

    /**
     * Once a frame, right before the LOD terrain is drawn, after Sodium (or vanilla) has taken in the sections it built for this frame:
     * the chunks along the edge of what vanilla draws are asked again, and the tiles where vanilla started or stopped drawing one leave
     * it out or take it back in this very frame. Worked out only every tick, both would draw a chunk at once for a moment, or neither.
     * Only the edge is asked: inside it what vanilla draws hardly changes.
     */
    public void frameCheck(double camX, double camY, double camZ) {
        if (this.edgeMeshes.isEmpty() && this.maskEdge.isEmpty()) {
            // Nothing along the edge: what Sodium drew is not needed, and an old frame of it must not be taken for this one.
            this.view.forgetFrame();
            return;
        }
        this.view.frameCamera(camX, camY, camZ);
        if (!this.maskEdge.isEmpty() && !DebugToggles.noFrameMask) {
            this.frameMask();
        }
        List<TileMesh> changed = this.changedEdgeMeshes;
        changed.clear();
        int[] chunks = this.edgeChunks.elements();
        for (int i = 0; i < this.edgeMeshes.size(); i++) {
            TileMesh mesh = this.edgeMeshes.get(i);
            int at = i * EDGE_FIELDS;
            if (mesh.vertices.isClosed()) {
                continue;
            }
            LodView.Coverage coverage = this.view.coverage(chunks[at], chunks[at + 1], chunks[at + 2], chunks[at + 3], true);
            if (coverage.ordinal() == chunks[at + 4] && this.view.drawnSections == chunks[at + 5] && this.view.lowestSection == chunks[at + 6]) {
                continue;
            }
            chunks[at + 4] = coverage.ordinal();
            chunks[at + 5] = this.view.drawnSections;
            chunks[at + 6] = this.view.lowestSection;
            if (changed.isEmpty() || changed.get(changed.size() - 1) != mesh) {
                changed.add(mesh);
            }
        }
        for (TileMesh mesh : changed) {
            this.vanillaFreeRanges(mesh, false);
        }
    }

    /**
     * Detailed blocks under vanilla chunks would show through the gaps of grass and leaves as ghost blocks, so the chunks of a tile
     * that vanilla already draws are left out. Chunks vanilla draws only partly, seen from high above for example, are drawn without
     * the layer vanilla draws. {@code record}: remember the chunks along the edge of what vanilla draws, see {@link #frameCheck}.
     */
    private void vanillaFreeRanges(TileMesh mesh, boolean record) {
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
        // Grass and flowers are only drawn a little beyond vanilla's chunks; farther out they are too small to see. Glowing things in
        // the water (sea pickles) come before them in every chunk and go farther, see GLOW_REACH.
        double plantReach = Math.max(PLANT_MIN_REACH, this.view.vanillaRadius + PLANT_REACH_BEYOND_VANILLA);
        boolean cutPlants = plants != null && mesh.pos.farthestDistanceTo(this.view.camX, this.view.camZ) > plantReach;
        if (!nearVanilla && !cutPlants && !anyHidden) {
            return;
        }
        int side = mesh.chunksPerSide();
        int baseX = (int) Math.floorDiv(mesh.pos.minX(), 16L);
        int baseZ = (int) Math.floorDiv(mesh.pos.minZ(), 16L);
        if (this.rangeCoverage.length < side * side) {
            this.rangeCoverage = new LodView.Coverage[side * side];
            this.rangeMasks = new int[side * side];
            this.rangeLowest = new int[side * side];
            this.rangeEnds = new int[side * side];
            this.rangeScratch = new int[side * side * TileMesh.GROUPS * 4];
        }
        LodView.Coverage[] coverage = this.rangeCoverage;
        int[] masks = this.rangeMasks;
        int[] lowest = this.rangeLowest;
        int[] ends = this.rangeEnds;
        boolean allNone = true;
        boolean anyPartial = false;
        boolean anyCut = false;
        for (int chunk = 0; chunk < side * side; chunk++) {
            int chunkX = baseX + chunk % side;
            int chunkZ = baseZ + chunk / side;
            if (nearVanilla) {
                // Along the edge of what vanilla draws, by what Sodium drew in the last frame, as frameCheck asks it.
                boolean edgeChunk = this.alongVanillaEdge(chunkX, chunkZ);
                coverage[chunk] = this.view.coverage(chunkX, chunkZ, surfaces[chunk * 2], surfaces[chunk * 2 + 1], edgeChunk);
                masks[chunk] = this.view.drawnSections;
                lowest[chunk] = this.view.lowestSection;
                if (record && edgeChunk) {
                    this.edgeMeshes.add(mesh);
                    this.edgeChunks.add(chunkX);
                    this.edgeChunks.add(chunkZ);
                    this.edgeChunks.add(surfaces[chunk * 2]);
                    this.edgeChunks.add(surfaces[chunk * 2 + 1]);
                    this.edgeChunks.add(coverage[chunk].ordinal());
                    this.edgeChunks.add(masks[chunk]);
                    this.edgeChunks.add(lowest[chunk]);
                }
            } else {
                coverage[chunk] = LodView.Coverage.NONE;
                masks[chunk] = 0;
                lowest[chunk] = 0;
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
                double distance = Math.sqrt(dx * dx + dz * dz) - 8.0;
                if (distance > GLOW_REACH) {
                    ends[chunk] = plants[chunk * 2];
                    anyCut = true;
                } else if (distance > plantReach) {
                    ends[chunk] = plants[chunk * 2 + 1];
                    anyCut = true;
                }
            }
        }
        if (allNone && !anyCut) {
            return;
        }
        mesh.drawRanges = this.ranges(segments, ends, coverage, side * side, LodView.Coverage.NONE);
        if (anyPartial) {
            int chunks = side * side;
            int[] clipped = this.rangeScratch;
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
    private int[] ranges(int[] segments, int[] plantEnds, LodView.Coverage[] coverage, int chunks, LodView.Coverage wanted) {
        int[] ranges = this.rangeScratch;
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

    /** Scratch arrays of {@link #vanillaFreeRanges}, as big as the biggest tile needs; only the results are new arrays. */
    private LodView.Coverage[] rangeCoverage = new LodView.Coverage[0];
    private int[] rangeMasks = new int[0];
    private int[] rangeLowest = new int[0];
    private int[] rangeEnds = new int[0];
    private int[] rangeScratch = new int[0];

    /** Grass and flowers of detailed tiles are drawn this far beyond vanilla's chunks, and at least {@link #PLANT_MIN_REACH} blocks. */
    private static final double PLANT_REACH_BEYOND_VANILLA = 64.0;
    private static final double PLANT_MIN_REACH = 128.0;
    /** How far glowing things in the water (sea pickles) and their light are drawn: 48 chunks. */
    private static final double GLOW_REACH = 48 * 16.0;

    /** Queued meshes are dropped; running ones finish. Nothing is interrupted, see {@link WorkThrottle.Worker}. */
    public void close() {
        this.resetTiles();
        this.rough.closeMemory();
        this.detail.closeMemory();
        this.meshExecutor.getQueue().clear();
        this.meshExecutor.shutdown();
        this.cullExecutor.shutdown();
    }
}
