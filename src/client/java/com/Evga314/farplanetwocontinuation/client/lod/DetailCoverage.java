package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import java.util.List;

/**
 * Which parts of the detailed ring are already shown, either by detailed tiles or by vanilla chunks, on a grid of the finest detailed
 * tile size (64 blocks). Summed counts of the missing cells answer "is this whole rectangle covered" in constant time.
 */
final class DetailCoverage {
    private static final int CELL = FarPlaneConstants.tileSize(FarPlaneConstants.DETAIL_MIN_LEVEL);

    private final double radius;
    private final double camX;
    private final double camZ;
    private final long originX;
    private final long originZ;
    private final int size;
    /** {@code missing[(z + 1) * (size + 1) + x + 1]} = missing cells in the rectangle from the origin to (x, z) inclusive. */
    private final int[] missing;

    DetailCoverage(LodView view, double radius, List<TileMesh> detailTiles) {
        this.radius = radius;
        this.camX = view.camX;
        this.camZ = view.camZ;
        this.originX = Math.floorDiv((long) Math.floor(view.camX - radius), CELL);
        this.originZ = Math.floorDiv((long) Math.floor(view.camZ - radius), CELL);
        this.size = (int) (Math.floorDiv((long) Math.floor(view.camX + radius), CELL) - this.originX + 1);

        boolean[] covered = new boolean[this.size * this.size];
        for (TileMesh mesh : detailTiles) {
            int cells = mesh.pos.size() / CELL;
            long startX = Math.floorDiv(mesh.pos.minX(), CELL) - this.originX;
            long startZ = Math.floorDiv(mesh.pos.minZ(), CELL) - this.originZ;
            for (int dz = 0; dz < cells; dz++) {
                for (int dx = 0; dx < cells; dx++) {
                    long x = startX + dx;
                    long z = startZ + dz;
                    // Only squares where every chunk had data replace the rough terrain.
                    if (x >= 0 && z >= 0 && x < this.size && z < this.size && (mesh.coverage & 1L << (dx + dz * cells)) != 0) {
                        covered[(int) (x + z * this.size)] = true;
                    }
                }
            }
        }

        int stride = this.size + 1;
        this.missing = new int[stride * stride];
        for (int z = 0; z < this.size; z++) {
            for (int x = 0; x < this.size; x++) {
                int value = 0;
                if (!covered[x + z * this.size]) {
                    TilePos cell = new TilePos(FarPlaneConstants.DETAIL_MIN_LEVEL, (int) (this.originX + x), (int) (this.originZ + z));
                    if (cell.distanceTo(this.camX, this.camZ) <= radius && !view.coveredByVanilla(cell, view.fallbackVertical(true))) {
                        value = 1;
                    }
                }
                this.missing[(z + 1) * stride + x + 1] = value
                    + this.missing[z * stride + x + 1]
                    + this.missing[(z + 1) * stride + x]
                    - this.missing[z * stride + x];
            }
        }
    }

    /** Missing cells of the ring inside a tile; cells outside the ring never count. */
    private int missingIn(TilePos pos) {
        int cells = Math.max(1, pos.size() / CELL);
        long x0 = Math.max(0, Math.floorDiv(pos.minX(), CELL) - this.originX);
        long z0 = Math.max(0, Math.floorDiv(pos.minZ(), CELL) - this.originZ);
        long x1 = Math.min(this.size, Math.floorDiv(pos.minX(), CELL) - this.originX + cells);
        long z1 = Math.min(this.size, Math.floorDiv(pos.minZ(), CELL) - this.originZ + cells);
        if (x0 >= x1 || z0 >= z1) {
            return 0;
        }
        int stride = this.size + 1;
        return this.missing[(int) (z1 * stride + x1)] - this.missing[(int) (z0 * stride + x1)]
            - this.missing[(int) (z1 * stride + x0)] + this.missing[(int) (z0 * stride + x0)];
    }

    /** A rough tile completely inside the ring, whose whole area is already shown, does not need to be drawn. */
    boolean coversTile(TilePos pos) {
        return pos.farthestDistanceTo(this.camX, this.camZ) <= this.radius && this.missingIn(pos) == 0;
    }

    /** Radius to cut out of a rough tile reaching into the ring, or 0 while part of the ring under it is still missing. */
    float cutoutRadius(TilePos pos) {
        if (pos.distanceTo(this.camX, this.camZ) >= this.radius) {
            return 0.0F;
        }
        return this.missingIn(pos) == 0 ? (float) this.radius : 0.0F;
    }
}
