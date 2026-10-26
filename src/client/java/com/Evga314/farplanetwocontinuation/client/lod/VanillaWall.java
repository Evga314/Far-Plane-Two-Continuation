package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Walls of water along the edge of what vanilla draws, where neither vanilla nor the detailed tiles draw the chunk next door. Seen
 * from above through vanilla's translucent water near its edge, the view goes under the surface into the next chunk, where the rough
 * terrain has only its flat water at sea level: without the wall it would show what lies under that water there instead. Each wall
 * stands on the border of vanilla's chunk, from its ground up to the surface of vanilla's water, in one darkened color of the water
 * of the rough terrain, but for a thin rim over vanilla's surface in the color of that water itself.
 * <p>
 * Made again only when the chunk mask changes. What each chunk has along its four sides is remembered while the chunk is in the mask.
 * Render thread only.
 */
final class VanillaWall implements AutoCloseable {
    /** Per side of a chunk, its 16 columns along the side: top and bottom of the water there, or NO_WATER. */
    private static final int COLUMNS = 16;
    private static final int NO_WATER = Integer.MIN_VALUE;
    /**
     * Vanilla's water fills its top block this far, and the wall reaches exactly up to it, a hair more: everything seen under that
     * surface is the wall, everything over it the rough terrain's water, which lies a little lower (see TileMeshBuilder's
     * WATER_UNDER_VANILLA). A wall ending under vanilla's surface left a thin gap through which the bottom of the world was seen; one
     * reaching over it stood out of the water as a thin band.
     */
    private static final float VANILLA_SURFACE = 8.0F / 9.0F;
    /**
     * The wall reaches this far over vanilla's surface, in the color of the rough terrain's water: that bit is seen not through vanilla's
     * water but over its edge, next to the rough water, and in the dark color of the wall it showed as a dark line along the edge.
     */
    private static final float OVER_SURFACE = 0.01F;
    // Directions of the sides: +x, -x, +z, -z.
    private static final int[] SIDE_X = {1, -1, 0, 0};
    /** Darkening of the water of the rough terrain for the walls. */
    private static final float SHADE = 0.55F;
    /** How far each quad of a wall reaches over its ends, see {@link #wall}. */
    private static final float OVERLAP = 0.02F;
    /** How deep under the surface the ground under a wall is looked for. */
    private static final int MAX_DEPTH = 64;
    private static final int[] SIDE_Z = {0, 0, 1, -1};
    /** Each column keeps top, bottom and the color of the water. */
    private static final int FIELDS = 3;

    /** Per chunk: for each side and column, top, bottom and color; see {@link #columns}. */
    private final Long2ObjectOpenHashMap<int[]> chunks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<int[]> keep = new Long2ObjectOpenHashMap<>();
    private int builtVersion = -1;
    private @Nullable GpuBuffer buffer;
    private int quads;
    private int originX;
    private int originZ;
    private ByteBuffer scratch = ByteBuffer.allocateDirect(0);

    int quads() {
        return this.quads;
    }

    /** Where the vertices are counted from, in blocks; heights are those of the world. */
    int originX() {
        return this.originX;
    }

    int originZ() {
        return this.originZ;
    }

    @Nullable GpuBuffer buffer() {
        return this.buffer;
    }

