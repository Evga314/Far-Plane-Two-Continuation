package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.tile.TilePos;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.Arrays;
import net.minecraft.util.Util;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Camera and vanilla render state for one client tick, shared by both LOD layers. Answers whether vanilla already draws an area,
 * in which case LOD tiles are not needed there.
 */
final class LodView {
    /** Columns of a chunk whose surface height is checked: the corners and the middle. */
    private static final int[] SURFACE_PROBES = {0, 0, 15, 0, 0, 15, 15, 15, 8, 8};

    private final Minecraft minecraft;
    /** Why the last chunk asked about does not count as drawn by vanilla; worded in full only while {@link #explain} is set. */
    private String notDrawn = "";
    private boolean explain;
    /** While explaining: the lowest height each heightmap gave, surface, ground and ocean floor. */
    private final int[] lowestByMap = new int[3];
    /** For the log of the debug build: why {@link #fullyDrawnRadius} ended where it did. */
    String fullyDrawnReason = "";
    /** How far down the bottom under water is looked for, see {@link #floorUnderLiquid}. */
    private static final int FLOOR_SEARCH = 64;
    private final BlockPos.MutableBlockPos floorScratch = new BlockPos.MutableBlockPos();
    /** Position of the section looked up; only used on the client thread. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    /**
     * What vanilla draws of each chunk up to {@link #gridHalf} chunks from the one at {@link #gridX}, {@link #gridZ}, row by row from
     * the north-west corner, worked out once per tick when first asked: see {@link #RENDERED} and {@link #COLUMN}. Kept from tick to
     * tick and only cleared, so asking costs no allocation.
     */
    private byte[] chunkGrid = new byte[0];
    private int gridHalf = -1;
    private int gridX;
    private int gridZ;
    /** Flags of {@link #chunkGrid}: whether each answer is known this tick, and what it is. */
    private static final byte RENDERED_KNOWN = 1;
    private static final byte RENDERED = 2;
    private static final byte COLUMN_KNOWN = 4;
    private static final byte COLUMN = 8;
    /**
     * Sodium's answers this tick of whether it has built a section, 1 yes, -1 no: the detailed tiles, the mask and the coverage ask
     * about the same sections, each through a method handle.
     */
    private final Long2ByteOpenHashMap sodiumReady = new Long2ByteOpenHashMap();
    /** Sodium's renderer this tick, or null without Sodium; then {@link #viewArea} is vanilla's. Asked for once per tick. */
    private @Nullable Object sodium;
    private @Nullable ViewArea viewArea;
    /** The view of the last frame drawn, around the camera then, see {@link #sodiumSees}; set by the renderer every frame. */
    private static final FrustumIntersection LAST_VIEW = new FrustumIntersection();
    private static boolean lastViewSet;
    private static double lastViewX;
    private static double lastViewY;
    private static double lastViewZ;
    /** Sections are tested this much bigger than they are, for the view turning a little until the next frame. */
    private static final double VIEW_MARGIN = 8.0;
    /** When each chunk the client has was first found loaded, see {@link #settled}. */
    private final Long2LongOpenHashMap loadedSince = new Long2LongOpenHashMap();
    /**
     * How long a chunk has to be loaded before a detailed tile leaves all of it to vanilla (without Sodium, see {@link #coverage})
     * even with sections not built: vanilla builds the sections it sees within moments, and those it never builds are hidden behind
     * the others. Where the rough terrain is cut away it is not enough (see sodiumCovers): there a section seen but not built yet
     * would leave a hole down to the bottom of the world.
     */
    private static final long SETTLE_MILLIS = 2000L;
    /** The chunks remembered by {@link #settled} are let go past this many, those more than this many chunks beyond vanilla's reach. */
    private static final int LOADED_SINCE_LIMIT = 65536;
    private static final int FORGET_BEYOND_CHUNKS = 8;
    /** {@link #chunkFloor} of no chunk: nothing known. */
    static final int NO_FLOOR = Integer.MIN_VALUE;
    /**
     * The floors of the chunks vanilla draws, see {@link #chunkFloor}: per chunk the tick it was worked out in (high half) and the floor
     * (low half). Worked out once and then again now and then, a few chunks a tick, so a pit dug since is caught up with soon.
     */
    private final Long2LongOpenHashMap floors = new Long2LongOpenHashMap();
    private static final int FLOOR_REFRESH_TICKS = 200;
    private static final int FLOOR_REFRESHES_PER_TICK = 4;
    /**
     * New chunks worked out per tick at most: flying fast with a long render distance many come in at once, and on a phone each costs
     * a fair share of a tick over the sea. The rest wait for the next ticks and lower the rough terrain under them only then.
     */
    private static final int NEW_FLOORS_PER_TICK = 8;
    private int floorRefreshes;
    private int newFloors;
    private long ticks;
    /** The sections the surface of a chunk lies in, see vanillaDrawsChunk; kept instead of made for every chunk. */
    private final int[] surfaceSections = new int[SURFACE_PROBES.length / 2 * 3];
    /** Sections vanilla has on screen, gathered once per tick when first needed. */
    private @Nullable ReferenceOpenHashSet<SectionRenderDispatcher.RenderSection> visibleSections;
    double camX;
    double camY;
    double camZ;
    /** Horizontal look direction, normalized; both 0 when looking straight up or down. */
    double lookX;
    double lookZ;
    double vanillaRadius;
    double vanillaDepthFar;
    /** The cloud far plane setting of the dimension the player is in, pushed by the manager every tick. */
    boolean extendCloudFarPlane;
    /**
     * How many pixels a block at a distance takes on this screen, against a 1080 pixels high window with a field of view of 70: tiles
     * get finer on bigger screens and with a narrower view. They never get coarser than on that screen.
     */
    double screenScale = 1.0;
    int worldMinY;
    int worldMaxY;

