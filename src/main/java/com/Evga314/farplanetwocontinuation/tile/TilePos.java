package com.Evga314.farplanetwocontinuation.tile;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;

/**
 * Position of a LOD tile. A tile at level L covers {@code [x * size, (x + 1) * size)} on both axes where size = 32 * 2^L.
 */
public record TilePos(int level, int x, int z) {

    public static long pack(int level, int x, int z) {
        return ((long) level << 58) | (((long) x & 0x1FFFFFFFL) << 29) | ((long) z & 0x1FFFFFFFL);
    }

    public static TilePos unpack(long key) {
        int level = (int) (key >>> 58);
        int x = (int) (key << 6 >> 35);
        int z = (int) (key << 35 >> 35);
        return new TilePos(level, x, z);
    }

    public long pack() {
        return pack(this.level, this.x, this.z);
    }

    public int size() {
        return FarPlaneConstants.tileSize(this.level);
    }

    public long minX() {
        return (long) this.x * this.size();
    }

    public long minZ() {
        return (long) this.z * this.size();
    }

    public TilePos child(int index) {
        return new TilePos(this.level - 1, this.x * 2 + (index & 1), this.z * 2 + (index >> 1));
    }

    /** Whether the tile touches the playable world at all. Tiles completely outside of it are never generated. */
    public boolean isInsideWorld() {
        if (this.level < 0 || this.level > FarPlaneConstants.MAX_LEVEL) {
            return false;
        }
        long size = this.size();
        long minX = this.minX();
        long minZ = this.minZ();
        long limit = FarPlaneConstants.WORLD_LIMIT;
        return minX < limit && minX + size > -limit && minZ < limit && minZ + size > -limit;
    }

    /** Horizontal distance from a point to the closest point of the tile, 0 when the point is inside. */
    public double distanceTo(double px, double pz) {
        double size = this.size();
        double minX = this.minX();
        double minZ = this.minZ();
        double dx = Math.max(0.0, Math.max(minX - px, px - (minX + size)));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - (minZ + size)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Horizontal distance from a point to the farthest corner of the tile. */
    public double farthestDistanceTo(double px, double pz) {
        double size = this.size();
        double minX = this.minX();
        double minZ = this.minZ();
        double dx = Math.max(Math.abs(minX - px), Math.abs(minX + size - px));
        double dz = Math.max(Math.abs(minZ - pz), Math.abs(minZ + size - pz));
        return Math.sqrt(dx * dx + dz * dz);
    }
}
