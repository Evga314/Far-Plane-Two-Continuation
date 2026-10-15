package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Client side of the LOD system. Drives two layers every tick: detailed tiles built from real chunks in a ring around the player,
 * and rough terrain from the world generator everywhere else up to the edge of the world.
 */
public final class LodManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CLEAR_TIMEOUT_MS = 30_000L;

    private final Minecraft minecraft;
    private final ClientConfig config;
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
            && !level.dimensionType().hasCeiling();
    }

    /** True when there is LOD terrain on screen, which is when vanilla's render distance fog has to move out of the way. */
    public boolean hasVisibleTerrain() {
        return this.isActive() && !this.drawList.isEmpty();
    }

    public int activeLodDistance() {
        return this.activeLodDistance;
    }

    public List<TileMesh> drawList() {
        return this.drawList;
    }

    public String debugStats() {
        return this.rough.stats() + " | " + this.detail.stats();
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
        int budget = this.detail.upload(this.config.maxUploadsPerFrame);
        this.rough.upload(budget);
    }

    public void tick() {
        this.tick++;
        ClientLevel level = this.minecraft.level;
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
        this.rough.expirePending(now);
        this.detail.expirePending(now);

        DetailCoverage coverage = null;
        double ringRadius = this.detail.configure(this.hello);
        if (ringRadius > 0.0) {
            this.detail.select(this.view, this.tick, now);
            coverage = new DetailCoverage(this.view, ringRadius, this.detail.drawList());
            for (TileMesh mesh : this.detail.drawList()) {
                mesh.keepRadius = mesh.pos.farthestDistanceTo(this.view.camX, this.view.camZ) > ringRadius ? (float) ringRadius : 0.0F;
                mesh.drawRanges = this.vanillaFreeRanges(mesh);
            }
        } else if (this.detail.hasWork()) {
            this.detail.reset();
        }

        this.rough.configure(this.hello, this.view, coverage);
        this.rough.select(this.view, this.tick, now);
        this.rough.applyDetailCutout(ringRadius);
        if (this.tick % FULLY_DRAWN_INTERVAL == 0) {
            this.fullyDrawnRadius = this.view.fullyDrawnRadius(this.minecraft.options.getEffectiveRenderDistance());
        }
        for (TileMesh mesh : this.rough.drawList()) {
            mesh.discardRadius = Math.max(mesh.discardRadius, (float) this.fullyDrawnRadius);
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
     * Detailed blocks under vanilla chunks would show through the gaps of grass and leaves as ghost blocks, so the chunks of a tile
     * that vanilla already draws are left out. Null when nothing has to be left out.
     */
    private int @Nullable [] vanillaFreeRanges(TileMesh mesh) {
        int[] chunkQuads = mesh.chunkQuads;
        if (chunkQuads == null || mesh.pos.distanceTo(this.view.camX, this.view.camZ) > this.view.vanillaRadius + 32.0) {
            return null;
        }
        int side = mesh.chunksPerSide();
        int baseX = (int) Math.floorDiv(mesh.pos.minX(), 16L);
        int baseZ = (int) Math.floorDiv(mesh.pos.minZ(), 16L);
        int[] ranges = new int[side * side * 2];
        int count = 0;
        int open = -1;
        for (int chunk = 0; chunk < side * side; chunk++) {
            boolean hidden = this.view.chunkRendered(baseX + chunk % side, baseZ + chunk / side);
            if (!hidden && open < 0) {
                open = chunkQuads[chunk];
            } else if (hidden && open >= 0) {
                ranges[count++] = open * 6;
                ranges[count++] = (chunkQuads[chunk] - open) * 6;
                open = -1;
            }
        }
        if (open >= 0) {
            ranges[count++] = open * 6;
            ranges[count++] = (chunkQuads[side * side] - open) * 6;
        }
        if (count == 2 && ranges[0] == 0 && ranges[1] == mesh.indexCount) {
            return null;
        }
        return Arrays.copyOf(ranges, count);
    }

    public void close() {
        this.resetTiles();
        this.meshExecutor.shutdownNow();
    }
}
