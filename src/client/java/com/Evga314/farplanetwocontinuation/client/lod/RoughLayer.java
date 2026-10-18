package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.client.GpuMemory;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.util.Arrays;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/** Rough terrain straight from the world generator noise, up to the edge of the world. */
final class RoughLayer extends LodLayer {
    private static final double UNDER_SURFACE_TOLERANCE = 4.0;
    private static final int DEFAULT_GPU_TILES = 2048;

    private final ClientConfig config;
    private int minLevel;
    private double reach;
    private double splitFactor;
    /** Size of the cells, in blocks, right where the rough terrain starts, and the distance at which it starts. */
    private double startCell;
    private double edge;
    /** Cells double in size every this many blocks past the edge, until they are as coarse as the screen allows. */
    private double doubling;
    private @Nullable DetailCoverage detailCoverage;
    private volatile boolean floating;

    RoughLayer(ClientConfig config, Executor meshExecutor) {
        super("rough", meshExecutor);
        this.config = config;
    }

    /** @param edge where the rough terrain starts: the edge of the detailed ring, or of vanilla's chunks without it */
    void configure(FarPlanePayloads.Hello hello, LodView view, @Nullable DetailCoverage detailCoverage, double edge) {
        if (this.config.distant().lodDistance >= FarPlaneConstants.MAX_LOD_DISTANCE && hello.maxDistance() >= FarPlaneConstants.MAX_LOD_DISTANCE) {
            // Maximum distance means the whole world: a circle around the camera big enough to hold the entire world square.
            double reachX = FarPlaneConstants.WORLD_LIMIT + Math.abs(view.camX);
            double reachZ = FarPlaneConstants.WORLD_LIMIT + Math.abs(view.camZ);
            this.reach = Math.sqrt(reachX * reachX + reachZ * reachZ);
        } else {
            this.reach = Math.min(this.config.distant().lodDistance, hello.maxDistance());
        }
        this.minLevel = Mth.clamp(Math.max(this.config.distant().startLevel, hello.minLevel()), 0, FarPlaneConstants.MAX_LEVEL);
        this.startCell = 1 << this.minLevel;
        this.edge = edge;
        this.doubling = Mth.clamp(edge / 4.0, 64.0, 512.0);
        // Seen from high above, terrain is viewed head-on instead of at a grazing angle, so tiles need to be finer to look as sharp.
        double altitude = Math.max(0.0, view.camY - view.worldMaxY);
        double altitudeBoost = Mth.clamp(1.0 + Math.log(1.0 + altitude / 512.0) / Math.log(2.0) / 4.0, 1.0, 2.5);
        // Memory beyond the default is spent on finer tiles.
        float memoryBoost = GpuMemory.qualityBoost(this.config.distant().maxGpuTiles, DEFAULT_GPU_TILES);
        this.splitFactor = this.config.distant().sharpnessFactor() * altitudeBoost * memoryBoost * view.screenScale;
        this.detailCoverage = detailCoverage;
    }

    /**
     * Right at the edge cells have the chosen start size, then they double every {@link #doubling} blocks until the screen rule
     * (cells of about the same number of pixels everywhere) takes over. A coarse start size holds until the screen rule asks for
     * coarser cells anyway.
     */
    @Override
    boolean wantsSplit(TilePos pos, double distance) {
        double cell = pos.size() / (double) FarPlaneConstants.TILE_CELLS;
        double screenCell = distance / (this.splitFactor * FarPlaneConstants.TILE_CELLS);
        double past = Math.max(0.0, pos.distanceTo(this.view.camX, this.view.camZ) - this.edge);
        double transitionCell = this.startCell * Math.pow(2.0, past / this.doubling);
        return cell > Math.min(transitionCell, Math.max(this.startCell, screenCell));
    }

    /** Floating islands over the void (the End). */
    void setFloating(boolean floating) {
        this.floating = floating;
    }

    /**
     * Over the void a tile much coarser than wanted has one sample per island or less, and in place of its missing children it would
     * show islands as big plates right under the camera. There only tiles at most two levels coarser than wanted stand in; without
     * any stand-in, islands would vanish and come back in rings while flying.
     */
    @Override
    boolean standsInForChildren(TilePos pos, double distance) {
        return !this.floating || distance >= pos.size() * this.splitFactor / 4.0;
    }

    @Override
    int minLevel() {
        return this.minLevel;
    }

    @Override
    int maxLevel() {
        return FarPlaneConstants.MAX_LEVEL;
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
        return this.config.distant().maxGpuTiles;
    }

    /** Tiles that lie completely inside the detailed ring, where detailed tiles are already drawn, are not needed. */
    @Override
    boolean extraRelevant(TilePos pos) {
        return this.detailCoverage == null || !this.detailCoverage.coversTile(pos);
    }

    /**
     * A coarse tile can bulge above the real ground, and if the camera is below its surface the tile would hang over the player and
     * cover the sky. Only the tile right under the camera is checked, against its own surface height at the camera position.
     */
    @Override
    boolean drawable(TileMesh mesh) {
        if (this.view.camY >= mesh.maxY || mesh.pos.distanceTo(this.view.camX, this.view.camZ) > 0.0) {
            return true;
        }
        return this.view.camY >= mesh.heightAt(this.view.camX, this.view.camZ) - UNDER_SURFACE_TOLERANCE;
    }

    @Override
    boolean canSendRequests() {
        return ClientPlayNetworking.canSend(FarPlanePayloads.TileRequest.TYPE);
    }

    @Override
    void sendRequests(int[] requests) {
        ClientPlayNetworking.send(new FarPlanePayloads.TileRequest(requests));
    }

    @Override
    TileMeshBuilder.Built build(byte[] bytes, long key, int epoch, Registry<Biome> biomes, int seaLevel) throws Exception {
        TileData data = TileData.decode(bytes);
        return TileMeshBuilder.build(key, epoch, data, Arrays.asList(TileMeshBuilder.resolveBiomes(biomes, data.palette)), seaLevel, this.floating);
    }

    /**
     * Rough tiles reaching into the finished part of the detailed ring drop their fragments there, so the two never overlap. Where
     * the ring is not finished yet both are drawn, and the depth priority of the renderer lets the detailed blocks win.
     */
    void applyDetailCutout(double ringRadius, double fade) {
        if (this.detailCoverage == null) {
            return;
        }
        for (TileMesh mesh : this.drawList()) {
            if (mesh.pos.distanceTo(this.view.camX, this.view.camZ) < ringRadius - fade) {
                mesh.discardRadius = this.detailCoverage.cutoutRadius(mesh.pos);
            }
        }
    }
}
