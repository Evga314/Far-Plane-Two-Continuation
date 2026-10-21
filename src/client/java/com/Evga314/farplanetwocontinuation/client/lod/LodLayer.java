package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.core.Registry;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One kind of LOD tiles around the camera. Every tick it walks a quadtree, decides which tiles are drawn, asks the server for
 * missing ones and keeps its GPU memory under the budget. Everything except mesh building runs on the client thread.
 */
abstract class LodLayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long REQUEST_TIMEOUT_MS = 20_000L;
    private static final int MAX_BUILD_BACKLOG = 384;
    /** Refreshes only go out when nothing is missing, so they never hold back tiles that are not loaded at all. */
    private static final double REFRESH_PRIORITY = 1.0E9;
    /** Refreshes sent per tick at most, so keeping tiles fresh stays a light background load on the server. */
    private static final int MAX_REFRESHES_PER_TICK = 8;
    /** A tile whose blocks changed is loaded again at most this often, so building next to it does not reload it all the time. */
    private static final long CHANGED_REFRESH_MS = 3_000L;
    /**
     * Share of the GPU budget given to tiles on screen. The rest is room for hidden parents kept as fallback and for tiles that are
     * still loading.
     */
    static final double LEAF_SHARE = 0.6;
    private static final int MIN_LEAVES = 16;
    /** Meshes are written to the GPU in parts of this size, one or more per frame. */
    private static final int UPLOAD_PART_BYTES = 256 * 1024;

    private final String name;
    private final Executor meshExecutor;
    final Long2ObjectOpenHashMap<TileMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap pending = new Long2LongOpenHashMap();
    private final LongOpenHashSet building = new LongOpenHashSet();
    /**
     * Tiles the server answered with nothing to draw (for example chunks that are not generated yet), with the time of the answer.
     * They count as loaded until they are due again; without this they would be asked for again every tick.
     */
    private final Long2LongOpenHashMap empty = new Long2LongOpenHashMap();
    private final ConcurrentLinkedQueue<TileMeshBuilder.Built> built = new ConcurrentLinkedQueue<>();
    /** Meshes replaced by fresher data. They may still be in this tick's draw list, so they are closed on the next tick. */
    private final List<TileMesh> retired = new ArrayList<>();
    private List<TileMesh> drawList = List.of();
    private int epoch;
    private @Nullable PartialUpload partial;
    /** Tiles asked for and tiles dropped for memory since the start, for the debug screen: both grow steadily when tiles thrash. */
    private long requested;
    private long evicted;

    private final ArrayList<TileMesh> nextDrawList = new ArrayList<>();
    private final LongOpenHashSet allowedSplits = new LongOpenHashSet();
    private final Long2ByteOpenHashMap readyStates = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap relevantStates = new Long2ByteOpenHashMap();
    private final ArrayList<Candidate> candidates = new ArrayList<>();
    protected LodView view;
    protected long tick;
    private long now;

    LodLayer(String name, Executor meshExecutor) {
        this.name = name;
        this.meshExecutor = meshExecutor;
    }

    /** Finest level drawn right now. */
    abstract int minLevel();

    /** Coarsest level this layer has; also the upper bound accepted from the server. */
    abstract int maxLevel();

    /** Horizontal distance from the camera within which tiles are wanted. */
    abstract double reach();

    abstract double splitFactor();

    /** How many meshes may stay on the GPU. */
    abstract int maxMeshes();

    /** Whether a tile at this distance is too coarse and should be split into four finer ones, budget permitting. */
    boolean wantsSplit(TilePos pos, double distance) {
        return distance < this.splitFactor() * pos.size();
    }

    /** Extra reasons to leave a tile out, on top of distance, world bounds and vanilla coverage. */
    boolean extraRelevant(TilePos pos) {
        return true;
    }

    boolean drawable(TileMesh mesh) {
        return true;
    }

    /** Requests sent and not answered yet. More keeps the server busy, fewer keeps the answers fresh while the player moves. */
    int maxOutstandingRequests() {
        return 96;
    }

    /**
     * Whether a missing tile is loaded as a stand-in while its finer children load. Rough terrain needs it, there is nothing below it;
     * detailed tiles do not, because the rough terrain already fills every gap, and each finer tile is shown as soon as it arrives.
     */
    boolean loadsFallbackParents() {
        return true;
    }

    /** Whether this tile, which should be split at this distance, may be loaded and drawn until all of its finer children have arrived. */
    boolean standsInForChildren(TilePos pos, double distance) {
        return true;
    }

    /** Load order of missing tiles, smaller first: big and close tiles first, so the whole view is covered quickly. */
    double priority(TilePos pos, double distance) {
        return distance / pos.size();
    }

    /** How long a tile without anything to draw counts as loaded before it is asked for again. */
    long emptyRetryMs() {
        return REQUEST_TIMEOUT_MS;
    }

    /** How old a drawn tile may get before it is asked for again; rough terrain never changes. */
    long refreshIntervalMs(TileMesh mesh) {
        return Long.MAX_VALUE;
    }

    abstract boolean canSendRequests();

    abstract void sendRequests(int[] requests);

    /** Decodes and meshes one tile on a mesh builder thread. Throws on bad data. */
    abstract TileMeshBuilder.Built build(byte[] data, long key, int epoch, Registry<Biome> biomes, int seaLevel) throws Exception;

    List<TileMesh> drawList() {
        return this.drawList;
    }

    int meshCount() {
        return this.meshes.size();
    }

    int pendingCount() {
        return this.pending.size() + this.building.size();
    }

    String stats() {
        long bytes = 0L;
        for (TileMesh mesh : this.meshes.values()) {
            bytes += mesh.bytes;
        }
        return this.name + ": tiles=" + this.meshes.size() + " (" + (bytes >> 20) + " MB) drawn=" + this.drawList.size() + " pending="
            + this.pending.size() + " building=" + this.building.size() + " empty=" + this.empty.size() + " wanted=" + this.candidates.size()
            + " requested=" + this.requested + " evicted=" + this.evicted;
    }

    boolean hasWork() {
        return !this.meshes.isEmpty() || !this.pending.isEmpty() || !this.building.isEmpty() || !this.empty.isEmpty();
    }

    void reset() {
        this.epoch++;
        this.planTick = Long.MIN_VALUE / 2;
        this.changed = true;
        this.keptParents.clear();
        this.keptChildren.clear();
        if (this.partial != null) {
            this.partial.buffer.close();
            this.partial = null;
        }
        for (TileMesh mesh : this.meshes.values()) {
            mesh.close();
        }
        this.closeRetired();
        this.meshes.clear();
        this.pending.clear();
        this.building.clear();
        this.empty.clear();
        this.built.clear();
        this.drawList = List.of();
        this.candidates.clear();
    }

    private void closeRetired() {
        for (TileMesh mesh : this.retired) {
            mesh.close();
        }
        this.retired.clear();
    }

    /** Every tile of this layer over a chunk counts as outdated: it is shown until fresh data arrives, which is asked for when drawn. */
    void markChanged(int chunkX, int chunkZ) {
        for (int level = this.lowestLevel(); level <= this.maxLevel(); level++) {
            long size = FarPlaneConstants.tileSize(level);
            long key = new TilePos(level, (int) Math.floorDiv(chunkX * 16L, size), (int) Math.floorDiv(chunkZ * 16L, size)).pack();
            TileMesh mesh = this.meshes.get(key);
            if (mesh != null) {
                mesh.changedAt = Util.getMillis();
            }
            this.empty.remove(key);
        }
        this.changed = true;
    }

    /** Lowest level this layer can have tiles of. */
    int lowestLevel() {
        return 0;
    }

    void onData(int level, int x, int z, byte[] bytes, Registry<Biome> biomes, int seaLevel) {
        if (level < 0 || level > this.maxLevel()) {
            return;
        }
        TilePos pos = new TilePos(level, x, z);
        if (!pos.isInsideWorld()) {
            return;
        }
        long key = pos.pack();
        // Only data that was asked for is accepted, so late answers to old requests cannot fill memory after a reset.
        if (!this.pending.containsKey(key)) {
            return;
        }
        this.pending.remove(key);
        if (this.building.contains(key) || this.building.size() >= MAX_BUILD_BACKLOG) {
            return;
        }
        this.submit(key, epoch -> this.build(bytes, key, epoch, biomes, seaLevel));
    }

    /** Something a mesh builder thread makes a tile's mesh with, for the epoch it was started in. */
    interface Work {
        TileMeshBuilder.Built run(int epoch) throws Exception;
    }

    /** Builds a tile's mesh on a mesh builder thread; it replaces the one drawn when it is uploaded. */
    final void submit(long key, Work work) {
        int buildEpoch = this.epoch;
        this.building.add(key);
        try {
            this.meshExecutor.execute(() -> {
                try {
                    long start = PerfLog.ON ? System.nanoTime() : 0L;
                    this.built.add(work.run(buildEpoch));
                    if (PerfLog.ON) {
                        PerfLog.meshBuilt(System.nanoTime() - start);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Dropping bad {} tile {}", this.name, TilePos.unpack(key), e);
                    this.built.add(TileMeshBuilder.Built.failed(key, buildEpoch));
                }
            });
        } catch (RejectedExecutionException e) {
            this.building.remove(key);
        }
    }

    final boolean isBuilding(long key) {
        return this.building.contains(key);
    }

    final int buildingCount() {
        return this.building.size();
    }

    /**
     * Uploads finished meshes on the render thread until the budget of meshes or the time of this frame runs out; at least one
     * part is written per call, so loading never stops. Returns how much of the budget is left.
     */
    int upload(int budget, long deadline) {
        int startBudget = budget;
        while (budget > 0) {
            if (this.partial == null) {
                // A single upload cannot be interrupted once it starts, so past the deadline no new mesh is started in this call
                // once one has gone up; the rest wait for the next frame.
                if (budget < startBudget && System.nanoTime() >= deadline) {
                    break;
                }
                TileMeshBuilder.Built result = this.built.poll();
                if (result == null) {
                    break;
                }
                if (result.epoch() != this.epoch) {
                    continue;
                }
                this.building.remove(result.key());
                if (result.vertices() == null || result.indexCount() == 0) {
                    // Old terrain that is now empty must not stay on screen.
                    TileMesh old = this.meshes.remove(result.key());
                    if (old != null) {
                        this.retired.add(old);
                    }
                    this.empty.put(result.key(), Util.getMillis());
                    this.changed = true;
                    continue;
                }
                this.empty.remove(result.key());
                TilePos pos = TilePos.unpack(result.key());
                int bytes = result.vertices().remaining();
                // Every mesh is written in parts over frames, a small one in a single part: on phones one upload of the data
                // itself can stall a frame for tens of milliseconds, so only the empty buffer is made in one call and the data
                // follows in parts, each followed by the deadline check below. The tile is only drawn once all of it is there.
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                    () -> "FarPlane " + this.name + " tile " + pos, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes
                );
                this.partial = new PartialUpload(result, buffer);
            }
            PartialUpload upload = this.partial;
            ByteBuffer data = upload.result.vertices();
            int base = data.position();
            int total = data.remaining();
            int length = Math.min(UPLOAD_PART_BYTES, total - upload.written);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(upload.buffer.slice(upload.written, length), data.slice(base + upload.written, length));
            upload.written += length;
            if (upload.written >= total) {
                this.partial = null;
                this.finishUpload(upload.result, upload.buffer);
                budget--;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return budget;
    }

    private void finishUpload(TileMeshBuilder.Built result, GpuBuffer buffer) {
        if (PerfLog.ON) {
            PerfLog.uploaded(result.vertices().remaining());
        }
        TileMesh mesh = new TileMesh(TilePos.unpack(result.key()), buffer, result, Util.getMillis());
        // A fresh tile has not been walked over yet; without this it would be the first one evicted.
        mesh.lastUsedTick = this.tick;
        TileMesh old = this.meshes.put(result.key(), mesh);
        if (old != null) {
            // The same tile built again (with new neighbours or fresh data): the tree stays as it was, the new mesh just takes the
            // old one's place, see quietTick.
            old.replacedBy = mesh;
            this.retired.add(old);
        } else {
            this.changed = true;
        }
        this.onUploaded(mesh);
    }

    /** A mesh being written to the GPU in parts, see {@link #upload}. */
    private static final class PartialUpload {
        final TileMeshBuilder.Built result;
        final GpuBuffer buffer;
        int written;

        PartialUpload(TileMeshBuilder.Built result, GpuBuffer buffer) {
            this.result = result;
            this.buffer = buffer;
        }
    }

    void onUploaded(TileMesh mesh) {
    }

    void expirePending(long now) {
        this.pending.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > REQUEST_TIMEOUT_MS);
        long retry = this.emptyRetryMs();
        // A tile no longer counted as empty is not ready any more: the tree has to be walked again.
        this.changed |= this.empty.long2LongEntrySet().removeIf(entry -> now - entry.getLongValue() > retry);
    }

    void select(LodView view, long tick, long now) {
        this.view = view;
        this.tick = tick;
        this.now = now;
        double movedX = view.camX - this.walkX;
        double movedY = view.camY - this.walkY;
        double movedZ = view.camZ - this.walkZ;
        boolean moved = movedX * movedX + movedY * movedY + movedZ * movedZ > QUIET_MOVE * QUIET_MOVE;
        if (!this.changed && !moved && tick >= this.walkTick && tick - this.walkTick < WALK_TICKS) {
            this.quietTick();
            return;
        }
        this.changed = false;
        this.walkTick = tick;
        this.walkX = view.camX;
        this.walkY = view.camY;
        this.walkZ = view.camZ;
        this.keptParents.clear();
        this.keptChildren.clear();
        this.nextDrawList.clear();
        this.candidates.clear();
        this.readyStates.clear();
        this.relevantStates.clear();
        this.distances.clear();

        double reach = this.reach();
        int rootLevel = this.minLevel();
        while (rootLevel < this.maxLevel() && (long) FarPlaneConstants.tileSize(rootLevel) * 2L < reach) {
            rootLevel++;
        }
        long rootSize = FarPlaneConstants.tileSize(rootLevel);
        double limit = FarPlaneConstants.WORLD_LIMIT;
        long minX = Math.floorDiv((long) Math.floor(Math.max(view.camX - reach, -limit)), rootSize);
        long maxX = Math.floorDiv((long) Math.floor(Math.min(view.camX + reach, limit)), rootSize);
        long minZ = Math.floorDiv((long) Math.floor(Math.max(view.camZ - reach, -limit)), rootSize);
        long maxZ = Math.floorDiv((long) Math.floor(Math.min(view.camZ + reach, limit)), rootSize);
        List<TilePos> roots = new ArrayList<>();
        for (long z = minZ; z <= maxZ; z++) {
            for (long x = minX; x <= maxX; x++) {
                roots.add(new TilePos(rootLevel, (int) x, (int) z));
            }
        }
        boolean timed = PerfLog.ON && this instanceof RoughLayer;
        long start = timed ? System.nanoTime() : 0L;
        // The plan changes slowly: it is made again every few ticks, and at once when the camera has moved a bit.
        double planMovedX = view.camX - this.planX;
        double planMovedZ = view.camZ - this.planZ;
        if (tick - this.planTick >= PLAN_TICKS || tick < this.planTick || planMovedX * planMovedX + planMovedZ * planMovedZ > PLAN_MOVE * PLAN_MOVE) {
            this.planSplits(roots);
            this.planTick = tick;
            this.planX = view.camX;
            this.planZ = view.camZ;
        }
        long planned = timed ? System.nanoTime() : 0L;
        for (TilePos root : roots) {
            this.visit(root);
        }
        if (timed) {
            PerfLog.tickPhase(7, planned - start);
            PerfLog.tickPhase(8, System.nanoTime() - planned);
            PerfLog.tickPhase(9, this.relevantStates.size());
        }
        if (!this.nextDrawList.equals(this.drawList)) {
            this.drawListVersion++;
        }
        this.drawList = List.copyOf(this.nextDrawList);
        // Replaced meshes may have been in the last draw list; once a new one is made they are closed.
        this.closeRetired();
    }

    /**
     * A tick with nothing new for the tree: the camera has hardly moved and no tile came, went or changed. The last walk still holds,
     * so it is not done again (the walk is most of the client tick with many tiles). What the walk does for every tile drawn is done
     * here only: they and the stand-ins kept with them count as used, and the cuts the manager sets after the walk start over. Changed
     * tiles and refreshes are asked for at the next walk, at most {@link #WALK_TICKS} later.
     */
    private void quietTick() {
        if (!this.retired.isEmpty()) {
            this.putReplacements();
        }
        for (TileMesh mesh : this.drawList) {
            mesh.lastUsedTick = this.tick;
            mesh.discardRadius = 0.0F;
            mesh.keepRadius = 0.0F;
            mesh.fadeWidth = 0.0F;
        }
        for (TileMesh mesh : this.keptParents) {
            mesh.lastUsedTick = Math.max(mesh.lastUsedTick, this.tick - 1);
        }
        for (TileMesh mesh : this.keptChildren) {
            mesh.lastUsedTick = this.tick;
        }
        if (PerfLog.ON && this instanceof RoughLayer) {
            PerfLog.tickPhase(10, 1L);
        }
    }

    /**
     * Puts the meshes of tiles built again where the old ones were drawn or kept, then closes the old ones. Only meshes replaced by
     * one of the same tile are retired between walks: a tile that came, went or turned empty makes the next tick walk the tree.
     */
    private void putReplacements() {
        boolean drawn = false;
        for (TileMesh mesh : this.drawList) {
            if (mesh.replacedBy != null) {
                drawn = true;
                break;
            }
        }
        if (drawn) {
            this.nextDrawList.clear();
            for (TileMesh mesh : this.drawList) {
                this.nextDrawList.add(latest(mesh));
            }
            this.drawList = List.copyOf(this.nextDrawList);
            this.drawListVersion++;
        }
        this.keptParents.replaceAll(LodLayer::latest);
        this.keptChildren.replaceAll(LodLayer::latest);
        this.closeRetired();
    }

    private static TileMesh latest(TileMesh mesh) {
        while (mesh.replacedBy != null) {
            mesh = mesh.replacedBy;
        }
        return mesh;
    }

    /** The draw list changes whenever this goes up; the rough layer walks again when the detailed one changes, see LodManager. */
    int drawListVersion() {
        return this.drawListVersion;
    }

    /** The next tick walks the tree again, whatever changed. */
    void walkAgain() {
        this.changed = true;
    }

    /** See select: the tree is walked again when something changed, the camera moved this far, or this many ticks went by. */
    private static final double QUIET_MOVE = 4.0;
    private static final int WALK_TICKS = 10;
    private boolean changed = true;
    private long walkTick = Long.MIN_VALUE / 2;
    private double walkX;
    private double walkY;
    private double walkZ;
    private int drawListVersion;
    /** Meshes the last walk kept without drawing them: parents standing behind their children, and children loaded under a stand-in. */
    private final ArrayList<TileMesh> keptParents = new ArrayList<>();
    private final ArrayList<TileMesh> keptChildren = new ArrayList<>();

    /**
     * Decides up front which tiles may split, most important first (big tiles close to the camera), until the tiles on screen would
     * no longer fit into the GPU memory budget. With a small budget distant terrain simply stays coarser. Without this the wanted
     * tiles would not fit, and tiles would be evicted and loaded again every tick, which shows up as flickering.
     */
    private long planTick = Long.MIN_VALUE / 2;
    private double planX;
    private double planZ;
    /** See select: how often the split plan is made again, and how far the camera may move before it is made again at once. */
    private static final int PLAN_TICKS = 5;
    private static final double PLAN_MOVE = 16.0;

    private void planSplits(List<TilePos> roots) {
        this.allowedSplits.clear();
        int maxLeaves = Math.max(MIN_LEAVES, (int) (this.maxMeshes() * LEAF_SHARE));
        PriorityQueue<SplitCandidate> queue = new PriorityQueue<>(Comparator.comparingDouble(SplitCandidate::score).reversed());
        int leaves = 0;
        for (TilePos root : roots) {
            if (this.isRelevant(root)) {
                leaves++;
                this.offerSplit(queue, root);
            }
        }
        while (!queue.isEmpty()) {
            TilePos pos = queue.poll().pos();
            int children = 0;
            for (int i = 0; i < 4; i++) {
                if (this.isRelevant(pos.child(i))) {
                    children++;
                }
            }
            if (leaves - 1 + children > maxLeaves) {
                break;
            }
            leaves += children - 1;
            this.allowedSplits.add(pos.pack());
            for (int i = 0; i < 4; i++) {
                TilePos child = pos.child(i);
                if (this.isRelevant(child)) {
                    this.offerSplit(queue, child);
                }
            }
        }
    }

    private void offerSplit(PriorityQueue<SplitCandidate> queue, TilePos pos) {
        double distance = this.effectiveDistance(pos);
        if (pos.level() > this.minLevel() && this.wantsSplit(pos, distance)) {
            queue.add(new SplitCandidate(pos, pos.size() / Math.max(distance, 1.0)));
        }
    }

    /** Cached per tick: the same tiles are checked by the split plan, the walk and the readiness test. */
    boolean isRelevant(TilePos pos) {
        long key = pos.pack();
        byte cached = this.relevantStates.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        // Only tiles within vanilla's distance can be covered by its chunks; the height of the others is not looked up at all.
        boolean relevant = pos.isInsideWorld()
            && pos.distanceTo(this.view.camX, this.view.camZ) <= this.reach()
            && this.extraRelevant(pos)
            && (pos.farthestDistanceTo(this.view.camX, this.view.camZ) >= this.view.vanillaRadius
                || !this.view.coveredByVanilla(pos, this.verticalDistance(pos, true)));
        this.relevantStates.put(key, relevant ? (byte) 1 : (byte) -1);
        return relevant;
    }

    /** Vertical distance from the camera to the terrain of a tile, taken from the tile itself or the closest loaded parent. */
    double verticalDistance(TilePos pos, boolean farthest) {
        int level = pos.level();
        int x = pos.x();
        int z = pos.z();
        double camY = this.view.camY;
        while (level <= this.maxLevel()) {
            TileMesh mesh = this.meshes.get(TilePos.pack(level, x, z));
            if (mesh != null) {
                return farthest
                    ? Math.max(Math.abs(camY - mesh.terrainMinY), Math.abs(camY - mesh.maxY))
                    : Math.max(0.0, Math.max(camY - mesh.maxY, mesh.terrainMinY - camY));
            }
            level++;
            x = Math.floorDiv(x, 2);
            z = Math.floorDiv(z, 2);
        }
        return this.view.fallbackVertical(farthest);
    }

    /** Cached per tick: the split plan, the walk and the requests all ask for it, several times per tile. */
    double effectiveDistance(TilePos pos) {
        long key = pos.pack();
        double cached = this.distances.getOrDefault(key, -1.0);
        if (cached >= 0.0) {
            return cached;
        }
        double horizontal = pos.distanceTo(this.view.camX, this.view.camZ);
        double vertical = this.verticalDistance(pos, false);
        double distance = Math.sqrt(horizontal * horizontal + vertical * vertical);
        this.distances.put(key, distance);
        return distance;
    }

    private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap distances = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();

    private void visit(TilePos pos) {
        if (!this.isRelevant(pos)) {
            return;
        }
        double distance = this.effectiveDistance(pos);
        TileMesh own = this.meshes.get(pos.pack());
        if (own != null) {
            // Parents hidden behind their children stay as a fallback, but are the first to go when memory runs out.
            own.lastUsedTick = Math.max(own.lastUsedTick, this.tick - 1);
            this.keptParents.add(own);
        }
        if (!this.allowedSplits.contains(pos.pack())) {
            if (own != null && this.drawable(own)) {
                this.draw(own, distance);
            } else {
                this.request(pos, distance, true);
            }
            return;
        }

        boolean childrenReady = true;
        for (int i = 0; i < 4; i++) {
            TilePos child = pos.child(i);
            if (this.isRelevant(child) && !this.isReady(child)) {
                childrenReady = false;
            }
        }

        boolean standIn = this.standsInForChildren(pos, distance);
        if (childrenReady || own == null || !this.drawable(own) || !standIn) {
            if (own == null && !childrenReady && this.loadsFallbackParents() && standIn) {
                this.request(pos, distance, true);
            }
            for (int i = 0; i < 4; i++) {
                this.visit(pos.child(i));
            }
        } else {
            this.draw(own, distance);
            for (int i = 0; i < 4; i++) {
                TilePos child = pos.child(i);
                if (!this.isRelevant(child)) {
                    continue;
                }
                TileMesh childMesh = this.meshes.get(child.pack());
                if (childMesh != null) {
                    childMesh.lastUsedTick = this.tick;
                    this.keptChildren.add(childMesh);
                } else if (!this.empty.containsKey(child.pack())) {
                    this.request(child, this.effectiveDistance(child), false);
                }
            }
        }
    }

    /**
     * Whether everything that would be drawn in place of a tile is loaded: the tile itself, or, when it splits further, all of its
     * relevant children. Without the second case a hidden middle tile that got evicted would keep its parent from switching to the
     * finer tiles, and the middle tile would be requested again and again.
     */
    private boolean isReady(TilePos pos) {
        long key = pos.pack();
        byte cached = this.readyStates.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        boolean ready = this.meshes.containsKey(key) || this.empty.containsKey(key);
        if (!ready && this.allowedSplits.contains(key)) {
            ready = true;
            for (int i = 0; i < 4; i++) {
                TilePos child = pos.child(i);
                if (this.isRelevant(child) && !this.isReady(child)) {
                    ready = false;
                    break;
                }
            }
        }
        this.readyStates.put(key, ready ? (byte) 1 : (byte) -1);
        return ready;
    }

    private void draw(TileMesh mesh, double distance) {
        mesh.lastUsedTick = this.tick;
        mesh.discardRadius = 0.0F;
        mesh.keepRadius = 0.0F;
        mesh.fadeWidth = 0.0F;
        this.nextDrawList.add(mesh);
        if (mesh.changedAt > mesh.receivedAt && this.now - mesh.receivedAt > CHANGED_REFRESH_MS) {
            // Changed blocks are as urgent as a missing tile: the old mesh stays on screen only until the new one arrives. As a
            // background refresh it would wait behind every tile still loading, which while flying around is forever.
            this.enqueue(mesh.pos, this.priority(mesh.pos, distance), false);
        } else if (this.now - mesh.receivedAt > this.refreshIntervalMs(mesh)) {
            this.enqueue(mesh.pos, REFRESH_PRIORITY + distance / mesh.pos.size(), false);
        }
    }

    /** @param hole nothing of this layer is drawn where the tile goes, see {@link FarPlanePayloads#REQUEST_HOLE} */
    private void request(TilePos pos, double distance, boolean hole) {
        long key = pos.pack();
        if (!this.meshes.containsKey(key) && !this.empty.containsKey(key)) {
            this.enqueue(pos, this.priority(pos, distance), hole);
        }
    }

    private void enqueue(TilePos pos, double priority, boolean hole) {
        long key = pos.pack();
        if (!this.pending.containsKey(key) && !this.building.contains(key)) {
            this.candidates.add(new Candidate(key, priority, hole));
        }
    }

    void sendRequests(long now) {
        int room = this.maxOutstandingRequests() - this.pending.size();
        if (room <= 0 || this.candidates.isEmpty() || !this.canSendRequests()) {
            return;
        }
        // Between walks the candidates of the last one stay; those asked for since are left out.
        this.candidates.removeIf(candidate -> this.pending.containsKey(candidate.key()) || this.building.contains(candidate.key()));
        if (this.candidates.isEmpty()) {
            return;
        }
        this.candidates.sort(Comparator.comparingDouble(Candidate::priority));
        int missing = 0;
        while (missing < this.candidates.size() && this.candidates.get(missing).priority() < REFRESH_PRIORITY) {
            missing++;
        }
        int count = Math.min(room, Math.min(this.candidates.size(), missing + MAX_REFRESHES_PER_TICK));
        int sent = 0;
        while (sent < count) {
            int batch = Math.min(FarPlanePayloads.MAX_REQUESTS_PER_PACKET, count - sent);
            int[] requests = new int[batch * 3];
            for (int i = 0; i < batch; i++) {
                Candidate candidate = this.candidates.get(sent + i);
                long key = candidate.key();
                TilePos pos = TilePos.unpack(key);
                requests[i * 3] = pos.level() | (candidate.hole() ? FarPlanePayloads.REQUEST_HOLE : 0);
                requests[i * 3 + 1] = pos.x();
                requests[i * 3 + 2] = pos.z();
                this.pending.put(key, now);
                this.requested++;
            }
            this.sendRequests(requests);
            sent += batch;
        }
    }

    void evict() {
        int limit = this.maxMeshes();
        if (this.meshes.size() <= limit) {
            return;
        }
        List<TileMesh> unused = new ArrayList<>();
        for (TileMesh mesh : this.meshes.values()) {
            if (mesh.lastUsedTick < this.tick) {
                unused.add(mesh);
            }
        }
        unused.sort(Comparator.comparingLong(mesh -> mesh.lastUsedTick));
        int target = limit - limit / 10;
        for (TileMesh mesh : unused) {
            if (this.meshes.size() <= target) {
                break;
            }
            this.meshes.remove(mesh.pos.pack());
            mesh.close();
            this.evicted++;
            this.changed = true;
        }
    }

    private record Candidate(long key, double priority, boolean hole) {
    }

    private record SplitCandidate(TilePos pos, double score) {
    }
}
