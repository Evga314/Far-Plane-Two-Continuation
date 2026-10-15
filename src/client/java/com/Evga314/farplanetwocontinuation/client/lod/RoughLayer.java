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
    private @Nullable DetailCoverage detailCoverage;

    RoughLayer(ClientConfig config, Executor meshExecutor) {
        super("rough", meshExecutor);
        this.config = config;
    }

    void configure(FarPlanePayloads.Hello hello, LodView view, @Nullable DetailCoverage detailCoverage) {
        if (this.config.lodDistance >= FarPlaneConstants.MAX_LOD_DISTANCE && hello.maxDistance() >= FarPlaneConstants.MAX_LOD_DISTANCE) {
            // Maximum distance means the whole world: a circle around the camera big enough to hold the entire world square.
            double reachX = FarPlaneConstants.WORLD_LIMIT + Math.abs(view.camX);
            double reachZ = FarPlaneConstants.WORLD_LIMIT + Math.abs(view.camZ);
            this.reach = Math.sqrt(reachX * reachX + reachZ * reachZ);
        } else {
            this.reach = Math.min(this.config.lodDistance, hello.maxDistance());
        }
        this.minLevel = Mth.clamp(Math.max(this.config.detailLevel, hello.minLevel()), 0, FarPlaneConstants.MAX_LEVEL);
        // Seen from high above, terrain is viewed head-on instead of at a grazing angle, so tiles need to be finer to look as sharp.
        double altitude = Math.max(0.0, view.camY - view.worldMaxY);
        double altitudeBoost = Mth.clamp(1.0 + Math.log(1.0 + altitude / 512.0) / Math.log(2.0) / 4.0, 1.0, 2.5);
        // Memory beyond the default is spent on finer tiles.
        float memoryBoost = GpuMemory.qualityBoost(this.config.maxGpuTiles, DEFAULT_GPU_TILES);
        this.splitFactor = this.config.quality * altitudeBoost * memoryBoost;
        this.detailCoverage = detailCoverage;
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
        return this.config.maxGpuTiles;
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
        return TileMeshBuilder.build(key, epoch, data, Arrays.asList(TileMeshBuilder.resolveBiomes(biomes, data.palette)), seaLevel);
    }

    /**
     * Rough tiles reaching into the finished part of the detailed ring drop their fragments there, so the two never overlap. Where
     * the ring is not finished yet both are drawn, and the depth priority of the renderer lets the detailed blocks win.
     */
    void applyDetailCutout(double ringRadius) {
        if (this.detailCoverage == null) {
            return;
        }
        for (TileMesh mesh : this.drawList()) {
            if (mesh.pos.distanceTo(this.view.camX, this.view.camZ) < ringRadius) {
                mesh.discardRadius = this.detailCoverage.cutoutRadius(mesh.pos);
            }
        }
    }
}