    /** Pixels per block at a distance of one block on a 1080 pixels high window with a field of view of 70. */
    private static final double REFERENCE_FOCAL = 540.0 / Math.tan(Math.toRadians(35.0));

    LodView(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /**
     * How far the far plane must reach for the whole cloud disc to stay inside it from a camera height: the slant distance to the
     * edge of the disc. The cloud layer of a vanilla dimension is never more than 192 blocks from y 0, which bounds the height
     * difference; the same formula extends the real far plane in CameraMixin.
     */
    static double cloudReach(double cloudRange, double camY) {
        double dy = Math.abs(camY) + 192.0;
        return Math.sqrt(cloudRange * cloudRange + dy * dy) * 1.02 + 32.0;
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
        double cloudRange = this.minecraft.options.cloudRange().get() * 16.0;
        double vanillaFar = Math.max(renderDistance * 16.0 * 4.0, cloudRange);
        if (this.extendCloudFarPlane) {
            // Vanilla's far plane is extended the same way (CameraMixin), so what counts as covered by vanilla grows with it.
            vanillaFar = Math.max(vanillaFar, cloudReach(cloudRange, this.camY));
        }
        this.vanillaDepthFar = 0.9 * vanillaFar;
        int height = Mth.clamp(this.minecraft.getWindow().getHeight(), 480, 1600);
        double fov = Math.toRadians(Mth.clamp(this.minecraft.options.fov().get(), 30, 110));
        this.screenScale = Math.max(1.0, height * 0.5 / Math.tan(fov * 0.5) / REFERENCE_FOCAL);
        this.worldMinY = level.getMinY();
        this.worldMaxY = level.getMaxY() + 1;
        this.sodium = SodiumCompat.isActive() ? SodiumCompat.renderer() : null;
        this.viewArea = this.sodium == null ? this.minecraft.levelRenderer.viewArea() : null;
        this.sodiumReady.clear();
        this.ticks++;
        this.floorRefreshes = FLOOR_REFRESHES_PER_TICK;
        this.newFloors = NEW_FLOORS_PER_TICK;
        int half = renderDistance + 1;
        int side = 2 * half + 1;
        if (this.chunkGrid.length != side * side) {
            this.chunkGrid = new byte[side * side];
        } else {
            Arrays.fill(this.chunkGrid, (byte) 0);
        }
        this.gridHalf = half;
        this.gridX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        this.gridZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        this.visibleSections = null;
        // Only chunks far behind are forgotten: clearing all would make every chunk around unsettled again at once, and the distant
        // terrain would show up under all of them for a moment.
        if (this.loadedSince.size() > LOADED_SINCE_LIMIT) {
            int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
            int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
            int keep = renderDistance + FORGET_BEYOND_CHUNKS;
            this.loadedSince.keySet().removeIf(key -> Math.abs(ChunkPos.getX(key) - cameraChunkX) > keep || Math.abs(ChunkPos.getZ(key) - cameraChunkZ) > keep);
        }
        if (this.floors.size() > LOADED_SINCE_LIMIT) {
            int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
            int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
            int keep = renderDistance + FORGET_BEYOND_CHUNKS;
            this.floors.keySet().removeIf(key -> Math.abs(ChunkPos.getX(key) - cameraChunkX) > keep || Math.abs(ChunkPos.getZ(key) - cameraChunkZ) > keep);
        }
    }

    /**
     * A height under every block of a chunk vanilla draws that can be seen: a block under the lowest ground of its columns, the bottom
     * under water and lava included. The rough terrain is lowered under it there (see LodManager.ChunkMask), so it never pokes out of
     * vanilla's blocks, and through any gap at the edge of what vanilla draws its slope down under them is seen instead of a hole.
     * {@link #NO_FLOOR} for a chunk the client does not have.
     */
    int chunkFloor(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        long known = this.floors.getOrDefault(key, Long.MIN_VALUE);
        if (known != Long.MIN_VALUE && (this.ticks - (known >>> 32) < FLOOR_REFRESH_TICKS || this.floorRefreshes <= 0)) {
            return (int) known;
        }
        ClientLevel level = this.minecraft.level;
        ChunkAccess chunk = level == null ? null : level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            this.floors.remove(key);
            return NO_FLOOR;
        }
        if (known != Long.MIN_VALUE) {
            this.floorRefreshes--;
        } else if (this.newFloors-- <= 0) {
            return NO_FLOOR;
        }
        int lowest = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos pos = this.floorScratch;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // The ground heightmap stops at the top of water and lava; the bottom under them is looked for, as far as FLOOR_SEARCH.
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int limit = Math.max(this.worldMinY, y - FLOOR_SEARCH);
                while (y > limit) {
                    // A section of nothing but water or lava is passed in one step.
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    if (!section.getStates().maybeHas(state -> state.getFluidState().isEmpty())) {
                        y = Math.max(limit, SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y)) - 1);
                        continue;
                    }
                    if (chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)).getFluidState().isEmpty()) {
                        break;
                    }
                    y--;
                }
                lowest = Math.min(lowest, y);
            }
        }
        int floor = lowest - 1;
        this.floors.put(key, this.ticks << 32 | floor & 0xFFFFFFFFL);
        return floor;
    }

    /**
     * The camera of the frame being drawn, for what is asked of vanilla's chunks within the frame (see LodManager.frameCheck): Sodium's
     * render distance is measured from it, and what Sodium draws in it is read (see SodiumFrame).
     */
    void frameCamera(double x, double y, double z) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.visibleSections = null;
        this.frameKnown = this.sodium != null && SodiumFrame.collect(this.sodium, this.frameSections);
    }

    void forgetFrame() {
        this.frameKnown = false;
    }

    /** The sections Sodium draws in the last frame, see {@link SodiumFrame}; valid while {@link #frameKnown}. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet frameSections = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private boolean frameKnown;

    /**
     * Whether what Sodium draws in a frame is known: then along the edge of what vanilla draws a detailed tile gives way exactly in the
     * frame Sodium starts drawing a section, not when Sodium has built it, which it may draw a frame or more later.
     */
    boolean frameKnown() {
        return this.frameKnown;
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
        int index = this.gridIndex(chunkX, chunkZ);
        if (index < 0) {
            return this.vanillaDrawsChunk(chunkX, chunkZ);
        }
        byte state = this.chunkGrid[index];
        if ((state & RENDERED_KNOWN) == 0) {
            state |= this.vanillaDrawsChunk(chunkX, chunkZ) ? RENDERED_KNOWN | RENDERED : RENDERED_KNOWN;
            this.chunkGrid[index] = state;
        }
        return (state & RENDERED) != 0;
    }

    /** Index of a chunk in {@link #chunkGrid}, -1 outside it. */
    private int gridIndex(int chunkX, int chunkZ) {
        int half = this.gridHalf;
        int side = 2 * half + 1;
        int x = chunkX - this.gridX + half;
        int z = chunkZ - this.gridZ + half;
        return half < 0 || x < 0 || z < 0 || x >= side || z >= side ? -1 : x + z * side;
    }

    /** Whether Sodium has built a section, asked once per section and tick, see {@link #sodiumReady}. */
    private boolean sodiumReady(Object sodium, int chunkX, int sectionY, int chunkZ) {
        long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
        byte known = this.sodiumReady.get(key);
        if (known != 0) {
            return known > 0;
        }
        boolean ready = SodiumCompat.isSectionReady(sodium, chunkX, sectionY, chunkZ);
        this.sodiumReady.put(key, ready ? (byte) 1 : (byte) -1);
        return ready;
    }

    /**
     * Whether vanilla really has terrain on screen for a chunk. {@code ClientLevel.hasChunk} is always true on the client, so the chunk
     * is taken from the chunk cache directly: missing chunks come back as null or as the shared empty placeholder. The section with
     * the surface block must also be built with actual geometry, an empty or not yet built section draws nothing. Vanilla also skips
     * sections more than its render distance above or below the camera, so from high up the ground is not drawn at all.
     */
    private boolean vanillaDrawsChunk(int chunkX, int chunkZ) {
        ClientLevel level = this.minecraft.level;
        Object sodium = this.sodium;
        ViewArea viewArea = this.viewArea;
        if (level == null || sodium == null && viewArea == null) {
            this.notDrawn = "no renderer";
            return false;
        }
        int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        // The client keeps chunks a little past the render distance, but vanilla only draws those inside its view distance circle.
        if (viewArea != null && !ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, viewArea.getViewDistance(), chunkX, chunkZ)) {
            this.notDrawn = "outside view distance";
            return false;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            this.loadedSince.remove(ChunkPos.pack(chunkX, chunkZ));
            this.notDrawn = "chunk not on the client";
            return false;
        }
        // The surface of a chunk can span several sections (water in a valley, trees on a hill). Vanilla has to draw the sections the
        // surface is in, otherwise hiding the LOD there would leave holes; the ones between them are under the ground.
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int[] surface = this.surfaceSections;
        int count = 0;
        int cameraSection = SectionPos.blockToSectionCoord(Mth.floor(this.camY));
        for (int i = 0; i < SURFACE_PROBES.length; i += 2) {
            int x = SURFACE_PROBES[i];
            int z = SURFACE_PROBES[i + 1];
            int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            // The ground under tree crowns and the bottom under water are part of what is seen too.
            int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int floor = this.floorUnderLiquid(chunk, x, ground, z);
            low = Math.min(low, Math.min(Math.min(height, ground), floor));
            if (this.explain) {
                this.lowestByMap[0] = Math.min(this.lowestByMap[0], height);
                this.lowestByMap[1] = Math.min(this.lowestByMap[1], ground);
                this.lowestByMap[2] = Math.min(this.lowestByMap[2], floor);
            }
            high = Math.max(high, height);
            count = addSection(surface, count, height);
            count = addSection(surface, count, ground);
            count = addSection(surface, count, floor);
        }
        if (high <= this.worldMinY) {
            return true;
        }
        int lowY = Math.max(low, this.worldMinY + 1) - 1;
        int highSection = SectionPos.blockToSectionCoord(Mth.clamp(high - 1, this.worldMinY, this.worldMaxY - 1));
        boolean drawn = true;
        int failed = 0;
        for (int i = 0; i < count && drawn; i++) {
            drawn = sodium != null
                ? this.sodiumCovers(sodium, chunkX, chunkZ, surface[i])
                : this.sectionDrawn(viewArea, cameraSection, chunkX, chunkZ, surface[i], surface[i] == highSection);
            failed = surface[i];
        }
        boolean result = drawn;
        if (!result && this.explain) {
            this.notDrawn = String.format(java.util.Locale.ROOT, "surface section %d (camera section %d) not drawn: %s, surface %d..%d",
                failed, cameraSection,
                sodium != null
                    ? "sodium in reach " + this.sodiumInReach(chunkX, chunkZ, failed) + ", built " + this.sodiumReady(sodium, chunkX, failed, chunkZ)
                    : "vanilla",
                low, high) + String.format(java.util.Locale.ROOT, ", lowest surface %d, ground %d, ocean floor %d",
                this.lowestByMap[0], this.lowestByMap[1], this.lowestByMap[2]);
        } else if (!result) {
            this.notDrawn = "surface section not drawn";
        }
        return result;
    }

    /**
     * The bottom under the water (or lava) of a column, from the top of the ground heightmap, which stops at liquids. The client has no
     * ocean floor heightmap: only the server keeps that one, on the client it is empty. Looked for at most {@link #FLOOR_SEARCH} blocks
     * down; deeper water ends there.
     */
    private int floorUnderLiquid(ChunkAccess chunk, int x, int top, int z) {
        BlockPos.MutableBlockPos pos = this.floorScratch.set(chunk.getPos().getMinBlockX() + x, top, chunk.getPos().getMinBlockZ() + z);
        int lowest = Math.max(this.worldMinY, top - FLOOR_SEARCH);
        int y = top;
        while (y > lowest && !chunk.getBlockState(pos.setY(y)).getFluidState().isEmpty()) {
            y--;
        }
        return y;
    }

    /** Adds the section holding the block under a surface height to the list once. */
    private int addSection(int[] sections, int count, int height) {
        int section = SectionPos.blockToSectionCoord(Mth.clamp(Math.max(height, this.worldMinY + 1) - 1, this.worldMinY, this.worldMaxY - 1));
        for (int i = 0; i < count; i++) {
            if (sections[i] == section) {
                return count;
            }
        }
        sections[count] = section;
        return count + 1;
    }

    /** Whether a chunk the client has has been loaded for a while, see {@link #SETTLE_MILLIS}. */
    private boolean settled(int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        long now = Util.getMillis();
        if (!this.loadedSince.containsKey(key)) {
            this.loadedSince.put(key, now);
            return false;
        }
        return now - this.loadedSince.get(key) >= SETTLE_MILLIS;
    }

    /** Whether the section at a height of a chunk is within the reach vanilla (or Sodium) draws sections in. */
    private boolean inReach(@Nullable Object sodium, @Nullable ViewArea viewArea, int chunkX, int chunkZ, int y) {
        int sectionY = SectionPos.blockToSectionCoord(Mth.clamp(y, this.worldMinY, this.worldMaxY - 1));
        if (sodium != null) {
            return this.sodiumInReach(chunkX, chunkZ, sectionY);
        }
        return viewArea != null && Math.abs(sectionY - SectionPos.blockToSectionCoord(Mth.floor(this.camY))) <= viewArea.getViewDistance();
    }

    /** How much of what a detailed tile has in a chunk vanilla draws itself. */
    enum Coverage {
        /** Nothing, or not yet: the tile draws all of the chunk. */
        NONE,
        /** Only some sections, see {@link #drawnSections} and {@link #lowestSection}. */
        PARTIAL,
        /** All of it: the tile leaves the chunk out. */
        FULL
    }

    /** Sections the last {@link #coverage} found drawn by vanilla, one bit each from {@link #lowestSection} up. */
    int drawnSections;
    int lowestSection;

    /**
     * Whether vanilla draws the blocks of a chunk between two heights, the ones a detailed tile shows there, section by section.
     * Vanilla draws sections up to its render distance above and below the camera, and builds only those it can see: seen from high
     * up, an End city is drawn but the island under it is not; the ground under water may be out of reach while the water is drawn.
     * The tile keeps such a chunk and leaves out exactly the sections vanilla has built.
     */
    Coverage coverage(int chunkX, int chunkZ, int lowY, int highY) {
        return this.coverage(chunkX, chunkZ, lowY, highY, false);
    }

    /** {@code byFrame}: with Sodium, a section counts as drawn when Sodium draws it in this frame, see {@link #frameKnown}. */
    Coverage coverage(int chunkX, int chunkZ, int lowY, int highY, boolean byFrame) {
        boolean frame = byFrame && this.frameKnown;
        this.drawnSections = 0;
        this.lowestSection = 0;
        if (lowY > highY) {
            return Coverage.FULL;
        }
        ClientLevel level = this.minecraft.level;
        Object sodium = this.sodium;
        ViewArea viewArea = this.viewArea;
        if (level == null || sodium == null && viewArea == null) {
            return Coverage.NONE;
        }
        int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        if (viewArea != null && !ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, viewArea.getViewDistance(), chunkX, chunkZ)) {
            return Coverage.NONE;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            return Coverage.NONE;
        }
        // Without Sodium the loop below reads vanilla's real built sections, and a settled chunk only skips it. Sodium does not build
        // sections it cannot see, the seabed under water seen from high up for example: a settled chunk would drop them from the tile
        // with nothing drawn there, so with Sodium every section is asked.
        if (sodium == null && this.settled(chunkX, chunkZ) && this.inReach(null, viewArea, chunkX, chunkZ, lowY) && this.inReach(null, viewArea, chunkX, chunkZ, highY)) {
            return Coverage.FULL;
        }
        int cameraSection = SectionPos.blockToSectionCoord(Mth.floor(this.camY));
        int lowSection = SectionPos.blockToSectionCoord(Mth.clamp(lowY, this.worldMinY, this.worldMaxY - 1));
        int highSection = Math.min(SectionPos.blockToSectionCoord(Mth.clamp(highY, this.worldMinY, this.worldMaxY - 1)), lowSection + 23);
        int mask = 0;
        boolean all = true;
        for (int sectionY = lowSection; sectionY <= highSection; sectionY++) {
            boolean drawn = sodium != null
                ? frame
                    ? this.sodiumInReach(chunkX, chunkZ, sectionY) && this.frameSections.contains(SectionPos.asLong(chunkX, sectionY, chunkZ))
                    : this.sodiumSectionDrawn(sodium, chunkX, chunkZ, sectionY)
                : this.sectionBuilt(viewArea, cameraSection, chunkX, chunkZ, sectionY, sectionY == highSection);
            if (drawn) {
                mask |= 1 << (sectionY - lowSection);
            } else {
                all = false;
            }
        }
        this.drawnSections = mask;
        this.lowestSection = lowSection;
        return all ? Coverage.FULL : mask != 0 ? Coverage.PARTIAL : Coverage.NONE;
    }

    /** Whether vanilla has built a section within its reach; the top section must also have something in it. */
    private boolean sectionBuilt(ViewArea viewArea, int cameraSection, int chunkX, int chunkZ, int sectionY, boolean mustHaveBlocks) {
        if (Math.abs(sectionY - cameraSection) > viewArea.getViewDistance()) {
            return false;
        }
        SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(this.scratch.set(chunkX * 16 + 8, sectionY * 16 + 8, chunkZ * 16 + 8));
        if (section == null) {
            return false;
        }
        SectionMesh mesh = section.sectionMesh.get();
        return mesh != CompiledSectionMesh.UNCOMPILED && (!mustHaveBlocks || mesh != CompiledSectionMesh.EMPTY);
    }

    /**
     * Whether a section is within Sodium's render distance, measured the way Sodium does (OcclusionCuller and its section trees): from
     * the camera to the nearest point of the section grown by a block on every side, horizontally inside the circle of the distance and
     * no farther above or below than it. The distance is the render distance, or with an opaque fog the end of the fog and half a block
     * if that is nearer (Sodium's fog occlusion, on by default). Sodium culls sections past it, built or not; one counted out here while
     * Sodium still draws it would be drawn twice, by Sodium and by the detailed tile, and the two flicker.
     */
    private boolean sodiumInReach(int chunkX, int chunkZ, int sectionY) {
        double limit = Math.min(this.minecraft.options.getEffectiveRenderDistance() * 16.0, LodRenderer.fogCullDistance + 0.5);
        double dx = Math.max(0.0, Math.max(chunkX * 16.0 - 1.0 - this.camX, this.camX - (chunkX * 16.0 + 17.0)));
        double dz = Math.max(0.0, Math.max(chunkZ * 16.0 - 1.0 - this.camZ, this.camZ - (chunkZ * 16.0 + 17.0)));
        double dy = Math.max(0.0, Math.max(sectionY * 16.0 - 1.0 - this.camY, this.camY - (sectionY * 16.0 + 17.0)));
        return dx * dx + dz * dz < limit * limit && dy < limit;
    }

    /**
     * Whether Sodium draws a section when it is in view: built and within its render distance. Sodium has no way to ask which
     * sections it has on screen, so a built section counts as drawn.
     */
    /**
     * Whether a section within Sodium's reach needs nothing drawn in its place: built, or not built because nobody sees it (out of
     * view). A section Sodium sees but has not built yet, while it catches up flying over new land or
     * as a hill stops hiding it, is left to the LOD; counted as drawn, the LOD would be cut away there over nothing.
     */
    private boolean sodiumCovers(Object sodium, int chunkX, int chunkZ, int sectionY) {
        return this.sodiumInReach(chunkX, chunkZ, sectionY)
            && (this.sodiumReady(sodium, chunkX, sectionY, chunkZ) || !sodiumSees(chunkX, sectionY, chunkZ));
    }

    /**
     * Whether a section Sodium has not built may be on screen: in the view of the last frame. Sodium cannot tell, it only knows the
     * sections it has; one behind a hill counts as seen too, and the LOD there stays under the blocks, hidden by the same hill.
     */
    private static boolean sodiumSees(int chunkX, int sectionY, int chunkZ) {
        if (!lastViewSet) {
            return true;
        }
        float x = (float) (chunkX * 16.0 - lastViewX - VIEW_MARGIN);
        float y = (float) (sectionY * 16.0 - lastViewY - VIEW_MARGIN);
        float z = (float) (chunkZ * 16.0 - lastViewZ - VIEW_MARGIN);
        float size = (float) (16.0 + 2.0 * VIEW_MARGIN);
        return LAST_VIEW.testAab(x, y, z, x + size, y + size, z + size);
    }

    /** The view of the frame being drawn: its culling matrix, around the camera at {@code x, y, z}. Render thread only. */
    static void lastView(Matrix4fc cull, double x, double y, double z) {
        LAST_VIEW.set(cull, false);
        lastViewX = x;
        lastViewY = y;
        lastViewZ = z;
        lastViewSet = true;
    }

    private boolean sodiumSectionDrawn(Object sodium, int chunkX, int chunkZ, int sectionY) {
        return this.sodiumInReach(chunkX, chunkZ, sectionY) && this.sodiumReady(sodium, chunkX, sectionY, chunkZ);
    }

    /**
     * Whether vanilla takes care of a section: close enough in height and built, the top section also with something in it. Vanilla
     * only builds sections it can see from the camera, through air; a section that cannot be seen at all is never built, and needs
     * nothing drawn in its place either. Only sections vanilla can see but has not built yet are left to the LOD.
     */
    private boolean sectionDrawn(ViewArea viewArea, int cameraSection, int chunkX, int chunkZ, int sectionY, boolean mustHaveBlocks) {
        if (Math.abs(sectionY - cameraSection) > viewArea.getViewDistance()) {
            return false;
        }
        SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(this.scratch.set(chunkX * 16 + 8, sectionY * 16 + 8, chunkZ * 16 + 8));
        if (section == null) {
            return false;
        }
        SectionMesh mesh = section.sectionMesh.get();
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            return !this.seenByVanilla(section);
        }
        return !mustHaveBlocks || mesh != CompiledSectionMesh.EMPTY;
    }

    /**
     * Whether vanilla has a section on screen this frame: in view and not hidden behind other blocks. Vanilla builds those first; the
     * others it builds only once they come into view, until then nobody sees them.
     */
    private boolean seenByVanilla(SectionRenderDispatcher.RenderSection section) {
        if (this.visibleSections == null) {
            try {
                this.visibleSections = new ReferenceOpenHashSet<>(this.minecraft.levelRenderer.visibleSections());
            } catch (RuntimeException e) {
                return true;
            }
        }
        return this.visibleSections.contains(section);
    }

    /**
     * Which chunks up to {@code half} chunks from the camera's vanilla draws, 1 each, row by row from the north-west corner. The rough
     * terrain is left out there: under vanilla's translucent water its opaque water and the skirts of its tiles would show.
     */
    byte[] chunkMask(int half, byte[] reuse) {
        int side = 2 * half + 1;
        int centerX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int centerZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        byte[] mask = reuse.length == side * side ? reuse : new byte[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                mask[x + z * side] = this.columnDrawn(centerX + x - half, centerZ + z - half) ? (byte) 1 : (byte) 0;
            }
        }
        return mask;
    }

    /** {@link #drawsColumn}, worked out once per chunk and tick. */
    private boolean columnDrawn(int chunkX, int chunkZ) {
        int index = this.gridIndex(chunkX, chunkZ);
        if (index < 0) {
            return this.drawsColumn(chunkX, chunkZ);
        }
        byte state = this.chunkGrid[index];
        if ((state & COLUMN_KNOWN) == 0) {
            state |= this.drawsColumn(chunkX, chunkZ) ? COLUMN_KNOWN | COLUMN : COLUMN_KNOWN;
            this.chunkGrid[index] = state;
        }
        return (state & COLUMN) != 0;
    }

    /**
     * Whether vanilla draws a chunk within its reach in height, for {@link #chunkMask}: the rough terrain is left out there only up to
     * that reach above and below the camera (see the shader), so sections out of it do not count. The chunk must be in the render
     * distance and loaded, and the sections of its surface within reach built, or not built because nobody sees them.
     */
    private boolean drawsColumn(int chunkX, int chunkZ) {
        ClientLevel level = this.minecraft.level;
        Object sodium = this.sodium;
        ViewArea viewArea = this.viewArea;
        if (level == null || sodium == null && viewArea == null) {
            return false;
        }
        int cameraSection = SectionPos.blockToSectionCoord(Mth.floor(this.camY));
        if (viewArea != null) {
            int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
            int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
            if (!ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, viewArea.getViewDistance(), chunkX, chunkZ)) {
                return false;
            }
        } else if (!this.sodiumInReach(chunkX, chunkZ, cameraSection)) {
            return false;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            this.loadedSince.remove(ChunkPos.pack(chunkX, chunkZ));
            this.floors.remove(ChunkPos.pack(chunkX, chunkZ));
            return false;
        }
        for (int i = 0; i < SURFACE_PROBES.length; i += 2) {
            int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, SURFACE_PROBES[i], SURFACE_PROBES[i + 1]);
            int sectionY = SectionPos.blockToSectionCoord(Mth.clamp(Math.max(height, this.worldMinY + 1) - 1, this.worldMinY, this.worldMaxY - 1));
            boolean inReach = sodium != null
                ? this.sodiumInReach(chunkX, chunkZ, sectionY)
                : Math.abs(sectionY - cameraSection) <= viewArea.getViewDistance();
            if (inReach && !(sodium != null
                ? this.sodiumReady(sodium, chunkX, sectionY, chunkZ) || !sodiumSees(chunkX, sectionY, chunkZ)
                : this.sectionDrawn(viewArea, cameraSection, chunkX, chunkZ, sectionY, true))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Radius around the camera in which vanilla draws every chunk: up to the nearest chunk it does not draw, within its render
     * distance. Rough terrain only guesses the surface and would show through vanilla water, leaves and glass there, or poke out of
     * the blocks, so it is left out inside it.
     */
    double fullyDrawnRadius(int renderDistance) {
        int centerX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int centerZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        double radius = Math.max(0, renderDistance - 1) * 16.0;
        int nearestX = Integer.MIN_VALUE;
        int nearestZ = 0;
        for (int dz = -renderDistance; dz <= renderDistance; dz++) {
            for (int dx = -renderDistance; dx <= renderDistance; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                double nearX = Math.max(0.0, Math.max(chunkX * 16.0 - this.camX, this.camX - (chunkX * 16.0 + 16.0)));
                double nearZ = Math.max(0.0, Math.max(chunkZ * 16.0 - this.camZ, this.camZ - (chunkZ * 16.0 + 16.0)));
                double near = Math.sqrt(nearX * nearX + nearZ * nearZ);
                if (near < radius && !this.chunkRendered(chunkX, chunkZ)) {
                    radius = near;
                    nearestX = chunkX;
                    nearestZ = chunkZ;
                }
            }
        }
        if (PerfLog.ON) {
            // Why the nearest chunk does not count as drawn, asked again with an explanation, for the log.
            this.fullyDrawnReason = "all drawn";
            if (nearestX != Integer.MIN_VALUE) {
                this.explain = true;
                Arrays.fill(this.lowestByMap, Integer.MAX_VALUE);
                this.vanillaDrawsChunk(nearestX, nearestZ);
                this.explain = false;
                this.fullyDrawnReason = "chunk " + (nearestX - centerX) + "," + (nearestZ - centerZ) + " from the camera: " + this.notDrawn;
            }
        }
        return radius;
    }

    /**
     * Whether the client has the real blocks of a chunk and vanilla's render distance covers it with a chunk to spare: its blocks are
     * on screen, or will be as soon as vanilla has built them.
     */
    boolean vanillaHasChunk(int chunkX, int chunkZ) {
        ClientLevel level = this.minecraft.level;
        if (level == null) {
            return false;
        }
        int cameraChunkX = SectionPos.blockToSectionCoord(Mth.floor(this.camX));
        int cameraChunkZ = SectionPos.blockToSectionCoord(Mth.floor(this.camZ));
        int distance = this.minecraft.options.getEffectiveRenderDistance() - 1;
        if (distance < 1 || !ChunkTrackingView.isInViewDistance(cameraChunkX, cameraChunkZ, distance, chunkX, chunkZ)) {
            return false;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk instanceof LevelChunk && !(chunk instanceof EmptyLevelChunk);
    }
}