    /** Makes the walls again when the mask changed since the last time. */
    void update(ClientLevel level, LodManager.ChunkMask mask) {
        if (mask.version() == this.builtVersion) {
            return;
        }
        this.builtVersion = mask.version();
        int half = mask.half();
        int[] values = mask.values();
        this.quads = 0;
        if (half < 0 || values.length == 0) {
            return;
        }
        int side = 2 * half + 1;
        this.originX = (mask.originX() - half) * 16;
        this.originZ = (mask.originZ() - half) * 16;
        int vertexSize = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        ByteBuffer data = this.scratch;
        data.clear();
        this.keep.clear();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                // Only chunks vanilla draws itself.
                if ((values[x + z * side] & 3) == 0) {
                    continue;
                }
                int[] columns = null;
                for (int direction = 0; direction < 4; direction++) {
                    int nx = x + SIDE_X[direction];
                    int nz = z + SIDE_Z[direction];
                    boolean inside = nx >= 0 && nz >= 0 && nx < side && nz < side;
                    int next = inside ? values[nx + nz * side] : 0;
                    // Next door vanilla or the detailed tiles draw: their own water is there.
                    if ((next & (3 | LodManager.ChunkMask.DETAILED)) != 0) {
                        continue;
                    }
                    if (columns == null) {
                        int chunkX = mask.originX() - half + x;
                        int chunkZ = mask.originZ() - half + z;
                        columns = this.columns(level, chunkX, chunkZ);
                        if (columns == null) {
                            break;
                        }
                    }
                    // Columns of the same heights and colors in a row make one quad: fewer quads, and fewer seams between them.
                    for (int c = 0; c < COLUMNS; ) {
                        int at = (direction * COLUMNS + c) * FIELDS;
                        if (columns[at] == NO_WATER) {
                            c++;
                            continue;
                        }
                        int end = c + 1;
                        while (end < COLUMNS) {
                            int following = (direction * COLUMNS + end) * FIELDS;
                            if (columns[following] != columns[at] || columns[following + 1] != columns[at + 1]
                                || columns[following + 2] != columns[at + 2]) {
                                break;
                            }
                            end++;
                        }
                        if (data.remaining() < 8 * vertexSize) {
                            ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(4096, data.capacity() * 2)).order(ByteOrder.nativeOrder());
                            data.flip();
                            bigger.put(data);
                            data = bigger;
                        }
                        this.wall(data, x * 16, z * 16, direction, c, end, columns[at], columns[at + 1], columns[at + 2]);
                        this.quads += 2;
                        c = end;
                    }
                }
            }
        }
        this.scratch = data;
        // Chunks that left the mask are forgotten.
        Long2ObjectOpenHashMap<int[]> swap = this.chunks;
        swap.clear();
        swap.putAll(this.keep);
        if (this.quads == 0) {
            return;
        }
        data.flip();
        if (this.buffer == null || this.buffer.isClosed() || this.buffer.size() < data.remaining()) {
            if (this.buffer != null) {
                this.buffer.close();
            }
            int capacity = Math.max(data.remaining(), 64 * 1024);
            this.buffer = RenderSystem.getDevice().createBuffer(() -> "FarPlane vanilla wall", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.buffer.slice(0, data.remaining()), data);
    }

    /**
     * The water along the four sides of a chunk: per side and column top, bottom and color, NO_WATER where its top block is no water.
     * Null when the chunk is not loaded. Remembered while the chunk stays in the mask.
     */
    private int @Nullable [] columns(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        int[] known = this.chunks.get(key);
        if (known != null) {
            this.keep.put(key, known);
            return known;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            return null;
        }
        int[] columns = new int[4 * COLUMNS * FIELDS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int direction = 0; direction < 4; direction++) {
            for (int c = 0; c < COLUMNS; c++) {
                int localX = SIDE_X[direction] > 0 ? 15 : SIDE_X[direction] < 0 ? 0 : c;
                int localZ = SIDE_Z[direction] > 0 ? 15 : SIDE_Z[direction] < 0 ? 0 : c;
                int at = (direction * COLUMNS + c) * FIELDS;
                // The client's heightmaps give the highest block itself, not the height over it; the floor of the sea is not sent to
                // clients at all, so it is looked for: down through the water and what stands in it, as far as MAX_DEPTH.
                int water = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                pos.set(baseX + localX, water, baseZ + localZ);
                if (!chunk.getFluidState(pos).is(FluidTags.WATER)) {
                    columns[at] = NO_WATER;
                    continue;
                }
                // The ground is the first whole block under the water that is no coral: plants and corals stand on it, and their colors
                // made walls of odd colors.
                int y = water - 1;
                int limit = Math.max(level.getMinY(), water - MAX_DEPTH);
                while (y > limit) {
                    BlockState below = chunk.getBlockState(pos.setY(y));
                    if (below.getFluidState().isEmpty() && below.isSolidRender() && !below.is(BlockTags.CORAL_BLOCKS)) {
                        break;
                    }
                    y--;
                }
                int top = water + 1;
                int bottom = y + 1;
                columns[at] = top;
                columns[at + 1] = bottom;
                // The color of the rough terrain's water: the wall is a darker shade of it, the bit over vanilla's surface this one.
                columns[at + 2] = ARGB.scaleRGB(TileColors.waterColor(level.getBiome(pos.setY(water))), GeneralMeshFactory.WATER_SHADE);
            }
        }
        this.keep.put(key, columns);
        return columns;
    }

    /**
     * Columns {@code from} to {@code to} (exclusive) of the wall on a side of the chunk whose corner lies at {@code x}, {@code z} from
     * the origin. Each quad reaches a little over its ends (OVERLAP): where two only met, a pixel right on their shared corner was
     * now and then covered by neither, and the bottom of the world was seen through it.
     */
    private void wall(ByteBuffer data, int x, int z, int direction, int from, int to, int top, int bottom, int color) {
        float x0;
        float x1;
        float z0;
        float z1;
        if (SIDE_X[direction] != 0) {
            x0 = x1 = x + (SIDE_X[direction] > 0 ? 16.0F : 0.0F);
            z0 = z + from - OVERLAP;
            z1 = z + to + OVERLAP;
        } else {
            z0 = z1 = z + (SIDE_Z[direction] > 0 ? 16.0F : 0.0F);
            x0 = x + from - OVERLAP;
            x1 = x + to + OVERLAP;
        }
        float surface = top - 1 + VANILLA_SURFACE;
        float over = surface + OVER_SURFACE;
        int dark = ARGB.scaleRGB(color, SHADE);
        vertex(data, x0, bottom, z0, dark);
        vertex(data, x0, surface, z0, dark);
        vertex(data, x1, surface, z1, dark);
        vertex(data, x1, bottom, z1, dark);
        vertex(data, x0, surface, z0, color);
        vertex(data, x0, over, z0, color);
        vertex(data, x1, over, z1, color);
        vertex(data, x1, surface, z1, color);
    }

    private static void vertex(ByteBuffer data, float x, float y, float z, int color) {
        data.putFloat(x).putFloat(y).putFloat(z)
            .put((byte) ARGB.red(color)).put((byte) ARGB.green(color)).put((byte) ARGB.blue(color)).put((byte) -1);
    }

    @Override
    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
        this.chunks.clear();
        this.builtVersion = -1;
        this.quads = 0;
    }
}
