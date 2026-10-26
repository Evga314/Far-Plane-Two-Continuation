package com.Evga314.farplanetwocontinuation.client.lod;

import java.util.Arrays;

/**
 * The light of blocks (sea pickles, torches, lava, glowstone) worked out from the blocks that give it, for the chunks the world never
 * lit (virtual ones), see TileLight: like vanilla's, a level less for every block away, counted along the axes. A source under
 * something that stops light (the rock over a cave) shines no higher than that, so a closed lava lake does not light the ground over
 * it; around it through the rock it still does. Sources are kept in buckets of {@link #BUCKET} blocks, so a point only looks at the
 * few nearby, and buckets that cannot beat the light found so far are skipped.
 */
final class BlockLights {
    private static final int BUCKET = 8;
    private static final int MAX_LEVEL = 15;
    /** Buckets around the tile: light from up to 16 blocks out reaches in. */
    private static final int MARGIN = 2;
    /** Buckets around a point that may hold sources reaching it, nearest first. */
    private static final int REACH = 2;
    private static final int[] ORDER_X;
    private static final int[] ORDER_Z;
    private static final float EPSILON = 1.0E-3F;

    static {
        int side = 2 * REACH + 1;
        Integer[] order = new Integer[side * side];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(
            Math.abs(a % side - REACH) + Math.abs(a / side - REACH), Math.abs(b % side - REACH) + Math.abs(b / side - REACH)
        ));
        ORDER_X = new int[order.length];
        ORDER_Z = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            ORDER_X[i] = order[i] % side - REACH;
            ORDER_Z[i] = order[i] / side - REACH;
        }
    }

    private final float originX;
    private final float originZ;
    private final int side;
    private final int[] heads;
    /** Per bucket: the brightest source and the lowest and highest one, to tell whether it can light a point at all. */
    private final int[] bucketLevels;
    private final float[] bucketLows;
    private final float[] bucketHighs;
    private int[] next = new int[16];
    private float[] positions = new float[48];
    private float[] ceilings = new float[16];
    private int[] levels = new int[16];
    private int count;

    /** For a tile whose west and north edge lie at {@code originX, originZ}, {@code width} blocks wide. */
    BlockLights(float originX, float originZ, float width) {
        this.originX = originX - MARGIN * BUCKET;
        this.originZ = originZ - MARGIN * BUCKET;
        this.side = (int) Math.ceil(width / BUCKET) + 2 * MARGIN;
        int buckets = this.side * this.side;
        this.heads = new int[buckets];
        Arrays.fill(this.heads, -1);
        this.bucketLevels = new int[buckets];
        this.bucketLows = new float[buckets];
        this.bucketHighs = new float[buckets];
        Arrays.fill(this.bucketLows, Float.MAX_VALUE);
        Arrays.fill(this.bucketHighs, -Float.MAX_VALUE);
    }

    boolean isEmpty() {
        return this.count == 0;
    }

    /**
     * A block giving light of a level, by the center of the block; {@code ceiling}: the bottom of the first block over it that stops
     * light, {@link Float#MAX_VALUE} for none.
     */
    void add(float x, float y, float z, float ceiling, int level) {
        int bx = (int) Math.floor((x - this.originX) / BUCKET);
        int bz = (int) Math.floor((z - this.originZ) / BUCKET);
        if (bx < 0 || bz < 0 || bx >= this.side || bz >= this.side || level <= 0) {
            return;
        }
        int bucket = bx + bz * this.side;
        if (this.count == this.levels.length) {
            this.levels = Arrays.copyOf(this.levels, this.count * 2);
            this.next = Arrays.copyOf(this.next, this.count * 2);
            this.ceilings = Arrays.copyOf(this.ceilings, this.count * 2);
            this.positions = Arrays.copyOf(this.positions, this.count * 6);
        }
        int light = Math.min(level, MAX_LEVEL);
        this.positions[this.count * 3] = x;
        this.positions[this.count * 3 + 1] = y;
        this.positions[this.count * 3 + 2] = z;
        this.ceilings[this.count] = ceiling;
        this.levels[this.count] = light;
        this.next[this.count] = this.heads[bucket];
        this.heads[bucket] = this.count;
        this.bucketLevels[bucket] = Math.max(this.bucketLevels[bucket], light);
        this.bucketLows[bucket] = Math.min(this.bucketLows[bucket], y);
        this.bucketHighs[bucket] = Math.max(this.bucketHighs[bucket], y);
        this.count++;
    }

    /** The light of blocks at a point, 0 to 15. */
    int at(float x, float y, float z) {
        if (this.count == 0) {
            return 0;
        }
        float fx = (x - this.originX) / BUCKET;
        float fz = (z - this.originZ) / BUCKET;
        int bx = (int) Math.floor(fx);
        int bz = (int) Math.floor(fz);
        int best = 0;
        for (int o = 0; o < ORDER_X.length; o++) {
            int column = bx + ORDER_X[o];
            int row = bz + ORDER_Z[o];
            if (column < 0 || row < 0 || column >= this.side || row >= this.side) {
                continue;
            }
            int bucket = column + row * this.side;
            int level = this.bucketLevels[bucket];
            if (level <= best) {
                continue;
            }
            // The nearest any source of the bucket can be.
            float minX = this.originX + column * BUCKET;
            float minZ = this.originZ + row * BUCKET;
            float near = Math.max(0.0F, Math.max(minX - x, x - minX - BUCKET)) + Math.max(0.0F, Math.max(minZ - z, z - minZ - BUCKET))
                + Math.max(0.0F, Math.max(this.bucketLows[bucket] - y, y - this.bucketHighs[bucket]));
            if (level - (int) near <= best) {
                continue;
            }
            for (int i = this.heads[bucket]; i >= 0; i = this.next[i]) {
                if (y > this.ceilings[i] + EPSILON) {
                    continue;
                }
                float distance = Math.abs(this.positions[i * 3] - x) + Math.abs(this.positions[i * 3 + 1] - y) + Math.abs(this.positions[i * 3 + 2] - z);
                best = Math.max(best, this.levels[i] - (int) distance);
            }
            if (best >= MAX_LEVEL) {
                break;
            }
        }
        return best;
    }
}
