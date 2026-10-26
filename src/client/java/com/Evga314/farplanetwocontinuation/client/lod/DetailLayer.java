package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.WorkThrottle;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.LoadOrder;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.util.Arrays;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

/**
 * Detailed tiles built from real chunks in a ring around the player: trees, buildings and single blocks, getting coarser with
 * distance. They also fill in vanilla chunks that are not loaded yet.
 */
final class DetailLayer extends LodLayer {
    private static final long INITIAL_TILE_BYTES = 256 * 1024;
    private static final long INCOMPLETE_REFRESH_MS = 60_000L;
    private static final long COMPLETE_REFRESH_MS = 300_000L;
    private static final double RING_STEP = 64.0;
    /** Split factor on a 1080p screen: a tile splits while its cells would take more than about 8 pixels. */
    private static final double SPLIT_FACTOR = 1.5;

    private final ClientConfig config;
    private double reach;
    private double splitFactor;
    /** Running average of real mesh sizes, used to turn the memory budget in megabytes into a number of tiles. */
    private double averageBytes = INITIAL_TILE_BYTES;

    DetailLayer(ClientConfig config, Executor meshExecutor) {
        super("detail", meshExecutor);
        this.config = config;
    }

    /**
     * Returns the radius of the ring in blocks, 0 when detailed tiles are off. The ring never grows past what the memory budget can
     * hold at the coarsest detailed level; beyond that the rough terrain takes over instead of the ring becoming blurry.
     */
    double configure(FarPlanePayloads.Hello hello, LodView view) {
        int radiusChunks = this.config.detail().enabled && hello.detailEnabled() ? Math.min(this.config.detail().radiusChunks, hello.detailMaxRadiusChunks()) : 0;
        double coarsest = FarPlaneConstants.tileSize(FarPlaneConstants.DETAIL_MAX_LEVEL);
        double affordable = Math.sqrt(this.maxMeshes() * LEAF_SHARE * coarsest * coarsest / Math.PI) * 0.85;
        // Snapped to 64 blocks and only moved by a full step, so the edge of the ring does not wander as mesh sizes are learned.
        double target = Math.floor(Math.min(radiusChunks * 16.0, affordable) / RING_STEP) * RING_STEP;
        if (target == 0.0 || this.reach == 0.0 || Math.abs(target - this.reach) >= RING_STEP) {
            this.reach = target;
        }
        // Finer tiles than this would not be seen; more memory makes the ring wider instead.
        this.splitFactor = SPLIT_FACTOR * view.screenScale;
        return this.reach;
    }

    /** Tiles with ungenerated chunks are asked for again soon; complete ones now and then to pick up what players built. */
    @Override
    long refreshIntervalMs(TileMesh mesh) {
        return mesh.complete ? COMPLETE_REFRESH_MS : INCOMPLETE_REFRESH_MS;
    }

    /** First the player's surroundings, then what is in view; see {@link LoadOrder}. */
    @Override
    double priority(TilePos pos, double distance) {
        return LoadOrder.score(pos, this.view.camX, this.view.camZ, this.view.lookX, this.view.lookZ);
    }

    /** The server answers the closest tiles first, so a short queue keeps up with a flying player. */
    @Override
    int maxOutstandingRequests() {
        return 32;
    }

    @Override
    boolean loadsFallbackParents() {
        return false;
    }

    /** Empty detailed tiles are mostly chunks that are not generated yet, which happens as the player moves. */
    @Override
    long emptyRetryMs() {
        return INCOMPLETE_REFRESH_MS / 4;
    }

    @Override
    int lowestLevel() {
        return FarPlaneConstants.DETAIL_MIN_LEVEL;
    }

    /** Next to the player cells are always single blocks; they get coarser with distance on their own. */
    @Override
    int minLevel() {
        return FarPlaneConstants.DETAIL_MIN_LEVEL;
    }

    @Override
    int maxLevel() {
        return FarPlaneConstants.DETAIL_MAX_LEVEL;
    }

    @Override
    double reach() {
        return this.reach;
    }

    @Override
    double splitFactor() {
        return this.splitFactor;
    }

    @Override
    int maxMeshes() {
        long budget = (long) this.config.detail().gpuMegabytes * 1024L * 1024L;
        return (int) Mth.clamp(budget / Math.max(16 * 1024.0, this.averageBytes), 16, 8192);
    }

    @Override
    void onUploaded(TileMesh mesh) {
        this.averageBytes = this.averageBytes * 0.95 + mesh.bytes * 0.05;
    }

    @Override
    boolean canSendRequests() {
        return ClientPlayNetworking.canSend(FarPlanePayloads.DetailRequest.TYPE);
    }

    @Override
    void sendRequests(int[] requests) {
        ClientPlayNetworking.send(new FarPlanePayloads.DetailRequest(
            requests, this.config.detail().generate, Math.round(WorkThrottle.urgency() * 100.0F), this.config.detail().scanDepth
        ));
    }

    @Override
    GeneralMeshFactory.Built build(byte[] bytes, long key, int epoch, Registry<Biome> biomes, int seaLevel) throws Exception {
        DetailData data = DetailData.decode(bytes);
        return DetailMeshBuilder.build(key, epoch, data, Arrays.asList(GeneralMeshFactory.resolveBiomes(biomes, data.palette)), this.config.detail().blocks());
    }
}
