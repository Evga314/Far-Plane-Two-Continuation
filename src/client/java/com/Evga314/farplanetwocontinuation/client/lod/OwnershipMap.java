package com.Evga314.farplanetwocontinuation.client.lod;

/**
 * Who draws every chunk column around the camera this frame (stages 4 and 5 of the rewrite): vanilla, the detailed tiles or the
 * rough terrain. One decision per column, made once a frame, which every output follows; see {@link #part}.
 * <p>
 * The rough terrain draws only its own columns, as they are, with their depth; of every other column it draws nothing. Where its
 * columns end against vanilla's or a detailed tile's, the border seam closes the wedge between the two surfaces (see BorderSeam).
 * A column vanilla has just started drawing counts as vanilla's from that very frame (what Sodium draws in a frame is read before
 * the LOD terrain is drawn, see LodManager.frameCheck), so the column turns from the rough terrain's into vanilla's in one frame;
 * one it has drawn for {@link #HANDOVER_MS} without a break is settled. The delay only goes that way: a column vanilla stops
 * drawing is the rough terrain's again at once. It is in time, not frames, so a phone at 30 frames a second waits as long as a
 * computer at 144.
 * <p>
 * Plain values only, so it can be tested without the game. Render thread only.
 */
final class OwnershipMap {
    /** How long vanilla has to draw a column without a break before the rough terrain stops drawing even its background. */
    static final long HANDOVER_MS = 100L;

    static final byte ROUGH = 0;
    static final byte VANILLA = 1;
    static final byte DETAILED = 2;
    /** Vanilla draws it, for less than {@link #HANDOVER_MS} yet. */
    static final byte HANDOVER = 3;

    /** What the rough terrain draws of a column. */
    static final byte NONE = 0;
    static final byte OWN = 1;

    /** Chunks from the middle of the map to its edge, and the chunk in its middle. */
    private int half = -1;
    private int originX;
    private int originZ;
    private byte[] owners = new byte[0];
    /** Since when vanilla has drawn each column without a break, plus one; 0 while it does not. */
    private long[] vanillaSince = new long[0];
    /** Scratch for moving the map with the camera. */
    private long[] movedSince = new long[0];
    /** Goes up whenever an owner changes, so what is made of the map is made again only then. */
    private int version;

    /**
     * Takes in what vanilla and the detailed tiles draw this frame, around chunk {@code originX, originZ}, {@code half} chunks to
     * each side, row by row from the north-west: {@code vanilla} and {@code detailed} per column. {@code half} below 0: nothing is
     * known, the rough terrain draws everything.
     */
    void update(int half, int originX, int originZ, boolean[] vanilla, boolean[] detailed, long nowMs) {
        if (half < 0) {
            if (this.half >= 0) {
                this.version++;
            }
            this.half = -1;
            return;
        }
        int side = 2 * half + 1;
        int count = side * side;
        if (half != this.half || originX != this.originX || originZ != this.originZ) {
            this.move(half, originX, originZ);
            this.version++;
        }
        for (int i = 0; i < count; i++) {
            if (vanilla[i]) {
                if (this.vanillaSince[i] == 0L) {
                    this.vanillaSince[i] = nowMs + 1L;
                }
            } else {
                this.vanillaSince[i] = 0L;
            }
            byte owner = ROUGH;
            if (detailed[i]) {
                owner = DETAILED;
            } else if (this.vanillaSince[i] != 0L) {
                owner = nowMs + 1L - this.vanillaSince[i] >= HANDOVER_MS ? VANILLA : HANDOVER;
            }
            if (this.owners[i] != owner) {
                this.owners[i] = owner;
                this.version++;
            }
        }
    }

    /** Moves the map to a new middle, keeping how long vanilla has drawn the columns still in it. */
    private void move(int half, int originX, int originZ) {
        int side = 2 * half + 1;
        int count = side * side;
        if (this.movedSince.length != count) {
            this.movedSince = new long[count];
        } else {
            java.util.Arrays.fill(this.movedSince, 0L);
        }
        if (this.half >= 0) {
            int oldSide = 2 * this.half + 1;
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int oldX = originX + x - half - this.originX + this.half;
                    int oldZ = originZ + z - half - this.originZ + this.half;
                    if (oldX >= 0 && oldZ >= 0 && oldX < oldSide && oldZ < oldSide) {
                        this.movedSince[x + z * side] = this.vanillaSince[oldX + oldZ * oldSide];
                    }
                }
            }
        }
        long[] swap = this.vanillaSince;
        this.vanillaSince = this.movedSince;
        this.movedSince = swap.length == count ? swap : new long[count];
        if (this.owners.length != count) {
            this.owners = new byte[count];
        }
        this.half = half;
        this.originX = originX;
        this.originZ = originZ;
    }

    int version() {
        return this.version;
    }

    int half() {
        return this.half;
    }

    int originX() {
        return this.originX;
    }

    int originZ() {
        return this.originZ;
    }

    /** The owner of a column; columns outside the map are the rough terrain's. */
    byte owner(long chunkX, long chunkZ) {
        int half = this.half;
        if (half < 0) {
            return ROUGH;
        }
        long x = chunkX - this.originX + half;
        long z = chunkZ - this.originZ + half;
        int side = 2 * half + 1;
        if (x < 0 || z < 0 || x >= side || z >= side) {
            return ROUGH;
        }
        return this.owners[(int) x + (int) z * side];
    }

    /** What the rough terrain draws of a column: its own columns as they are, nothing of the others. */
    byte part(long chunkX, long chunkZ) {
        return this.owner(chunkX, chunkZ) == ROUGH ? OWN : NONE;
    }


}
