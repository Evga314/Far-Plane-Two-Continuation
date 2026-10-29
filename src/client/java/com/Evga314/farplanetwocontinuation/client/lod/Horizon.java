package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Occlusion culling by the terrain itself, the way flight simulators do it. Seen from the camera, everything is walked from near to
 * far; the terrain passed so far raises a horizon, one elevation per direction around the camera, and whatever lies completely under
 * that horizon in all of its directions cannot be seen and is not drawn: rough tiles as a whole, detailed tiles chunk by chunk.
 * <p>
 * It only depends on where the camera is, not where it looks, so turning around never shows anything missing. Everything is
 * rounded towards drawing too much: terrain hides things only with the lowest surface of a chunk (or of a part of a rough tile) and
 * only where it is drawn, things count with their highest point, and a margin covers the camera moving until the result is used.
 * <p>
 * A {@link #snapshot} is taken on the client thread; {@link Job#run} then works on a background thread, on that copy only.
 */
final class Horizon {
    /** Directions around the camera. They are not evenly spread (see {@link #direction}), at most about 0.7 degrees each. */
    private static final int BINS = 512;
    private static final double BINS_PER_UNIT = BINS / 4.0;
    /** Parts of a near rough tile that hide what is behind them, per side. */
    static final int ROUGH_PARTS = 4;
    /** Farther rough tiles hide what is behind them as a whole: far away, a part would cover almost no direction anyway. */
    private static final double ROUGH_PARTS_RANGE = 2048.0;
    /** Plants stand one block over the highest top of a chunk. */
    private static final int ABOVE_TOP = 2;

    private Horizon() {
    }

    /**
     * Copies what the culling needs from the tiles drawn this tick: the lists, and the cuts of the tiles, which change every tick.
     * Everything else it reads never changes.
     */
    static Job snapshot(LodView view, List<TileMesh> detail, List<TileMesh> rough, double ringRadius, double margin) {
        float[] roughCuts = new float[rough.size()];
        for (int t = 0; t < rough.size(); t++) {
            roughCuts[t] = rough.get(t).discardRadius;
        }
        return new Job(view.camX, view.camY, view.camZ, ringRadius, margin, detail, rough, roughCuts);
    }

    /**
     * A monotonic stand-in for the angle of a direction, from 0 to 4 around the circle, without trigonometry (the "diamond angle").
     * Per radian it changes by 0.5 to 1.
     */
    private static double direction(double x, double z) {
        if (z >= 0.0) {
            return x >= 0.0 ? z / (x + z + 1.0E-9) : 1.0 - x / (-x + z);
        }
        return x < 0.0 ? 2.0 - z / (-x - z) : 3.0 + x / (x - z);
    }

    /** What is hidden: whole tiles, and chunks of detailed tiles. */
    record Result(
        Map<TileMesh, boolean[]> hiddenChunks, Map<TileMesh, Boolean> occluded, int hiddenTiles, int hiddenChunkCount, double camX, double camY,
        double camZ, double margin, long nanos
    ) {
        String stats() {
            return "hidden tiles=" + this.hiddenTiles + " chunks=" + this.hiddenChunkCount;
        }
    }

    static final class Job {
        private final double camX;
        private final double camY;
        private final double camZ;
        private final double eyeLow;
        private final double eyeHigh;
        private final double margin;
        private final double ringRadius;
        private final float[] horizon = new float[BINS];

        private int count;
        // The arrays below are the workspace's, see Workspace; set when the job runs.
        private double[] near;
        private double[] far;
        private double[] minX;
        private double[] minZ;
        private double[] size;
        /** Highest point of something that may be hidden, NaN for parts that only hide. */
        private float[] top;
        /** Lowest surface of something that hides what is behind it, NaN for things that cannot. */
        private float[] bottom;
        private TileMesh[] mesh;
        /** Chunk of a detailed tile, -1 for a whole tile. */
        private int[] chunk;
        private final Map<TileMesh, boolean[]> hiddenChunks = new IdentityHashMap<>();
        private final Map<TileMesh, Boolean> occluded = new IdentityHashMap<>();
        private final List<TileMesh> detailTiles;
        private final List<TileMesh> roughTiles;
        private final float[] roughCuts;

        Job(
            double camX, double camY, double camZ, double ringRadius, double margin, List<TileMesh> detailTiles,
            List<TileMesh> roughTiles, float[] roughCuts
        ) {
            this.detailTiles = detailTiles;
            this.roughTiles = roughTiles;
            this.roughCuts = roughCuts;
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.eyeLow = camY - margin;
            this.eyeHigh = camY + margin;
            this.margin = margin;
            this.ringRadius = ringRadius;
        }

        private void addDetail(TileMesh tile) {
            int[] surfaces = tile.chunkSurfaces;
            int side = tile.chunksPerSide();
            if (surfaces == null || side == 0) {
                return;
            }
            this.hiddenChunks.put(tile, new boolean[side * side]);
            for (int c = 0; c < side * side; c++) {
                int low = surfaces[c * 2];
                int high = surfaces[c * 2 + 1];
                if (low > high) {
                    continue;
                }
                double x = tile.pos.minX() + (c % side) * 16.0;
                double z = tile.pos.minZ() + (c / side) * 16.0;
                int i = this.add(tile, c, x, z, 16.0, high + ABOVE_TOP);
                // Every chunk the tile draws hides what is behind it, out to the tile's own edge.
                this.bottom[i] = low;
            }
        }

        private void addRough(TileMesh tile, float discardRadius) {
            int whole = this.add(tile, -1, tile.pos.minX(), tile.pos.minZ(), tile.pos.size(), tile.maxY);
            float[] lows = tile.partLows;
            if (lows == null) {
                return;
            }
            // Parts cut away for vanilla's chunks are not drawn and hide nothing; inside the detailed ring the real blocks hide what is
            // behind them, the rough terrain there only guesses.
            double cut = Math.max(discardRadius, this.ringRadius);
            if (this.near[whole] >= ROUGH_PARTS_RANGE) {
                if (this.near[whole] - this.margin >= cut) {
                    float low = Float.MAX_VALUE;
                    for (float part : lows) {
                        low = Math.min(low, part);
                    }
                    this.bottom[whole] = low;
                }
                return;
            }
            double part = tile.pos.size() / (double) ROUGH_PARTS;
            for (int p = 0; p < lows.length; p++) {
                double x = tile.pos.minX() + (p % ROUGH_PARTS) * part;
                double z = tile.pos.minZ() + (p / ROUGH_PARTS) * part;
                int i = this.add(tile, -1, x, z, part, Float.NaN);
                if (this.near[i] - this.margin >= cut) {
                    this.bottom[i] = lows[p];
                } else {
                    this.count--;
                }
            }
        }

        private int add(TileMesh tile, int chunkIndex, double x, double z, double width, float highest) {
            if (this.count == this.near.length) {
                int capacity = this.count * 2;
                this.near = Arrays.copyOf(this.near, capacity);
                this.far = Arrays.copyOf(this.far, capacity);
                this.minX = Arrays.copyOf(this.minX, capacity);
                this.minZ = Arrays.copyOf(this.minZ, capacity);
                this.size = Arrays.copyOf(this.size, capacity);
                this.top = Arrays.copyOf(this.top, capacity);
                this.bottom = Arrays.copyOf(this.bottom, capacity);
                this.mesh = Arrays.copyOf(this.mesh, capacity);
                this.chunk = Arrays.copyOf(this.chunk, capacity);
            }
            int i = this.count++;
            double dx = Math.max(0.0, Math.max(x - this.camX, this.camX - (x + width)));
            double dz = Math.max(0.0, Math.max(z - this.camZ, this.camZ - (z + width)));
            double fx = Math.max(Math.abs(x - this.camX), Math.abs(x + width - this.camX));
            double fz = Math.max(Math.abs(z - this.camZ), Math.abs(z + width - this.camZ));
            this.near[i] = Math.sqrt(dx * dx + dz * dz);
            this.far[i] = Math.sqrt(fx * fx + fz * fz);
            this.minX[i] = x;
            this.minZ[i] = z;
            this.size[i] = width;
            this.top[i] = highest;
            this.bottom[i] = Float.NaN;
            this.mesh[i] = tile;
            this.chunk[i] = chunkIndex;
            return i;
        }

        Result run() {
            long start = System.nanoTime();
            Workspace work = WORKSPACE;
            this.near = work.near;
            this.far = work.far;
            this.minX = work.minX;
            this.minZ = work.minZ;
            this.size = work.size;
            this.top = work.top;
            this.bottom = work.bottom;
            this.mesh = work.mesh;
            this.chunk = work.chunk;
            try {
                return this.cull(work, start);
            } finally {
                // Grown arrays are kept for the next job; the tiles are let go of.
                Arrays.fill(this.mesh, 0, this.count, null);
                work.near = this.near;
                work.far = this.far;
                work.minX = this.minX;
                work.minZ = this.minZ;
                work.size = this.size;
                work.top = this.top;
                work.bottom = this.bottom;
                work.mesh = this.mesh;
                work.chunk = this.chunk;
            }
        }

        private Result cull(Workspace work, long start) {
            for (int t = 0; t < this.detailTiles.size(); t++) {
                this.addDetail(this.detailTiles.get(t));
            }
            for (int t = 0; t < this.roughTiles.size(); t++) {
                this.addRough(this.roughTiles.get(t), this.roughCuts[t]);
            }
            Arrays.fill(this.horizon, Float.NEGATIVE_INFINITY);
            if (work.order.length < this.count) {
                work.order = new int[this.near.length];
            }
            int[] order = work.order;
            for (int i = 0; i < this.count; i++) {
                order[i] = i;
            }
            double[] nearest = this.near;
            IntArrays.quickSort(order, 0, this.count, (a, b) -> Double.compare(nearest[a], nearest[b]));
            work.far = this.far;
            IntHeapPriorityQueue waiting = work.waiting;
            waiting.clear();
            int hiddenTiles = 0;
            int hiddenChunkCount = 0;
            for (int o = 0; o < this.count; o++) {
                int i = order[o];
                // Something hides only what lies completely behind it: along the same direction, a neighbour at the same distance may
                // just as well be in front of it.
                while (!waiting.isEmpty() && this.far[waiting.firstInt()] <= this.near[i]) {
                    this.raise(waiting.dequeueInt());
                }
                if (!Float.isNaN(this.top[i]) && !this.visible(i)) {
                    if (this.chunk[i] < 0) {
                        this.occluded.put(this.mesh[i], Boolean.TRUE);
                        hiddenTiles++;
                    } else {
                        this.hiddenChunks.get(this.mesh[i])[this.chunk[i]] = true;
                        hiddenChunkCount++;
                    }
                }
                if (!Float.isNaN(this.bottom[i])) {
                    waiting.enqueue(i);
                }
            }
            for (Map.Entry<TileMesh, boolean[]> entry : this.hiddenChunks.entrySet()) {
                TileMesh tile = entry.getKey();
                boolean[] hidden = entry.getValue();
                boolean all = true;
                for (int c = 0; c < hidden.length && all; c++) {
                    all = hidden[c] || tile.chunkSurfaces[c * 2] > tile.chunkSurfaces[c * 2 + 1];
                }
                if (all) {
                    this.occluded.put(tile, Boolean.TRUE);
                    hiddenTiles++;
                }
            }
            return new Result(
                this.hiddenChunks, this.occluded, hiddenTiles, hiddenChunkCount, this.camX, this.camY, this.camZ, this.margin,
                System.nanoTime() - start
            );
        }

        /** Whether some direction of a thing reaches above the horizon. */
        private boolean visible(int i) {
            double distance = this.near[i] - this.margin;
            if (distance <= 1.0) {
                return true;
            }
            double height = this.top[i] - this.eyeLow;
            // The highest point looks highest from as close as it can be, or, when it is below the eye, from as far as it can be.
            float elevation = (float) (height / (height > 0.0 ? distance : this.far[i] + this.margin));
            double[] span = this.span(i);
            // The camera may have moved sideways by the margin; a direction changes by at most one unit per radian.
            double grow = this.margin / distance;
            int first = (int) Math.floor((span[0] - grow) * BINS_PER_UNIT);
            int last = (int) Math.floor((span[1] + grow) * BINS_PER_UNIT);
            if (last - first >= BINS) {
                return true;
            }
            for (int b = first; b <= last; b++) {
                if (elevation > this.horizon[b & (BINS - 1)]) {
                    return true;
                }
            }
            return false;
        }

        /** Raises the horizon in the directions a part of the terrain covers completely. */
        private void raise(int i) {
            double distance = this.near[i] - this.margin;
            if (distance <= 1.0) {
                return;
            }
            double height = this.bottom[i] - this.eyeHigh;
            // The lowest surface hides least from as far as it can be, or, when it is below the eye, from as close as it can be.
            float elevation = (float) (height / (height > 0.0 ? this.far[i] + this.margin : distance));
            double[] span = this.span(i);
            double shrink = this.margin / distance;
            int first = (int) Math.ceil((span[0] + shrink) * BINS_PER_UNIT);
            int last = (int) Math.floor((span[1] - shrink) * BINS_PER_UNIT) - 1;
            for (int b = first; b <= last; b++) {
                int bin = b & (BINS - 1);
                if (elevation > this.horizon[bin]) {
                    this.horizon[bin] = elevation;
                }
            }
        }

        private final double[] spanScratch = new double[2];

        /** The directions a square covers, around the direction of its middle so that they never wrap. */
        private double[] span(int i) {
            double half = this.size[i] * 0.5;
            double center = direction(this.minX[i] + half - this.camX, this.minZ[i] + half - this.camZ);
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double x = this.minX[i] + ((corner & 1) == 0 ? 0.0 : this.size[i]) - this.camX;
                double z = this.minZ[i] + ((corner & 2) == 0 ? 0.0 : this.size[i]) - this.camZ;
                double offset = direction(x, z) - center;
                offset -= 4.0 * Math.floor((offset + 2.0) / 4.0);
                low = Math.min(low, offset);
                high = Math.max(high, offset);
            }
            this.spanScratch[0] = center + low;
            this.spanScratch[1] = center + high;
            return this.spanScratch;
        }
    }

    /**
     * The arrays a job works in, kept from job to job instead of made anew every tick: with all tiles in view they hold tens of
     * thousands of entries. Only one job runs at a time, on the culling thread, so one workspace serves them all.
     */
    private static final class Workspace {
        double[] near = new double[1024];
        double[] far = new double[1024];
        double[] minX = new double[1024];
        double[] minZ = new double[1024];
        double[] size = new double[1024];
        float[] top = new float[1024];
        float[] bottom = new float[1024];
        TileMesh[] mesh = new TileMesh[1024];
        int[] chunk = new int[1024];
        int[] order = new int[1024];
        /** Things that hide what is behind them, farthest end first; reads {@link #far} as it is when compared. */
        final IntHeapPriorityQueue waiting = new IntHeapPriorityQueue((a, b) -> Double.compare(this.far[a], this.far[b]));
    }

    private static final Workspace WORKSPACE = new Workspace();

    /** Lowest ground of each part of a rough tile, from its sample heights. */
    static float[] partLows(short[] heights) {
        int cells = FarPlaneConstants.TILE_CELLS / ROUGH_PARTS;
        int row = FarPlaneConstants.TILE_SAMPLES;
        float[] lows = new float[ROUGH_PARTS * ROUGH_PARTS];
        for (int p = 0; p < lows.length; p++) {
            int startX = (p % ROUGH_PARTS) * cells;
            int startZ = (p / ROUGH_PARTS) * cells;
            int low = Integer.MAX_VALUE;
            for (int z = startZ; z <= startZ + cells; z++) {
                for (int x = startX; x <= startX + cells; x++) {
                    low = Math.min(low, heights[x + z * row]);
                }
            }
            lows[p] = low;
        }
        return lows;
    }
}
