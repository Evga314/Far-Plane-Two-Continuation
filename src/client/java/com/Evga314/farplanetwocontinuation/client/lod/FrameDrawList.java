package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.surface.LodVertex;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceIndices;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceTile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * What the new renderer's rough terrain draws in a frame, worked out once and drawn by whichever output the device takes
 * ({@link OwnPassOutput}, {@link VanillaPassOutput}, or with a shader pack IrisOutput): the tiles in view, nearest first, and of each its chunk columns by the owners of
 * this frame ({@link OwnershipMap}):its own or nothing. Also what the shaders need: the frame's block and each tile's block.
 * each tile's block, and what the ring under vanilla's edge needs to know of its neighbours (see {@link #ringInfo}).
 * <p>
 * Everything is written before a pass opens, as nothing may be written to buffers while one is. Each tile is then one draw call per
 * layer and kind of column: its columns are index ranges, those next to each other joined. Render thread only.
 */
final class FrameDrawList implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The depth left for vanilla is pushed this much away, so its blocks at the same place win, see surface_depth.fsh. */
    private static final float VANILLA_DEPTH_PUSH = 1.004F;
    /**
     * Rough tiles are pushed this much away from the camera for the depth test, and finer ones less than coarser ones: the detailed
     * tiles win where they fade out over the rough terrain, and of a finer and a coarser tile of the same place, drawn both while the
     * finer one loads, the finer one wins. Scaling along the view ray keeps every pixel where it is.
     */
    private static final float DEPTH_PUSH = 1.01F;
    private static final float DEPTH_PUSH_PER_LEVEL = 0.002F;

    /** What the frame block holds, see surface.glsl. */
    private static final class FrameData implements DynamicGpuDataStorage.DynamicGpuData {
        static final int SIZE = 64 + 64 + 3 * 16;
        final Matrix4f view = new Matrix4f();
        final Matrix4f vanillaProjection = new Matrix4f();
        final Vector4f light = new Vector4f();
        final Vector4f sky = new Vector4f();
        final Vector4f params = new Vector4f();

        @Override
        public void write(ByteBuffer buffer) {
            this.view.get(buffer.position(), buffer);
            this.vanillaProjection.get(buffer.position() + 64, buffer);
            this.light.get(buffer.position() + 128, buffer);
            this.sky.get(buffer.position() + 144, buffer);
            this.params.get(buffer.position() + 160, buffer);
            buffer.position(buffer.position() + SIZE);
        }
    }

    /**
     * What the tile block holds, see surface.glsl. Kept from frame to frame, two per tile drawn: for its own columns, and for the
     * background under vanilla, which the debug build can tint.
     */
    private static final class TileData implements DynamicGpuDataStorage.DynamicGpuData {
        static final int SIZE = 48;
        float x;
        float y;
        float z;
        float scale;
        float tint;
        float push;

        @Override
        public void write(ByteBuffer buffer) {
            buffer.putFloat(this.x).putFloat(this.y).putFloat(this.z).putFloat(this.scale);
            // The background is tinted magenta, half way.
            buffer.putFloat(1.0F).putFloat(0.0F).putFloat(1.0F).putFloat(this.tint * 0.5F);
            buffer.putFloat(this.push).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        }
    }

    private final LodManager manager;
    /** Made with the first frame drawn: the GPU is not there yet when the mod starts. */
    private @Nullable DynamicGpuDataStorageMapped<FrameData> frames;
    private @Nullable DynamicGpuDataStorageMapped<TileData> tileStorage;
    private final FrameData frame = new FrameData();
    private TileData[] tileData = new TileData[0];
    private TileData[] written = new TileData[0];
    private GpuBufferSlice @Nullable [] tileSlices;
    private @Nullable GpuBufferSlice frameSlice;
    /** The shared triangles of the ground, one list per number of chunk columns, see {@link SurfaceIndices}; made once. */
    private final @Nullable GpuBuffer[] groundIndices = new GpuBuffer[5];
    /** The tiles drawn this frame, nearest first, and what each draws of its chunk columns, see {@link OwnershipMap#part}. */
    private final ArrayList<TileMesh> tiles = new ArrayList<>();
    private byte[][] parts = new byte[0][];
    private final OwnershipMap ownership = new OwnershipMap();
    /** The seam sewn along the border of vanilla's columns, see BorderSeam. / Шов, сшитый вдоль границы колонок ваниллы, см. BorderSeam. */
    private final BorderSeam seam = new BorderSeam();
    /** Scratch for the seam's draw runs of one call, and how many numbers of it are filled. / Черновик для отрезков отрисовки шва на один вызов и сколько чисел в нём заполнено. */
    private int[] seamRunScratch = new int[256];
    private int seamRunCount;
    /** The frame's detailed tiles with a kept edge, for the seam to sew the rough terrain to (see BorderSeam). / Детальные тайлы кадра с сохранённой кромкой, чтобы шов сшивал к ним дальний ландшафт (см. BorderSeam). */
    private final List<TileMesh> seamDetailTiles = new ArrayList<>();
    private boolean[] vanillaColumns = new boolean[0];
    private boolean[] detailedColumns = new boolean[0];
    /** The span of the ownership map and the inputs its columns were last filled from; they are refilled only when one changes. / Размах карты владения и вводные, из которых её колонки заполнялись в последний раз; они перезаполняются, только когда что-то из них меняется. */
    private int ownerHalf = -1;
    private int ownerOriginX;
    private int ownerOriginZ;
    private int ownerMaskVersion = -1;
    private int ownerDetailVersion = -1;
    /** What one draw call of a tile's ranges takes, kept. */
    private PointerBuffer offsets = MemoryUtil.memAllocPointer(256);
    private IntBuffer counts = MemoryUtil.memAllocInt(256);
    private IntBuffer baseVertices = MemoryUtil.memCallocInt(256);
    /** Whether the device draws a tile's ranges in one call, given as separate lists (OpenGL); 0 not asked yet, 1 yes, 2 no. */
    private int separateMultiDraw;
    /** The camera of the frame, for outputs that place the tiles themselves (IrisOutput). */
    private double camX;
    private double camY;
    private double camZ;

    FrameDrawList(LodManager manager) {
        this.manager = manager;
    }

    /** What to draw of the rough tiles of the new mesh among {@code visible} (nearest first), and the blocks of the frame and the tiles. */
    void prepare(
        List<TileMesh> visible, double camX, double camY, double camZ, Matrix4fc viewRotation, Matrix4fc vanillaProjection, boolean zeroToOne,
        Vector4f light, boolean overworldSky, float noSkyRed, float noSkyGreen, float noSkyBlue, float canopyStart, float waterBlur
    ) {
        if (this.frames == null) {
            this.frames = new DynamicGpuDataStorageMapped<>("FarPlane surface frame", FrameData.SIZE, GpuBuffer.USAGE_UNIFORM, 1);
            this.tileStorage = new DynamicGpuDataStorageMapped<>("FarPlane surface tiles", TileData.SIZE, GpuBuffer.USAGE_UNIFORM, 128);
        }
        this.frames.endFrame();
        this.tileStorage.endFrame();
        this.seam.beginFrame();
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.tiles.clear();
        this.seamDetailTiles.clear();
        for (TileMesh mesh : visible) {
            if (mesh.surface != null) {
                this.tiles.add(mesh);
            } else if (!IrisGlue.packMeshes && mesh.detail && mesh.detailEdges != null) {
                // The seam sews to the detailed tiles the frame draws. Under a shader pack none are drawn yet, so none are
                // handed over; when packs learn to draw them, hand them over here and the seam follows on its own.
                // / Шов шьётся к детальным тайлам, которые рисует кадр. Под шейдерным паком они пока не рисуются, поэтому не
                // передаются; когда паки научатся их рисовать, передавать их надо будет здесь, и шов последует сам.
                this.seamDetailTiles.add(mesh);
            }
        }
        this.tileSlices = null;
        this.frameSlice = null;
        if (this.tiles.isEmpty()) {
            return;
        }
        FrameData frame = this.frame;
        frame.view.set(viewRotation);
        frame.vanillaProjection.set(vanillaProjection);
        frame.light.set(light);
        frame.sky.set(noSkyRed, noSkyGreen, noSkyBlue, overworldSky ? 1.0F : 0.0F);
        frame.params.set(canopyStart, waterBlur, zeroToOne ? 1.0F : 0.0F, VANILLA_DEPTH_PUSH);
        this.frameSlice = this.frames.writeData(frame);

        LodManager.ChunkMask mask = this.manager.chunkMask();
        this.updateOwnership(mask);

        int count = this.tiles.size();
        if (this.tileData.length < count) {
            TileData[] grown = Arrays.copyOf(this.tileData, Math.max(count, this.tileData.length * 2));
            for (int i = this.tileData.length; i < grown.length; i++) {
                grown[i] = new TileData();
            }
            this.tileData = grown;
        }
        if (this.parts.length < count) {
            this.parts = Arrays.copyOf(this.parts, Math.max(count, this.parts.length * 2));
        }
        // The storage writes an array as long as it is: one of that length, kept while the count stays.
        if (this.written.length != count) {
            this.written = new TileData[count];
        }
        TileData[] written = this.written;
        for (int i = 0; i < count; i++) {
            TileMesh mesh = this.tiles.get(i);
            SurfaceTile surface = mesh.surface;
            TileData data = this.tileData[i];
            data.x = (float) (mesh.pos.minX() - camX);
            data.y = (float) (surface.baseY() - camY);
            data.z = (float) (mesh.pos.minZ() - camZ);
            data.scale = (1 << mesh.pos.level()) / LodVertex.XZ_UNITS;
            data.tint = 0.0F;
            data.push = DEPTH_PUSH + DEPTH_PUSH_PER_LEVEL * mesh.pos.level();
            written[i] = data;
            int groups = surface.groups() * surface.groups();
            if (this.parts[i] == null || this.parts[i].length < groups) {
                this.parts[i] = new byte[Math.max(groups, 16)];
            }
            this.parts(mesh, mask, camY, this.parts[i]);
        }
        this.seam.update(Minecraft.getInstance().level, this.ownership, this.tiles, this.parts, this.seamDetailTiles, camX, camY, camZ);
        this.tileSlices = this.tileStorage.writeData(written);
    }

    boolean hasTiles() {
        return this.frameSlice != null;
    }

    /** The tiles of the frame, nearest first; read only. */
    List<TileMesh> tiles() {
        return this.tiles;
    }

    /** What the {@code i}th tile draws of its chunk columns, see {@link #parts(TileMesh, LodManager.ChunkMask, double, byte[])}. */
    byte[] parts(int i) {
        return this.parts[i];
    }

    double camX() {
        return this.camX;
    }

    double camY() {
        return this.camY;
    }

    double camZ() {
        return this.camZ;
    }

    /** The byte offsets of the ranges {@link #ranges} found. */
    PointerBuffer rangeOffsets() {
        return this.offsets;
    }

    /** The index counts of the ranges {@link #ranges} found. */
    IntBuffer rangeCounts() {
        return this.counts;
    }

    /** The owners of this frame, from what vanilla and the detailed tiles draw, see {@link OwnershipMap}. */
    private void updateOwnership(LodManager.ChunkMask mask) {
        int half = mask.half();
        if (half < 0 || mask.values().length != (2 * half + 1) * (2 * half + 1)) {
            this.ownership.update(-1, 0, 0, this.vanillaColumns, this.detailedColumns, 0L);
            return;
        }
        // The map spans more than the chunk mask. The mask is made around vanilla's drawn chunks and ends a few chunks past
        // them, while the detailed ring reaches much farther; outside a map every column counts as the rough terrain's own,
        // so the detailed tiles past the mask's edge kept their rough terrain - its ground, its water and its tree tops -
        // because no one told the map they were there. The map stays centred on the mask, wide enough for the whole ring.
        // / Карта шире маски чанков. Маска строится вокруг рисуемых ваниллой чанков и кончается в нескольких чанках за ними,
        // а детальное кольцо тянется намного дальше; вне карты каждая колонка считается собственной для дальнего ландшафта,
        // поэтому детальные тайлы за краем маски сохраняли свой дальний ландшафт - его землю, его воду и его верхушки
        // деревьев: никто не говорил карте, что они там. Карта остаётся центрированной на маске и достаточно широкой для
        // всего кольца.
        int ring = (int) Math.ceil(this.manager.detailRingRadius() / 16.0);
        int wide = Math.max(half, ring) + 2;
        int detailVersion = this.manager.detailVersion();
        if (wide != this.ownerHalf || mask.originX() != this.ownerOriginX || mask.originZ() != this.ownerOriginZ
            || mask.version() != this.ownerMaskVersion || detailVersion != this.ownerDetailVersion) {
            this.ownerHalf = wide;
            this.ownerOriginX = mask.originX();
            this.ownerOriginZ = mask.originZ();
            this.ownerMaskVersion = mask.version();
            this.ownerDetailVersion = detailVersion;
            this.fillColumns(mask, wide);
        }
        this.ownership.update(wide, mask.originX(), mask.originZ(), this.vanillaColumns, this.detailedColumns, Util.getMillis());
    }

    /**
     * The vanilla and detailed columns of the widened map: both from the chunk mask where it reaches, and the detailed ones
     * past its edge from the detailed tiles themselves, marked from their coverage the way the mask marks them (see
     * LodManager's own marking of the same tiles).
     * <p>
     * Колонки ваниллы и детальных тайлов расширенной карты: обе - из маски чанков, где она достаёт, а детальные за её краем -
     * из самих детальных тайлов, отмеченные по их покрытию так, как их отмечает маска (см. собственную разметку тех же
     * тайлов в LodManager).
     */
    private void fillColumns(LodManager.ChunkMask mask, int wide) {
        int side = 2 * wide + 1;
        if (this.vanillaColumns.length != side * side) {
            this.vanillaColumns = new boolean[side * side];
            this.detailedColumns = new boolean[side * side];
        } else {
            Arrays.fill(this.vanillaColumns, false);
            Arrays.fill(this.detailedColumns, false);
        }
        int maskHalf = mask.half();
        int maskSide = 2 * maskHalf + 1;
        int[] values = mask.values();
        for (int z = 0; z < side; z++) {
            int mz = z - wide + maskHalf;
            if (mz < 0 || mz >= maskSide) {
                continue;
            }
            for (int x = 0; x < side; x++) {
                int mx = x - wide + maskHalf;
                if (mx < 0 || mx >= maskSide) {
                    continue;
                }
                int value = values[mx + mz * maskSide];
                int at = x + z * side;
                this.vanillaColumns[at] = (value & (LodManager.ChunkMask.VANILLA | LodManager.ChunkMask.VANILLA_EDGE)) != 0;
                this.detailedColumns[at] = (value & LodManager.ChunkMask.DETAILED) != 0;
            }
        }
        int square = FarPlaneConstants.tileSize(FarPlaneConstants.DETAIL_MIN_LEVEL);
        int perSquare = square / 16;
        for (TileMesh mesh : this.manager.detailTiles()) {
            if (mesh.vertices.isClosed()) {
                continue;
            }
            int squares = Math.max(1, mesh.pos.size() / square);
            for (int s = 0; s < squares * squares; s++) {
                if ((mesh.coverage & 1L << s) == 0) {
                    continue;
                }
                long squareX = mesh.pos.minX() + (long) (s % squares) * square;
                long squareZ = mesh.pos.minZ() + (long) (s / squares) * square;
                for (int c = 0; c < perSquare * perSquare; c++) {
                    long chunkMinX = squareX + (long) (c % perSquare) * 16L;
                    long chunkMinZ = squareZ + (long) (c / perSquare) * 16L;
                    int x = (int) Math.floorDiv(chunkMinX, 16L) - mask.originX() + wide;
                    int z = (int) Math.floorDiv(chunkMinZ, 16L) - mask.originZ() + wide;
                    if (x >= 0 && z >= 0 && x < side && z < side) {
                        this.detailedColumns[x + z * side] = true;
                    }
                }
            }
        }
    }

    /**
     * What a tile draws of each of its chunk columns, by the owners of this frame. A tile farther above or below the camera than vanilla
     * draws sections keeps vanilla's columns - vanilla draws no ground of it there (seen from high above, for one) - but still leaves
     * out the detailed tiles' columns: the detailed tiles draw at any height, so theirs are cut at any height too.
     * <p>
     * Что тайл рисует из каждой своей колонки чанков, по владельцам этого кадра. Тайл, который выше или ниже камеры дальше, чем
     * ванилла рисует секции, сохраняет колонки ваниллы - ванилла там её землю не рисует (например, при взгляде с большой высоты), -
     * но по-прежнему вырезает колонки детальных тайлов: детальные тайлы рисуются на любой высоте, поэтому и вырезаются на любой.
     */
    private void parts(TileMesh mesh, LodManager.ChunkMask mask, double camY, byte[] parts) {
        SurfaceTile surface = mesh.surface;
        int groups = surface.groups();
        Arrays.fill(parts, 0, groups * groups, OwnershipMap.OWN);
        if (groups == 1) {
            return;
        }
        boolean withinReach = camY - mesh.maxY < mask.reach() && mesh.minY - camY < mask.reach();
        long firstChunkX = Math.floorDiv(mesh.pos.minX(), 16L);
        long firstChunkZ = Math.floorDiv(mesh.pos.minZ(), 16L);
        for (int gz = 0; gz < groups; gz++) {
            for (int gx = 0; gx < groups; gx++) {
                long chunkX = firstChunkX + gx;
                long chunkZ = firstChunkZ + gz;
                parts[gx + gz * groups] = withinReach
                    ? this.ownership.part(chunkX, chunkZ)
                    : this.ownership.owner(chunkX, chunkZ) == OwnershipMap.DETAILED ? OwnershipMap.NONE : OwnershipMap.OWN;
            }
        }
    }


    /**
     * Draws a layer of every tile: the ground ({@code water} false) nearest first, or the water farthest first, so it blends over what
     * lies behind it; of each tile the columns of kind {@code part} (OwnershipMap.OWN), only those within {@code distance}
     * of the camera. The pipeline is set here; what else the pass needs (the projection, the fog) the output binds.
     */
    void draw(RenderPass pass, @Nullable CompiledRenderPipeline pipeline, boolean water, byte part, double distance) {
        GpuBufferSlice[] slices = this.tileSlices;
        if (pipeline == null || slices == null || this.frameSlice == null) {
            return;
        }
        pass.setPipeline(pipeline);
        pass.setUniform("SurfaceFrame", this.frameSlice);
        pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        GpuBuffer bound = null;
        int count = this.tiles.size();
        for (int n = 0; n < count; n++) {
            int i = water ? count - 1 - n : n;
            TileMesh mesh = this.tiles.get(i);
            SurfaceTile surface = mesh.surface;
            // The tiles for a shader pack are IrisOutput's, in its vertex format.
            if (mesh.vertices.isClosed() || surface.pack() || water && !surface.hasWater() || mesh.sortDistance >= distance) {
                continue;
            }
            GpuBuffer indices;
            long base;
            int[] ranges;
            if (water || !surface.sharedGround()) {
                indices = mesh.vertices.buffer;
                base = mesh.vertices.offset + surface.vertexBytes();
                ranges = water ? surface.waterOffsets() : surface.groundOffsets();
            } else {
                indices = this.groundIndices(surface.groups());
                base = 0L;
                ranges = surface.groundOffsets();
            }
            int draws = this.ranges(ranges, this.parts[i], base, part);
            if (draws == 0) {
                continue;
            }
            if (indices != bound) {
                pass.setIndexBuffer(indices, IndexType.SHORT);
                bound = indices;
            }
            pass.setUniform("SurfaceTile", slices[i]);
            pass.setVertexBuffer(0, mesh.vertexSlice());
            if (this.separateMultiDraw()) {
                pass.multiDrawIndexed(this.offsets, this.counts, this.baseVertices, draws);
            } else {
                // Vulkan has the ranges in another layout, which nothing in the game uses yet: one call each, a few per tile at most,
                // as the chunk columns next to each other are joined.
                for (int d = 0; d < draws; d++) {
                    pass.drawIndexed(this.counts.get(d), 1, (int) (this.offsets.get(d) / 2L), 0, 0);
                }
            }
            if (PerfLog.ON) {
                for (int d = 0; d < draws; d++) {
                    PerfLog.draw(this.counts.get(d));
                }
            }
        }
    }

    /**
     * The index ranges of the chunk columns of a tile of one kind, those next to each other joined, into {@link #offsets} (in bytes)
     * and {@link #counts}; returns how many.
     */
    int ranges(int[] groupOffsets, byte[] parts, long base, byte part) {
        int groups = groupOffsets.length - 1;
        if (this.counts.capacity() < groups) {
            MemoryUtil.memFree(this.counts);
            MemoryUtil.memFree(this.baseVertices);
            MemoryUtil.memFree(this.offsets);
            this.offsets = MemoryUtil.memAllocPointer(groups);
            this.counts = MemoryUtil.memAllocInt(groups);
            this.baseVertices = MemoryUtil.memCallocInt(groups);
        }
        int draws = 0;
        int start = -1;
        for (int g = 0; g <= groups; g++) {
            boolean take = g < groups && parts[g] == part && groupOffsets[g + 1] > groupOffsets[g];
            if (take && start < 0) {
                start = g;
            } else if (!take && start >= 0) {
                int from = groupOffsets[start];
                int count = groupOffsets[g] - from;
                if (count > 0) {
                    this.offsets.put(draws, base + 2L * from);
                    this.counts.put(draws, count);
                    draws++;
                }
                start = -1;
            }
        }
        return draws;
    }
    /** The border seam's mesh for a shader pack, see BorderSeam. / Меш пограничного шва для шейдерного пака, см. BorderSeam. */
    BorderSeam.@Nullable PackMesh seamPack() {
        return this.seam.packMesh();
    }

    /** Works this frame's seam draw runs out (see BorderSeam.runs) into the scratch. / Считает отрезки отрисовки шва этого кадра (см. BorderSeam.runs) в черновик. */
    private void computeSeamRuns() {
        int need = this.seam.builtSegments() * 2;
        if (this.seamRunScratch.length < need) {
            this.seamRunScratch = new int[Math.max(need, 256)];
        }
        this.seamRunCount = this.seam.runs(this.ownership, this.seamRunScratch);
    }

    /** The seam's draw runs for a shader pack, in triangles (see BorderSeam.runs); {@link #seamRunLength} says how many numbers are filled. / Отрезки отрисовки шва для шейдерного пака, в треугольниках (см. BorderSeam.runs); {@link #seamRunLength} говорит, сколько чисел заполнено. */
    int[] seamPackRuns() {
        this.computeSeamRuns();
        return this.seamRunScratch;
    }

    /** How many numbers of {@link #seamPackRuns} are filled. / Сколько чисел в {@link #seamPackRuns} заполнено. */
    int seamRunLength() {
        return this.seamRunCount;
    }

    /**
     * Draws the border seam (see BorderSeam) with one of the surface's pipelines, the ground's or the depth's: for the pass the
     * seam is one more tile, with its own buffers and its own tile block.
     * <p>
     * Рисует пограничный шов (см. BorderSeam) одним из конвейеров поверхности, земли или глубины: для прохода шов - ещё один тайл,
     * со своими буферами и своим блоком тайла.
     */
    void drawSeam(RenderPass pass, @Nullable CompiledRenderPipeline pipeline) {
        GpuBufferSlice slice = this.seam.blockSlice();
        GpuBufferSlice vertices = this.seam.ownVertices();
        GpuBuffer buffer = this.seam.ownBuffer();
        if (pipeline == null || this.frameSlice == null || slice == null || vertices == null || buffer == null || !this.seam.hasMesh()) {
            return;
        }
        pass.setPipeline(pipeline);
        pass.setUniform("SurfaceFrame", this.frameSlice);
        pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setUniform("SurfaceTile", slice);
        // Only the segments whose neighbour is drawn this frame (see BorderSeam.runs): a segment sewn ahead for a chunk
        // that is loaded but not drawn yet waits in the mesh until its frame comes.
        // / Только отрезки, чей сосед рисуется в этом кадре (см. BorderSeam.runs): отрезок, сшитый заранее для загруженного,
        // но ещё не рисуемого чанка, ждёт в меше, пока не придёт его кадр.
        this.computeSeamRuns();
        if (this.seamRunCount == 0) {
            return;
        }
        pass.setVertexBuffer(0, vertices);
        pass.setIndexBuffer(buffer, IndexType.INT);
        int total = this.seam.ownIndexCount() / 6;
        int drawn = 0;
        for (int block = 0; block < 2; block++) {
            for (int r = 0; r + 1 < this.seamRunCount; r += 2) {
                int count = this.seamRunScratch[r + 1] * 3;
                pass.drawIndexed(count, 1, this.seam.ownFirstIndex() + (block * total + this.seamRunScratch[r]) * 3, 0, 0);
                drawn += count;
            }
        }
        if (PerfLog.ON) {
            PerfLog.draw(drawn);
        }
    }
    private boolean separateMultiDraw() {
        if (this.separateMultiDraw == 0) {
            this.separateMultiDraw = RenderSystem.getDevice().getDeviceInfo().features().multiDrawDirectSeparate() ? 1 : 2;
        }
        return this.separateMultiDraw == 1;
    }

    private GpuBuffer groundIndices(int groups) {
        int slot = Integer.numberOfTrailingZeros(groups);
        GpuBuffer buffer = this.groundIndices[slot];
        if (buffer == null) {
            ByteBuffer data = SurfaceIndices.ground(groups).indices();
            buffer = RenderSystem.getDevice().createBuffer(() -> "FarPlane surface ground indices x" + groups, GpuBuffer.USAGE_INDEX, data);
            this.groundIndices[slot] = buffer;
        }
        return buffer;
    }

    @Override
    public void close() {
        if (this.frames != null) {
            this.frames.close();
            this.tileStorage.close();
            this.frames = null;
            this.tileStorage = null;
        }
        for (int i = 0; i < this.groundIndices.length; i++) {
            if (this.groundIndices[i] != null) {
                this.groundIndices[i].close();
                this.groundIndices[i] = null;
            }
        }
        this.seam.close();
        MemoryUtil.memFree(this.offsets);
        MemoryUtil.memFree(this.counts);
        MemoryUtil.memFree(this.baseVertices);
    }
}
