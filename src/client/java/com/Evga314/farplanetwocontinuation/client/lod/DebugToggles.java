package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Parts of the rough terrain the debug build can turn off, or mark, to find what draws something: each one while a file of its name
 * exists in the config folder, which can be created and removed while the game runs. The files are looked for once a second; when
 * one comes or goes, the tiles are built again.
 */
final class DebugToggles {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Paints the samples on tile edges, which the next tile shares, in the debug color. */
    static volatile boolean tileEdges;
    /** Water next to land takes no borders (snow, forests, biomes) from it, see TileMeshBuilder.mergeCoasts. */
    static volatile boolean noCoastBorders;
    static volatile boolean noForestEdges;
    static volatile boolean noBiomeBorders;
    static volatile boolean noSnowLine;
    /** Coasts are neither cut back nor fitted to the smoothed line, see TileMeshBuilder.shapeCoasts. */
    static volatile boolean noCoastSmoothing;
    /** Distances to borders are searched sample by sample as before the distance transform, to compare, see TileMeshBuilder. */
    static volatile boolean oldDistances;
    /** Ice and the temperatures of water make one border as before, see TileMeshBuilder.shapeWaters. */
    static volatile boolean oldIce;
    /** Paints the samples where two borders meet and both stay blended (blocked, see TileMeshBuilder.shapeBorders). */
    static volatile boolean markBlocked;
    /** Paints what the rough terrain draws under and around vanilla's chunks, see the shader. */
    static volatile boolean markVanillaChunks;
    /** Leaves out the water drawn under vanilla's and the detailed tiles' water, see LodRenderer.UNDER_DETAIL_PIPELINE. */
    static volatile boolean noPatch;
    /** Vanilla's chunks along its edge are only asked once a tick, not in every frame, see LodManager.frameMask. */
    static volatile boolean noFrameMask;
    /** The LOD terrain keeps its own pass instead of being drawn in vanilla's main pass, see LodRenderer.mainPassPrototype. */
    static volatile boolean noMainPass;

    /** Builds the new renderer's mesh beside the old one and logs what it costs, see SurfaceMeshBuilder. */
    static volatile boolean surfaceMesh;
    /** Draws the rough tiles with the new renderer's mesh and pass (stage 3 of the rewrite), see OwnPassOutput. */
    static volatile boolean surface;
    /** Tints the ring the new renderer draws under vanilla's edge, see OwnershipMap. */
    static volatile boolean markRing;

    private static long checkedAt;

    private DebugToggles() {
    }

    /** Looks for the files once a second; returns whether anything changed, so the tiles have to be built again. */
    static boolean refresh() {
        if (!PerfLog.ON) {
            return false;
        }
        long now = System.nanoTime();
        if (now - checkedAt < 1_000_000_000L) {
            return false;
        }
        checkedAt = now;
        Path config = FabricLoader.getInstance().getConfigDir();
        boolean changed = false;
        changed |= tileEdges != (tileEdges = check(config, "farplane-debug-tile-edges"));
        changed |= noCoastBorders != (noCoastBorders = check(config, "farplane-debug-no-coast-borders"));
        changed |= noForestEdges != (noForestEdges = check(config, "farplane-debug-no-forest-edges"));
        changed |= noBiomeBorders != (noBiomeBorders = check(config, "farplane-debug-no-biome-borders"));
        changed |= noSnowLine != (noSnowLine = check(config, "farplane-debug-no-snow-line"));
        changed |= noCoastSmoothing != (noCoastSmoothing = check(config, "farplane-debug-no-coast-smoothing"));
        changed |= oldDistances != (oldDistances = check(config, "farplane-debug-old-distances"));
        changed |= oldIce != (oldIce = check(config, "farplane-debug-old-ice"));
        changed |= markBlocked != (markBlocked = check(config, "farplane-debug-mark-blocked"));
        changed |= markVanillaChunks != (markVanillaChunks = check(config, "farplane-debug-mark-vanilla-chunks"));
        changed |= noPatch != (noPatch = check(config, "farplane-debug-no-patch"));
        changed |= surfaceMesh != (surfaceMesh = check(config, "farplane-debug-surface-mesh"));
        changed |= surface != (surface = check(config, "farplane-debug-surface"));
        // These two need no tiles built again.
        boolean frameMask = noFrameMask != (noFrameMask = check(config, "farplane-debug-no-frame-mask"));
        boolean mainPass = noMainPass != (noMainPass = check(config, "farplane-debug-no-main-pass"));
        markRing = check(config, "farplane-debug-mark-ring");
        if (frameMask || mainPass) {
            LOGGER.info("[FarPlane debug] chunks along vanilla's edge asked every frame {}, LOD terrain in vanilla's main pass {}", !noFrameMask, !noMainPass);
        }
        if (changed) {
            LOGGER.info("[FarPlane debug] tile edges marked {}, coast borders {}, forest edges {}, biome borders {}, snow line {}, coast smoothing {}, old distances {}, old ice {}, blocked marked {}, vanilla chunks marked {}, patch {}, surface mesh {}, new renderer {}",
                tileEdges, !noCoastBorders, !noForestEdges, !noBiomeBorders, !noSnowLine, !noCoastSmoothing, oldDistances, oldIce, markBlocked, markVanillaChunks, !noPatch, surfaceMesh, surface);
        }
        return changed;
    }

    private static boolean check(Path config, String name) {
        return Files.exists(config.resolve(name));
    }
}
