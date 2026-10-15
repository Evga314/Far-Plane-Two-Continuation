package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.tile.TilePos;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Camera and vanilla render state for one client tick, shared by both LOD layers. Answers whether vanilla already draws an area,
 * in which case LOD tiles are not needed there.
 */
final class LodView {
    /** Columns of a chunk whose surface height is checked: the corners and the middle. */
    private static final int[] SURFACE_PROBES = {0, 0, 15, 0, 0, 15, 15, 15, 8, 8};

    private final Minecraft minecraft;
    /** Per-tick cache of whether vanilla already draws a chunk: 1 yes, -1 no. */
    private final Long2ByteOpenHashMap chunkStates = new Long2ByteOpenHashMap();
    double camX;
    double camY;
    double camZ;
    /** Horizontal look direction, normalized; both 0 when looking straight up or down. */
    double lookX;
    double lookZ;
    double vanillaRadius;
    double vanillaDepthFar;
    int worldMinY;
    int worldMaxY;

    LodView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    void update(ClientLevel level) {
        Vec3 camera = this.minecraft.gameRenderer.mainCamera().position();
        this.camX = camera.x;
        this.camY = camera.y;
        this.camZ = camera.z;
        Vector3fc forward = this.minecraft.gameRenderer.mainCamera().forwardVector();
        double horizontal = Math.sqrt(forward.x() * forward.x() + forward.z() * forward.z());
        this.lookX = horizontal > 0.05 ? forward.x() / horizontal : 0.0;
        this.lookZ = horizontal > 0.05 ? forward.z() / horizontal : 0.0;
        int renderDistance = this.minecraft.options.getEffectiveRenderDistance();
        this.vanillaRadius = Math.max(0, renderDistance - 1) * 16.0;
        this.vanillaDepthFar = 0.9 * Math.max(renderDistance * 16.0 * 4.0, this.minecraft.options.cloudRange().get() * 16.0);
        this.worldMinY = level.getMinY();
        this.worldMaxY = level.getMaxY() + 1;
        this.chunkStates.clear();
    }

    /** Vertical distance to the whole build height, used when nothing better is known about a tile. */
    double fallbackVertical(boolean farthest) {
        return farthest
            ? Math.max(Math.abs(this.camY - this.worldMinY), Math.abs(this.camY - this.worldMaxY))
            : Math.max(0.0, Math.max(this.camY - this.worldMaxY, this.worldMinY - this.camY));
    }

    /**
     * Vanilla draws sections within its horizontal render distance, but its projection ends at {@code Camera.depthFar}, so the ground
     * disappears when the camera flies high enough. An area is left to vanilla only while all of it is inside both limits and every
     * chunk in it is actually drawn.
     */
    boolean coveredByVanilla(TilePos pos, double verticalFarthest) {
        double horizontal = pos.farthestDistanceTo(this.camX, this.camZ);
        if (horizontal >= this.vanillaRadius) {
            return false;
        }
        if (horizontal * horizontal + verticalFarthest * verticalFarthest >= this.vanillaDepthFar * this.vanillaDepthFar) {
            return false;
        }
        int minChunkX = (int) Math.floorDiv(pos.minX(), 16L);
        int minChunkZ = (int) Math.floorDiv(pos.minZ(), 16L);
        int chunks = Math.max(1, pos.size() / 16);
        for (int dz = 0; dz < chunks; dz++) {
            for (int dx = 0; dx < chunks; dx++) {
                if (!this.chunkRendered(minChunkX + dx, minChunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Vanilla needs time to load and build chunks, especially right after joining or while flying fast. Until every chunk of a tile
     * is drawn by vanilla, the LOD tile fills the gap; vanilla terrain is drawn over it wherever it already exists. This holds right
     * under the player too: when flying fast those chunks are just as likely to be missing.
     */
    boolean chunkRendered(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        byte cached = this.chunkStates.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        boolean rendered = this.vanillaDrawsChunk(chunkX, chunkZ);
        this.chunkStates.put(key, rendered ? (byte) 1 : (byte) -1);
        return rendered;
    }

    /**
     * Whether vanilla really has terrain on screen for a chunk. {@code ClientLevel.hasChunk} is always true on the client, so the chunk
     * is taken from the chunk cache directly: missing chunks come back as null or as the shared empty placeholder. The section with
     * the surface block must also be built with actual geometry, an empty or not yet built section draws nothing. Vanilla also skips
     * sections more than its render distance above or below the camera, so from high up the ground is not drawn at all.
     */
    private boolean vanillaDrawsChunk(int chunkX, int chunkZ) {
        ClientLevel level = this.minecraft.level;
        Object sodium = SodiumCompat.isActive() ? SodiumCompat.renderer() : null;
        ViewArea viewArea = sodium == null ? this.minecraft.levelRenderer.viewArea() : null;
        if (level == null || sodium == null && viewArea == null) {
            return false;
        }
        int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        // The client keeps chunks a little past the render distance, but vanilla only draws those inside its view distance circle.
        if (viewArea != null && !ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, viewArea.getViewDistance(), chunkX, chunkZ)) {
            return false;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            return false;
        }
        // The surface of a chunk can span several sections (water in a valley, trees on a hill). Vanilla has to draw the lowest and
        // the highest of them, otherwise hiding the LOD there would leave holes.
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < SURFACE_PROBES.length; i += 2) {
            int x = SURFACE_PROBES[i];
            int z = SURFACE_PROBES[i + 1];
            int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            // The ground under tree crowns is part of what is seen too.
            int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            low = Math.min(low, Math.min(height, ground));
            high = Math.max(high, height);
        }
        if (high <= this.worldMinY) {
            return true;
        }
        int cameraSection = SectionPos.blockToSectionCoord(Mth.floor(this.camY));
        int lowSection = SectionPos.blockToSectionCoord(Mth.clamp(Math.max(low, this.worldMinY + 1) - 1, this.worldMinY, this.worldMaxY - 1));
        int highSection = SectionPos.blockToSectionCoord(Mth.clamp(high - 1, this.worldMinY, this.worldMaxY - 1));
        // Every section from the lowest ground to the highest top has to be drawn by vanilla, or hiding the LOD leaves a hole.
        for (int sectionY = lowSection; sectionY <= highSection; sectionY++) {
            boolean drawn = sodium != null
                ? this.sodiumSectionDrawn(sodium, chunkX, chunkZ, sectionY)
                : this.sectionDrawn(viewArea, cameraSection, chunkX, chunkZ, sectionY, sectionY == highSection);
            if (!drawn) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether Sodium draws a section: built, and all of it well inside the render distance around the camera. Sodium culls by its own
     * distance rules, so the check stays on the safe side; where it says no, the LOD terrain just stays under Sodium's blocks.
     */
    private boolean sodiumSectionDrawn(Object sodium, int chunkX, int chunkZ, int sectionY) {
        double limit = Math.max(0, this.minecraft.options.getEffectiveRenderDistance() - 1) * 16.0;
        double dx = Math.max(Math.abs(chunkX * 16.0 - this.camX), Math.abs(chunkX * 16.0 + 16.0 - this.camX));
        double dy = Math.max(Math.abs(sectionY * 16.0 - this.camY), Math.abs(sectionY * 16.0 + 16.0 - this.camY));
        double dz = Math.max(Math.abs(chunkZ * 16.0 - this.camZ), Math.abs(chunkZ * 16.0 + 16.0 - this.camZ));
        if (dx * dx + dy * dy + dz * dz > limit * limit) {
            return false;
        }
        return SodiumCompat.isSectionReady(sodium, chunkX, sectionY, chunkZ);
    }

    /** Whether vanilla draws a section: close enough in height and built. The top section must also have something in it. */
    private boolean sectionDrawn(ViewArea viewArea, int cameraSection, int chunkX, int chunkZ, int sectionY, boolean mustHaveBlocks) {
        if (Math.abs(sectionY - cameraSection) > viewArea.getViewDistance()) {
            return false;
        }
        SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(new BlockPos(chunkX * 16 + 8, sectionY * 16 + 8, chunkZ * 16 + 8));
        if (section == null) {
            return false;
        }
        SectionMesh mesh = section.sectionMesh.get();
        return mesh != CompiledSectionMesh.UNCOMPILED && (!mustHaveBlocks || mesh != CompiledSectionMesh.EMPTY);
    }

    /**
     * Radius around the camera in which vanilla draws every chunk, found ring by ring from the camera outwards. Rough terrain only
     * guesses the surface and would show through vanilla water, leaves and glass there, so it is left out inside it.
     */
    double fullyDrawnRadius(int renderDistance) {
        int centerX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int centerZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        int ring = 0;
        outer:
        for (; ring <= renderDistance; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if ((Math.abs(dx) == ring || Math.abs(dz) == ring) && !this.chunkRendered(centerX + dx, centerZ + dz)) {
                        break outer;
                    }
                }
            }
        }
        // Every chunk closer than the first ring with a missing one is drawn; the camera can be anywhere in its own chunk.
        return Math.max(0, ring - 1) * 16.0;
    }
}
