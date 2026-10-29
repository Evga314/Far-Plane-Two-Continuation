package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.surface.LodVertex;
import com.Evga314.farplanetwocontinuation.client.lod.surface.PackMesher;
import com.Evga314.farplanetwocontinuation.tile.TilePos;
import com.Evga314.farplanetwocontinuation.client.lod.surface.SurfaceMesh;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.DynamicGpuDataStorageMapped;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The seam between vanilla's chunks and the rough terrain: a strip of triangles sewn into the wedge between the two surfaces along
 * the border of what vanilla draws (see {@link OwnershipMap}), so no sky shows through where the rough surface ends lower or higher
 * than vanilla's edge. One side of the strip follows the rough tile's own edge samples exactly as they are drawn, the other follows
 * vanilla's edge column by column; where the two profiles meet, the strip's triangles shrink to nothing. It is sewn again only when
 * the border or the tiles along it change (and now and then besides, in case a border chunk was edited), and drawn by every output:
 * with the surface pipelines in {@link LodVertex} format, and with a shader pack in the pack's format (see IrisOutput).
 * <p>
 * Шов между чанками ваниллы и дальним ландшафтом: полоса треугольников, вшитая в клин между двумя поверхностями вдоль границы того,
 * что рисует ванилла (см. {@link OwnershipMap}), чтобы небо не просвечивало там, где грубая поверхность обрывается ниже или выше
 * кромки ваниллы. Одна сторона полосы идёт точно по кромочным отсчётам грубого тайла, как они нарисованы, другая - по кромке ваниллы
 * колонка за колонкой; где профили сходятся, треугольники полосы вырождаются в ноль. Перешивается только когда меняется граница или
 * тайлы вдоль неё (и время от времени просто так, на случай правки приграничного чанка), а рисуется всеми выходами: конвейерами
 * поверхности в формате {@link LodVertex}, а с шейдерным паком - в формате пака (см. IrisOutput).
 * <p>
 * Render thread only. / Только поток отрисовки.
 */
final class BorderSeam implements AutoCloseable {
    // Directions of a chunk's sides: +x, -x, +z, -z, as in VanillaWall. / Направления сторон чанка: +x, -x, +z, -z, как в VanillaWall.
    private static final int[] SIDE_X = {1, -1, 0, 0};
    private static final int[] SIDE_Z = {0, 0, 1, -1};
    /**
     * How far the seam's plane is shifted towards the rough terrain, and how far its ends reach past their ends along the border:
     * where two surfaces only met in one plane, a pixel right on their shared line was now and then covered by neither (the same
     * overlap as VanillaWall's walls). / На сколько плоскость шва сдвинута к дальнему ландшафту и на сколько его концы заходят за
     * концы отрезка вдоль границы: где две поверхности только встречались в одной плоскости, пиксель ровно на их общей линии порой не
     * покрывался ни одной (тот же заход, что у стен VanillaWall).
     */
    private static final float OVERLAP = 0.02F;
    /** How deep under its top a vanilla column is looked through for its ground. / На какую глубину под верхом просматривается колонка ваниллы в поисках её земли. */
    private static final int MAX_DEPTH = 64;
    /** Vanilla's water fills its top block this far. / Вода ваниллы заполняет свой верхний блок на столько. */
    private static final float VANILLA_WATER_FILL = 8.0F / 9.0F;
    /** The seam is sewn again at least this often while it exists, in case a border chunk changed under it. / Шов перешивается не реже, чем раз в столько, пока существует, на случай, если под ним изменился приграничный чанк. */
    private static final long RECHECK_MS = 500L;
    // Faces as Distant Horizons numbers them, the same table as in PackMesher. / Грани в нумерации Distant Horizons, та же таблица, что в PackMesher.
    private static final int FACE_NORTH = 2;
    private static final int FACE_SOUTH = 3;
    private static final int FACE_WEST = 4;
    private static final int FACE_EAST = 5;

    /**
     * What a rough tile keeps of its samples for the seam: per sample the drawn height and the level of its water, two unsigned
     * shorts in one int (the height low), its color and its material and flags; and per sample of its four edges the bottom of its
     * skirt, packed the same way, edge by edge as the mesh writes them (north, south, west, east); see {@link #extract}. Kept on
     * the CPU only.
     * <p>
     * Что грубый тайл хранит от своих отсчётов для шва: на отсчёт нарисованная высота и уровень его воды, два беззнаковых short в
     * одном int (высота - младший), его цвет и его материал с флагами; и на отсчёт его четырёх краёв - низ его юбки, упаковано так
     * же, край за краем, как их пишет меш (север, юг, запад, восток); см. {@link #extract}. Хранится только в обычной памяти.
     */
    record Samples(int[] heights, int[] colors, byte[] bits, int[] skirts) {
    }

    /**
     * Takes the seam's data out of a rough tile's own-format mesh: the grid vertices are written first, in sample order (see
     * SurfaceMesher), so sample {@code i} is vertex {@code i}, and the bottoms of the skirts follow them, edge by edge. Null for
     * the floating islands of the End, whose vertices are laid out differently; the seam is not sewn from them. Called in the
     * mesh builder threads.
     * <p>
     * Достаёт данные шва из меша грубого тайла в собственном формате: вершины решётки пишутся первыми, в порядке отсчётов (см.
     * SurfaceMesher), поэтому отсчёт {@code i} - это вершина {@code i}, а за ними идут низы юбок, край за краем. Null для
     * плавающих островов Края, чьи вершины разложены иначе; из них шов не шьётся. Вызывается в потоках построения мешей.
     */
    static @Nullable Samples extract(@Nullable SurfaceMesh mesh) {
        if (mesh == null || !mesh.sharedGround()) {
            return null;
        }
        int side = FarPlaneConstants.TILE_SAMPLES;
        int count = side * side;
        if (mesh.vertexCount() < count + 4 * side) {
            return null;
        }
        ByteBuffer vertices = mesh.vertices();
        int[] heights = new int[count];
        int[] colors = new int[count];
        byte[] bits = new byte[count];
        for (int i = 0; i < count; i++) {
            int at = i * LodVertex.SIZE;
            heights[i] = (vertices.getShort(at + LodVertex.Y) & 0xFFFF) | (vertices.getShort(at + LodVertex.WATER_LEVEL) & 0xFFFF) << 16;
            colors[i] = rgba(vertices, at + LodVertex.COLOR);
            bits[i] = vertices.get(at + LodVertex.BITS);
        }
        int[] skirts = new int[4 * side];
        for (int i = 0; i < skirts.length; i++) {
            int at = (count + i) * LodVertex.SIZE;
            skirts[i] = (vertices.getShort(at + LodVertex.Y) & 0xFFFF) | (vertices.getShort(at + LodVertex.WATER_LEVEL) & 0xFFFF) << 16;
        }
        return new Samples(heights, colors, bits, skirts);
    }

    /** RGBA bytes as ARGB, as PackMesher reads them. / Байты RGBA как ARGB, как их читает PackMesher. */
    private static int rgba(ByteBuffer source, int at) {
        return (source.get(at + 3) & 0xFF) << 24 | (source.get(at) & 0xFF) << 16 | (source.get(at + 1) & 0xFF) << 8 | source.get(at + 2) & 0xFF;
    }

    /**
     * The block of one tile as the surface shaders read it (see surface.glsl), for the seam as a whole: its north-west corner at its
     * base height, from the camera, and how many blocks one unit of x and z is. The seam is pushed away like the finest tiles and
     * never tinted.
     * <p>
     * Блок одного тайла, как его читают шейдеры поверхности (см. surface.glsl), для шва целиком: его северо-западный угол на его
     * базовой высоте, от камеры, и сколько блоков в одной единице x и z. Шов отодвигается как самые подробные тайлы и никогда не
     * подкрашивается.
     */
    private static final class TileBlock implements DynamicGpuDataStorage.DynamicGpuData {
        static final int SIZE = 48;
        float x;
        float y;
        float z;
        float scale;

        @Override
        public void write(ByteBuffer buffer) {
            buffer.putFloat(this.x).putFloat(this.y).putFloat(this.z).putFloat(this.scale);
            buffer.putFloat(1.0F).putFloat(0.0F).putFloat(1.0F).putFloat(0.0F);
            buffer.putFloat(1.01F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
        }
    }

    /** The storage of the seam's tile block, made with the first frame drawn. / Хранилище блока тайла шва, создаётся с первым нарисованным кадром. */
    private @Nullable DynamicGpuDataStorageMapped<TileBlock> blocks;
    private final TileBlock block = new TileBlock();
    private @Nullable GpuBufferSlice blockSlice;

    /** The seam in the surface's own format: vertices, then its indices as ints. / Шов в собственном формате поверхности: вершины, затем его индексы как int. */
    private @Nullable GpuBuffer ownBuffer;
    private @Nullable GpuBufferSlice ownSlice;
    private int ownVertexBytes;
    private int ownIndexCount;
    /** The same seam in the pack's format, and where its vertices are counted from, in the world. / Тот же шов в формате пака и точка, от которой считаются его вершины, в мире. */
    private @Nullable GpuBuffer packBuffer;
    private int packVertexBytes;
    private int packIndexCount;
    private float packOriginX;
    private float packBaseY;
    private float packOriginZ;
    /** Where the own format's vertices are counted from, and how many blocks one of its units is. / Откуда считаются вершины собственного формата и сколько блоков в одной его единице. */
    private int originX;
    private int originZ;
    private float baseY;
    private int spacing = 1;
    /** Nothing to draw: no border, or no tile along it. / Рисовать нечего: нет границы или нет тайла вдоль неё. */
    private boolean empty = true;
    /** The last sewing left a segment out (a vanilla chunk was not there to read): it is sewn again at the next recheck. / В последнем шитье пропущен отрезок (чанка ваниллы не было, чтобы прочитать): он перешьётся при следующей перепроверке. */
    private boolean incomplete;

    /** The ownership the seam was last sewn for, and a print of what it was sewn from. / Владение, для которого шов шился в последний раз, и отпечаток того, из чего он шился. */
    private int builtVersion = -1;
    private long builtPrint = Long.MIN_VALUE;
    private long lastScanMs;

    // Scratch of one sewing, kept between sewings. / Черновик одного шитья, хранится между шитьями.
    /** Per segment found: the chunk, the direction to vanilla, and the tile of the frame's list that draws the chunk; four each. / На найденный отрезок: чанк, направление к ванилле и тайл из списка кадра, который рисует чанк; по четыре. */
    private int[] segDesc = new int[256];
    private int segCount;
    /** The points of the seam: their place in the world, the level of their water, their color and material, and the way the rough terrain lies from their segment. / Точки шва: их место в мире, уровень их воды, их цвет и материал и сторона, с которой от их отрезка лежит дальний ландшафт. */
    private float[] px = new float[1024];
    private float[] py = new float[1024];
    private float[] pz = new float[1024];
    private float[] pwl = new float[1024];
    private int[] pcolor = new int[1024];
    private byte[] pbits = new byte[1024];
    private float[] pnx = new float[1024];
    private float[] pnz = new float[1024];
    private int points;
    /** The triangles of the seam as triples of points, wound to face the rough terrain; the other side is the same, reversed. / Треугольники шва тройками точек, закрученные лицом к дальнему ландшафту; обратная сторона - те же, наоборот. */
    private int[] tris = new int[3072];
    private int triCount;
    /** The two profiles of one segment being sewn: ours at its samples, vanilla's at every block. / Два профиля одного отрезка в работе: наш по его отсчётам, ваниллы по каждому блоку. */
    private final float[] ourWX = new float[17];
    private final float[] ourWZ = new float[17];
    private final float[] ourT = new float[17];
    private final float[] ourY = new float[17];
    private final float[] ourWl = new float[17];
    private final int[] ourColor = new int[17];
    private final byte[] ourBits = new byte[17];
    private final float[] vanWX = new float[33];
    private final float[] vanWZ = new float[33];
    private final float[] vanT = new float[33];
    private final float[] vanY = new float[33];
    private final float[] vanWl = new float[33];
    /** How many points the step profile of the segment being sewn has, and the ground and water of its columns before the expansion. / Сколько точек у ступенчатого профиля отрезка в работе, и земля с водой его колонок до разворота. */
    private int vanCount;
    private final float[] colY = new float[17];
    private final float[] colWl = new float[17];
    /** The vanilla chunks read in this sewing, so a chunk bordering the seam on two sides is read once. / Чанки ваниллы, прочитанные в этом шитье, чтобы чанк, граничащий со швом с двух сторон, читался один раз. */
    private final Long2ObjectOpenHashMap<ChunkAccess> chunkCache = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ByteBuffer scratch = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
    private float minWX;
    private float minWZ;
    private float minY;
    private float maxWX;
    private float maxWZ;

    /** The mesh of the seam for a shader pack: its buffer, where its indices start in it, how many they are, and its origin. / Меш шва для шейдерного пака: его буфер, где в нём начинаются индексы, сколько их, и его начало. */
    record PackMesh(GpuBuffer buffer, int vertexBytes, int indexCount, float originX, float baseY, float originZ) {
    }

    /** Opens the frame's writing of the seam's tile block, as FrameDrawList does for its own. / Открывает запись блока тайла шва на кадр, как FrameDrawList делает для своих. */
    void beginFrame() {
        if (this.blocks == null) {
            this.blocks = new DynamicGpuDataStorageMapped<>("FarPlane border seam", TileBlock.SIZE, GpuBuffer.USAGE_UNIFORM, 1);
        }
        this.blocks.endFrame();
    }

    /**
     * Sews the seam again when what it is sewn from has changed, and writes its tile block for this frame. Called from
     * {@link FrameDrawList#prepare} with the frame's tiles and what each of them draws of its chunk columns.
     * <p>
     * Перешивает шов, когда изменилось то, из чего он шьётся, и пишет его блок тайла на этот кадр. Вызывается из
     * {@link FrameDrawList#prepare} с тайлами кадра и тем, что каждый из них рисует от своих чанков-колонок.
     */
    void update(@Nullable ClientLevel level, OwnershipMap ownership, List<TileMesh> tiles, byte[][] parts, double camX, double camY, double camZ) {
        this.blockSlice = null;
        if (level == null || ownership.half() < 0) {
            this.empty = true;
            this.builtVersion = -1;
            this.builtPrint = Long.MIN_VALUE;
            return;
        }
        long now = Util.getMillis();
        boolean due = now - this.lastScanMs >= RECHECK_MS;
        if (ownership.version() != this.builtVersion || due) {
            this.lastScanMs = now;
            this.builtVersion = ownership.version();
            long print = this.scan(ownership, tiles, parts);
            if (print != this.builtPrint || due && this.incomplete) {
                this.builtPrint = print;
                this.build(level, tiles);
            }
        }
        if (!this.empty && this.blocks != null) {
            TileBlock data = this.block;
            data.x = (float) (this.originX - camX);
            data.y = (float) (this.baseY - camY);
            data.z = (float) (this.originZ - camZ);
            data.scale = this.spacing / LodVertex.XZ_UNITS;
            this.blockSlice = this.blocks.writeData(data);
        }
    }

    /**
     * Finds the segments of the seam: the chunk columns the rough terrain draws as its own, with vanilla next door, settled or
     * being handed over this very frame. For
     * every one, the tile of the frame that draws the column at the finest level there, or none (the segment is then left out: a
     * tile still loading shows the usual pop-in, not a hole sewn to nothing). Returns a print of the segments and their tiles.
     * <p>
     * Находит отрезки шва: чанки-колонки, которые дальний ландшафт рисует как свои, с устоявшейся ваниллой по соседству. Для каждой -
     * тайл кадра, который рисует колонку на самом подробном уровне там, или никакой (тогда отрезок пропускается: ещё строящийся тайл
     * показывает обычный поп-ин, а не дыру, пришитую к пустоте). Возвращает отпечаток отрезков и их тайлов.
     */
    private long scan(OwnershipMap ownership, List<TileMesh> tiles, byte[][] parts) {
        this.segCount = 0;
        HashMap<Long, Integer> byPos = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            byPos.put(tiles.get(i).pos.pack(), i);
        }
        long print = 0x5EEDL + ownership.version();
        int half = ownership.half();
        int side = 2 * half + 1;
        for (int mz = 0; mz < side; mz++) {
            for (int mx = 0; mx < side; mx++) {
                int cx = ownership.originX() - half + mx;
                int cz = ownership.originZ() - half + mz;
                if (ownership.part(cx, cz) != OwnershipMap.OWN) {
                    continue;
                }
                for (int dir = 0; dir < 4; dir++) {
                    // Vanilla's column counts from the very frame Sodium draws it (HANDOVER), not once it has stood for
                    // HANDOVER_MS: the wedge along its edge is open from that frame, and the seam closes it in the same frame.
                    // The map itself is fed frame by frame from what Sodium draws (see LodManager.frameCheck and SodiumFrame),
                    // so no waiting is built into the seam.
                    // / Колонка ваниллы считается с того самого кадра, как Sodium её нарисовал (HANDOVER), а не когда она
                    // простояла HANDOVER_MS: клин вдоль её кромки открыт с этого кадра, и шов закрывает его в том же кадре.
                    // Сама карта питается покадрово тем, что рисует Sodium (см. LodManager.frameCheck и SodiumFrame), поэтому
                    // ожидания в шов не встроено.
                    byte neighbour = ownership.owner(cx + SIDE_X[dir], cz + SIDE_Z[dir]);
                    if (neighbour != OwnershipMap.VANILLA && neighbour != OwnershipMap.HANDOVER) {
                        continue;
                    }
                    int tile = this.covering(byPos, tiles, parts, cx, cz);
                    if (tile < 0) {
                        continue;
                    }
                    if (this.segCount * 4 + 4 > this.segDesc.length) {
                        int[] grown = new int[this.segDesc.length * 2];
                        System.arraycopy(this.segDesc, 0, grown, 0, this.segDesc.length);
                        this.segDesc = grown;
                    }
                    int at = this.segCount * 4;
                    this.segDesc[at] = cx;
                    this.segDesc[at + 1] = cz;
                    this.segDesc[at + 2] = dir;
                    this.segDesc[at + 3] = tile;
                    this.segCount++;
                    TileMesh mesh = tiles.get(tile);
                    print = print * 31L + mesh.pos.pack();
                    print = print * 31L + mesh.vertices.offset;
                }
            }
        }
        return print;
    }

    /** The frame tile that draws chunk {@code cx, cz} as its own, the finest first; -1 when none does. / Тайл кадра, который рисует чанк {@code cx, cz} как свой, сначала самый подробный; -1, если такого нет. */
    private int covering(HashMap<Long, Integer> byPos, List<TileMesh> tiles, byte[][] parts, int cx, int cz) {
        for (int level = 0; level <= FarPlaneConstants.MAX_LEVEL; level++) {
            int size = FarPlaneConstants.tileSize(level);
            int tileX = (int) Math.floorDiv((long) cx * 16L, (long) size);
            int tileZ = (int) Math.floorDiv((long) cz * 16L, (long) size);
            Integer index = byPos.get(TilePos.pack(level, tileX, tileZ));
            if (index == null) {
                continue;
            }
            TileMesh mesh = tiles.get(index);
            if (mesh.surface == null || mesh.seamHeights == null) {
                continue;
            }
            int groups = mesh.surface.groups();
            int gx = cx - (int) Math.floorDiv(mesh.pos.minX(), 16L);
            int gz = cz - (int) Math.floorDiv(mesh.pos.minZ(), 16L);
            if (gx < 0 || gz < 0 || gx >= groups || gz >= groups) {
                continue;
            }
            if (parts[index][gx + gz * groups] == OwnershipMap.OWN) {
                return index;
            }
        }
        return -1;
    }

    /** Sews the segments {@link #scan} found into points and triangles, and puts them on the GPU in both formats. / Шьёт отрезки, найденные {@link #scan}, в точки и треугольники и кладёт их на видеокарту в обоих форматах. */
    private void build(ClientLevel level, List<TileMesh> tiles) {
        this.points = 0;
        this.triCount = 0;
        this.chunkCache.clear();
        this.minWX = Float.MAX_VALUE;
        this.minWZ = Float.MAX_VALUE;
        this.minY = Float.MAX_VALUE;
        this.maxWX = -Float.MAX_VALUE;
        this.maxWZ = -Float.MAX_VALUE;
        boolean dropped = false;
        for (int s = 0; s < this.segCount; s++) {
            int at = s * 4;
            dropped |= !this.segment(level, tiles.get(this.segDesc[at + 3]), this.segDesc[at], this.segDesc[at + 1], this.segDesc[at + 2]);
        }
        this.incomplete = dropped;
        if (this.points == 0) {
            this.empty = true;
            this.ownIndexCount = 0;
            this.packIndexCount = 0;
            return;
        }
        this.originX = (int) Math.floor(this.minWX);
        this.originZ = (int) Math.floor(this.minWZ);
        this.baseY = (float) Math.floor(this.minY) - 1.0F;
        // The own format counts x and z in units, 256 to a step of the seam's own grid: the coarsest grid whose units still reach
        // the seam's far corner. Every point of the seam stands on a whole block or a tile's sample, so a coarser grid loses nothing
        // but the overlap's two hundredths. / Собственный формат считает x и z в единицах, 256 на шаг собственной сетки шва: берётся
        // самая грубая сетка, чьи единицы ещё дотягиваются до дальнего угла шва. Каждая точка шва стоит на целом блоке или на отсчёте
        // тайла, поэтому более грубая сетка не теряет ничего, кроме двух сотых захода.
        float extent = Math.max(this.maxWX - this.minWX, this.maxWZ - this.minWZ);
        int spacing = 1;
        while (extent * (LodVertex.XZ_UNITS / spacing) > 65000.0F && spacing < 256) {
            spacing *= 2;
        }
        this.spacing = spacing;
        this.writeOwn();
        this.writePack();
        this.empty = false;
    }

    /** The seam's ends reach this far past the segment's ends along the border, see {@link #OVERLAP}. / Концы шва заходят на столько за концы отрезка вдоль границы, см. {@link #OVERLAP}. */
    private static float extended(float t) {
        return t <= 0.0F ? -OVERLAP : t >= 16.0F ? 16.0F + OVERLAP : t;
    }

    /**
     * One segment of the seam, along one side of chunk {@code cx, cz} whose neighbour in direction {@code dir} is vanilla's: the
     * zipper between our profile (the tile's samples along the side) and vanilla's (its edge, block by block).
     * <p>
     * Один отрезок шва вдоль одной стороны чанка {@code cx, cz}, чей сосед в направлении {@code dir} - ванилла: молния между нашим
     * профилем (отсчёты тайла вдоль стороны) и профилем ваниллы (её кромка, блок за блоком).
     */
    private boolean segment(ClientLevel level, TileMesh mesh, int cx, int cz, int dir) {
        ChunkAccess chunk = this.chunk(level, cx + SIDE_X[dir], cz + SIDE_Z[dir]);
        if (chunk == null) {
            return false;
        }
        boolean xEdge = SIDE_X[dir] != 0;
        float planeX = (cx + (SIDE_X[dir] > 0 ? 1 : 0)) * 16.0F - SIDE_X[dir] * OVERLAP;
        float planeZ = (cz + (SIDE_Z[dir] > 0 ? 1 : 0)) * 16.0F - SIDE_Z[dir] * OVERLAP;
        // Vanilla's profile: the ground of every column along its edge, then the columns opened out into the true step profile:
        // across a column its top runs level, at the column's far side it jumps straight to the next column's top. Joining the
        // column tops corner to corner instead left a diagonal over every rising step, and under the diagonal a triangle of sky
        // the width of one block and the height of the step.
        // / Профиль ваниллы: земля каждой колонки вдоль её кромки, затем колонки разворачиваются в настоящий ступенчатый
        // профиль: поперёк колонки её верх идёт ровно, у дальнего края колонки прыгает прямо к верху следующей. Соединение
        // верхов колонок угол к углу вместо этого оставляло диагональ над каждой восходящей ступенькой, а под диагональю -
        // треугольник неба шириной в один блок и высотой в ступеньку.
        for (int k = 0; k < 16; k++) {
            int localX;
            int localZ;
            if (xEdge) {
                localX = SIDE_X[dir] > 0 ? 0 : 15;
                localZ = k;
            } else {
                localZ = SIDE_Z[dir] > 0 ? 0 : 15;
                localX = k;
            }
            this.vanillaColumn(level, chunk, localX, localZ, k);
        }
        int m = 0;
        this.vanT[m] = 0.0F;
        this.vanY[m] = this.colY[0];
        this.vanWl[m] = this.colWl[0];
        m++;
        for (int k = 0; k < 16; k++) {
            this.vanT[m] = k + 1.0F;
            this.vanY[m] = this.colY[k];
            this.vanWl[m] = this.colWl[k];
            m++;
            if (k < 15) {
                this.vanT[m] = k + 1.0F;
                this.vanY[m] = this.colY[k + 1];
                this.vanWl[m] = this.colWl[k + 1];
                m++;
            }
        }
        this.vanCount = m;
        for (int p = 0; p < m; p++) {
            float t = extended(this.vanT[p]);
            if (xEdge) {
                this.vanWX[p] = planeX;
                this.vanWZ[p] = cz * 16.0F + t;
            } else {
                this.vanWX[p] = cx * 16.0F + t;
                this.vanWZ[p] = planeZ;
            }
        }
        // Our profile: the tile's samples along the same side, at the heights they are drawn. A tile coarser than a chunk has no
        // sample line along the side: its surface there is read between its samples instead, the way TileMesh.heightAt reads it.
        // / Наш профиль: отсчёты тайла вдоль той же стороны, на высотах, на которых они нарисованы. У тайла грубее чанка линии
        // отсчётов вдоль стороны нет: его поверхность там читается между отсчётами, как её читает TileMesh.heightAt.
        int spacing = mesh.pos.size() / FarPlaneConstants.TILE_CELLS;
        long minX = mesh.pos.minX();
        long minZ = mesh.pos.minZ();
        float tileBaseY = mesh.surface.baseY();
        int steps;
        if (spacing <= 16) {
            steps = 16 / spacing;
            for (int k = 0; k <= steps; k++) {
                int sx;
                int sz;
                if (xEdge) {
                    sx = (int) (((cx + (SIDE_X[dir] > 0 ? 1 : 0)) * 16L - minX) / spacing);
                    sz = (int) ((cz * 16L - minZ) / spacing) + k;
                } else {
                    sz = (int) (((cz + (SIDE_Z[dir] > 0 ? 1 : 0)) * 16L - minZ) / spacing);
                    sx = (int) ((cx * 16L - minX) / spacing) + k;
                }
                int sample = sx + sz * FarPlaneConstants.TILE_SAMPLES;
                int packed = mesh.seamHeights[sample];
                this.ourT[k] = k * (float) spacing;
                this.ourY[k] = tileBaseY + (packed & 0xFFFF) / LodVertex.Y_UNITS;
                this.ourWl[k] = tileBaseY + (packed >>> 16) / LodVertex.Y_UNITS;
                this.ourColor[k] = mesh.seamColors[sample];
                this.ourBits[k] = mesh.seamBits[sample];
                float t = extended(this.ourT[k]);
                if (xEdge) {
                    this.ourWX[k] = planeX;
                    this.ourWZ[k] = cz * 16.0F + t;
                } else {
                    this.ourWX[k] = cx * 16.0F + t;
                    this.ourWZ[k] = planeZ;
                }
            }
        } else {
            steps = 16;
            int edgeLine = xEdge ? (cx + (SIDE_X[dir] > 0 ? 1 : 0)) * 16 : (cz + (SIDE_Z[dir] > 0 ? 1 : 0)) * 16;
            for (int k = 0; k <= 16; k++) {
                float fx;
                float fz;
                if (xEdge) {
                    fx = (edgeLine - minX) / (float) spacing;
                    fz = (cz * 16 + k - minZ) / (float) spacing;
                } else {
                    fx = (cx * 16 + k - minX) / (float) spacing;
                    fz = (edgeLine - minZ) / (float) spacing;
                }
                int x0 = clamp((int) Math.floor(fx), 0, FarPlaneConstants.TILE_CELLS - 1);
                int z0 = clamp((int) Math.floor(fz), 0, FarPlaneConstants.TILE_CELLS - 1);
                float tx = fx - x0;
                float tz = fz - z0;
                int row = FarPlaneConstants.TILE_SAMPLES;
                int h00 = mesh.seamHeights[x0 + z0 * row];
                int h10 = mesh.seamHeights[x0 + 1 + z0 * row];
                int h01 = mesh.seamHeights[x0 + (z0 + 1) * row];
                int h11 = mesh.seamHeights[x0 + 1 + (z0 + 1) * row];
                this.ourT[k] = k;
                this.ourY[k] = tileBaseY + lerp2(h00 & 0xFFFF, h10 & 0xFFFF, h01 & 0xFFFF, h11 & 0xFFFF, tx, tz) / LodVertex.Y_UNITS;
                this.ourWl[k] = tileBaseY + lerp2(h00 >>> 16, h10 >>> 16, h01 >>> 16, h11 >>> 16, tx, tz) / LodVertex.Y_UNITS;
                int nearest = clamp(Math.round(fx), 0, FarPlaneConstants.TILE_CELLS) + clamp(Math.round(fz), 0, FarPlaneConstants.TILE_CELLS) * row;
                this.ourColor[k] = mesh.seamColors[nearest];
                this.ourBits[k] = mesh.seamBits[nearest];
                float t = extended(k);
                if (xEdge) {
                    this.ourWX[k] = planeX;
                    this.ourWZ[k] = cz * 16.0F + t;
                } else {
                    this.ourWX[k] = cx * 16.0F + t;
                    this.ourWZ[k] = planeZ;
                }
            }
        }
        // Where the segment runs along the tile's own edge, the tile's skirt already hangs there in the border's plane, down to
        // the bottom the mesh gave it; in the pack's format the seam falls into that same plane exactly (its positions are whole
        // blocks), and two surfaces in one plane show through each other tooth by tooth and flicker. There the seam's side starts
        // at the skirt's bottom instead of at the surface, so the two never share a band: the skirt covers the upper part of the
        // wedge, the seam the rest.
        // / Где отрезок идёт вдоль собственного края тайла, там в плоскости границы уже висит юбка тайла, до того низа, который
        // дал ей меш; в формате пака шов попадает ровно в ту же плоскость (его позиции - целые блоки), а две поверхности в одной
        // плоскости просвечивают друг сквозь друга зубцами и мерцают. Там сторона шва начинается от низа юбки, а не от
        // поверхности, и они никогда не делят полосу: юбка закрывает верх клина, шов - остальное.
        if (mesh.seamSkirts != null) {
            int skirtEdge = -1;
            float along0 = 0.0F;
            if (xEdge) {
                long edgeLine = (cx + (SIDE_X[dir] > 0 ? 1 : 0)) * 16L;
                if (edgeLine == minX) {
                    skirtEdge = 2;
                } else if (edgeLine == minX + mesh.pos.size()) {
                    skirtEdge = 3;
                }
                along0 = (cz * 16L - minZ) / (float) spacing;
            } else {
                long edgeLine = (cz + (SIDE_Z[dir] > 0 ? 1 : 0)) * 16L;
                if (edgeLine == minZ) {
                    skirtEdge = 0;
                } else if (edgeLine == minZ + mesh.pos.size()) {
                    skirtEdge = 1;
                }
                along0 = (cx * 16L - minX) / (float) spacing;
            }
            if (skirtEdge >= 0) {
                for (int k = 0; k <= steps; k++) {
                    float at = along0 + this.ourT[k] / spacing;
                    float skirtY = tileBaseY + skirtHalf(mesh.seamSkirts, skirtEdge, at, false) / LodVertex.Y_UNITS;
                    if (skirtY < this.ourY[k]) {
                        this.ourY[k] = skirtY;
                        this.ourWl[k] = tileBaseY + skirtHalf(mesh.seamSkirts, skirtEdge, at, true) / LodVertex.Y_UNITS;
                    }
                }
            }
        }
        // The zipper itself: walk both profiles, always taking the next point of whichever comes first along the border; vanilla's
        // points take the color and material of our nearest sample, so the strip reads as the far terrain's own side everywhere.
        // / Сама молния: идём по обоим профилям, всегда беря следующую точку того, кто идёт первым вдоль границы; точки ваниллы берут
        // цвет и материал нашего ближайшего отсчёта, поэтому полоса везде читается как собственная сторона дальнего ландшафта.
        float rdx = -SIDE_X[dir];
        float rdz = -SIDE_Z[dir];
        int color = this.ourColor[0];
        byte bits = this.ourBits[0];
        int prevOur = this.point(this.ourWX[0], this.ourY[0], this.ourWZ[0], this.ourWl[0], color, bits, rdx, rdz);
        int prevVan = this.point(this.vanWX[0], this.vanY[0], this.vanWZ[0], this.vanWl[0], color, bits, rdx, rdz);
        int i = 1;
        int j = 1;
        while (i <= steps || j < this.vanCount) {
            boolean ours = j >= this.vanCount || i <= steps && this.ourT[i] <= this.vanT[j];
            if (ours) {
                color = this.ourColor[i];
                bits = this.ourBits[i];
                int next = this.point(this.ourWX[i], this.ourY[i], this.ourWZ[i], this.ourWl[i], color, bits, rdx, rdz);
                this.tri(prevOur, prevVan, next, rdx, rdz);
                prevOur = next;
                i++;
            } else {
                int next = this.point(this.vanWX[j], this.vanY[j], this.vanWZ[j], this.vanWl[j], color, bits, rdx, rdz);
                this.tri(prevOur, prevVan, next, rdx, rdz);
                prevVan = next;
                j++;
            }
        }
        return true;
    }

    /** A vanilla chunk the seam borders, read once a sewing; null while it is not there. / Чанк ваниллы, с которым граничит шов, читается раз за шитье; null, пока его нет. */
    private @Nullable ChunkAccess chunk(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        ChunkAccess known = this.chunkCache.get(key);
        if (known != null) {
            return known;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk) || chunk instanceof EmptyLevelChunk) {
            return null;
        }
        this.chunkCache.put(key, chunk);
        return chunk;
    }

    /**
     * The ground of one column of vanilla's edge, and the level of its water, into {@link #colY} and {@link #colWl} at {@code k}:
     * down from the top block through water and what stands in it to the first whole block, as VanillaWall looks for the ground
     * under its walls (plants and corals stand on the ground; they are not the ground itself).
     * <p>
     * Земля одной колонки кромки ваниллы и уровень её воды, в {@link #colY} и {@link #colWl} по индексу {@code k}: вниз от верхнего
     * блока сквозь воду и то, что в ней стоит, до первого целого блока, как VanillaWall ищет землю под своими стенами (растения и
     * кораллы стоят на земле; сами они землёй не являются).
     */
    private void vanillaColumn(ClientLevel level, ChunkAccess chunk, int localX, int localZ, int k) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
        BlockPos.MutableBlockPos pos = this.pos;
        pos.set(baseX + localX, top, baseZ + localZ);
        boolean water = chunk.getFluidState(pos).is(FluidTags.WATER);
        int y = top;
        int limit = Math.max(level.getMinY(), top - MAX_DEPTH);
        while (y > limit) {
            BlockState state = chunk.getBlockState(pos.setY(y));
            if (state.getFluidState().isEmpty() && state.isSolidRender() && !state.is(BlockTags.CORAL_BLOCKS)) {
                break;
            }
            y--;
        }
        float ground = y + 1;
        this.colY[k] = ground;
        this.colWl[k] = water ? top + VANILLA_WATER_FILL : ground;
    }

    /** Adds a point of the seam and returns its number. / Добавляет точку шва и возвращает её номер. */
    private int point(float wx, float y, float wz, float wl, int color, byte bits, float rdx, float rdz) {
        if (this.points == this.px.length) {
            int size = this.px.length * 2;
            this.px = java.util.Arrays.copyOf(this.px, size);
            this.py = java.util.Arrays.copyOf(this.py, size);
            this.pz = java.util.Arrays.copyOf(this.pz, size);
            this.pwl = java.util.Arrays.copyOf(this.pwl, size);
            this.pcolor = java.util.Arrays.copyOf(this.pcolor, size);
            this.pbits = java.util.Arrays.copyOf(this.pbits, size);
            this.pnx = java.util.Arrays.copyOf(this.pnx, size);
            this.pnz = java.util.Arrays.copyOf(this.pnz, size);
        }
        int at = this.points++;
        this.px[at] = wx;
        this.py[at] = y;
        this.pz[at] = wz;
        this.pwl[at] = wl;
        this.pcolor[at] = color;
        this.pbits[at] = bits;
        this.pnx[at] = rdx;
        this.pnz[at] = rdz;
        this.minWX = Math.min(this.minWX, wx);
        this.maxWX = Math.max(this.maxWX, wx);
        this.minWZ = Math.min(this.minWZ, wz);
        this.minY = Math.min(this.minY, y);
        return at;
    }

    /** Adds a triangle of the seam, wound to face the rough terrain (the direction {@code rdx, rdz} points to it). / Добавляет треугольник шва, закрученный лицом к дальнему ландшафту (направление {@code rdx, rdz} указывает на него). */
    private void tri(int a, int b, int c, float rdx, float rdz) {
        float ux = this.px[b] - this.px[a];
        float uy = this.py[b] - this.py[a];
        float uz = this.pz[b] - this.pz[a];
        float vx = this.px[c] - this.px[a];
        float vy = this.py[c] - this.py[a];
        float vz = this.pz[c] - this.pz[a];
        float dot = (uy * vz - uz * vy) * rdx + (ux * vy - uy * vx) * rdz;
        if (this.triCount * 3 + 3 > this.tris.length) {
            this.tris = java.util.Arrays.copyOf(this.tris, this.tris.length * 2);
        }
        int at = this.triCount * 3;
        if (dot < 0.0F) {
            this.tris[at] = a;
            this.tris[at + 1] = c;
            this.tris[at + 2] = b;
        } else {
            this.tris[at] = a;
            this.tris[at + 1] = b;
            this.tris[at + 2] = c;
        }
        this.triCount++;
    }

    /**
     * The seam in the surface's own format, see {@link LodVertex}: every point twice, once facing the rough terrain and once
     * facing vanilla, then the indices of both. / Шов в собственном формате поверхности, см. {@link LodVertex}: каждая точка дважды,
     * один раз лицом к дальнему ландшафту и один раз к ванилле, затем индексы обеих сторон.
     */
    private void writeOwn() {
        ByteBuffer data = this.scratch;
        data.clear();
        for (int block = 0; block < 2; block++) {
            float sign = block == 0 ? 1.0F : -1.0F;
            for (int p = 0; p < this.points; p++) {
                data = this.room(data, LodVertex.SIZE);
                int color = this.pcolor[p];
                LodVertex.put(data,
                        LodVertex.position((this.px[p] - this.originX) * (LodVertex.XZ_UNITS / this.spacing)),
                        LodVertex.position((this.pz[p] - this.originZ) * (LodVertex.XZ_UNITS / this.spacing)),
                        LodVertex.height(this.py[p], this.baseY),
                        LodVertex.height(this.pwl[p], this.baseY),
                        LodVertex.normal(this.pnx[p] * sign, 0.0F, this.pnz[p] * sign),
                        0,
                        this.pbits[p] & LodVertex.MATERIAL_MASK,
                        color,
                        LodVertex.withAlpha(color, 0));
            }
        }
        this.ownVertexBytes = data.position();
        data = this.putIndices(data);
        this.ownIndexCount = this.triCount * 6;
        this.ownBuffer = this.upload(this.ownBuffer, data, "FarPlane border seam");
        this.ownSlice = this.ownBuffer.slice(0, this.ownVertexBytes);
        this.scratch = data;
    }

    /** The seam in the pack's format, see PackMesher: whole blocks from the same origin, the same triangles. / Шов в формате пака, см. PackMesher: целые блоки от того же начала, те же треугольники. */
    private void writePack() {
        ByteBuffer data = this.scratch;
        data.clear();
        for (int block = 0; block < 2; block++) {
            float sign = block == 0 ? 1.0F : -1.0F;
            for (int p = 0; p < this.points; p++) {
                data = this.packVertex(data, p, sign);
            }
        }
        this.packVertexBytes = data.position();
        data = this.putIndices(data);
        this.packIndexCount = this.triCount * 6;
        this.packBuffer = this.upload(this.packBuffer, data, "FarPlane border seam pack");
        this.packOriginX = this.originX;
        this.packBaseY = this.baseY;
        this.packOriginZ = this.originZ;
        this.scratch = data;
    }

    /** One vertex in the pack's format: the light as PackMesher puts it on the ground, sky light falling by one a block of water. / Одна вершина в формате пака: свет, как PackMesher кладёт его на землю, свет неба падает на единицу за блок воды. */
    private ByteBuffer packVertex(ByteBuffer data, int p, float sign) {
        data = this.room(data, PackMesher.SIZE);
        int color = this.pcolor[p];
        float depth = Math.max(0.0F, this.pwl[p] - this.py[p]);
        int sky = depth <= 0.0F ? 15 : clamp(15 - (int) Math.ceil(depth), 0, 15);
        int blockLight = (this.pbits[p] & LodVertex.MATERIAL_MASK) == LodVertex.MATERIAL_LAVA ? 15 : 0;
        data.putShort((short) clamp(Math.round(this.px[p]) - this.originX, 0, 0xFFFF));
        data.putShort((short) clamp(Math.round(this.py[p] - this.baseY), 0, 0xFFFF));
        data.putShort((short) clamp(Math.round(this.pz[p]) - this.originZ, 0, 0xFFFF));
        data.putShort((short) (sky | blockLight << 4));
        data.put((byte) (color >>> 16)).put((byte) (color >>> 8)).put((byte) color).put((byte) -1);
        data.put((byte) (this.pbits[p] & LodVertex.MATERIAL_MASK));
        data.put((byte) faceOf(this.pnx[p] * sign, this.pnz[p] * sign));
        data.put((byte) 0).put((byte) 0);
        return data;
    }

    /** The indices of both sides of the seam, after its vertices: the rough side as sewn, the vanilla side reversed. / Индексы обеих сторон шва, после его вершин: сторона дальнего ландшафта как сшита, сторона ваниллы наоборот. */
    private ByteBuffer putIndices(ByteBuffer data) {
        for (int block = 0; block < 2; block++) {
            int offset = block * this.points;
            for (int t = 0; t < this.triCount; t++) {
                data = this.room(data, 12);
                int a = this.tris[t * 3] + offset;
                int b = this.tris[t * 3 + 1] + offset;
                int c = this.tris[t * 3 + 2] + offset;
                if (block == 0) {
                    data.putInt(a).putInt(b).putInt(c);
                } else {
                    data.putInt(a).putInt(c).putInt(b);
                }
            }
        }
        return data;
    }

    /** Room for {@code needed} more bytes in the scratch, growing it as VanillaWall grows its own. / Место ещё на {@code needed} байт в черновике, с ростом, как VanillaWall растит свой. */
    private ByteBuffer room(ByteBuffer data, int needed) {
        if (data.remaining() >= needed) {
            return data;
        }
        ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(data.capacity() * 2, data.position() + needed)).order(ByteOrder.nativeOrder());
        data.flip();
        bigger.put(data);
        return bigger;
    }

    /** Puts the scratch into a buffer of the GPU, making or growing it as VanillaWall does. / Кладёт черновик в буфер видеокарты, создавая или растя его, как это делает VanillaWall. */
    private @Nullable GpuBuffer upload(@Nullable GpuBuffer old, ByteBuffer data, String name) {
        data.flip();
        if (old == null || old.isClosed() || old.size() < data.remaining()) {
            if (old != null) {
                old.close();
            }
            int capacity = Math.max(data.remaining(), 64 * 1024);
            old = RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(old.slice(0, data.remaining()), data);
        return old;
    }

    /** The face of the pack's table a horizontal normal takes. / Грань таблицы пака, которую берёт горизонтальная нормаль. */
    private static int faceOf(float nx, float nz) {
        if (nx > 0.0F) {
            return FACE_EAST;
        }
        if (nx < 0.0F) {
            return FACE_WEST;
        }
        return nz < 0.0F ? FACE_NORTH : FACE_SOUTH;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }

    /**
     * One half (the height, or the water level above it) of a tile's skirt bottoms along an edge, read between the edge's samples
     * at {@code at} samples from its start. / Одна половина (высота или уровень воды над ней) низов юбки тайла вдоль края,
     * прочитанная между отсчётами края в точке {@code at} отсчётов от его начала.
     */
    private static float skirtHalf(int[] skirts, int edge, float at, boolean high) {
        int i0 = clamp((int) Math.floor(at), 0, FarPlaneConstants.TILE_CELLS - 1);
        float frac = at - i0;
        int a = skirts[edge * FarPlaneConstants.TILE_SAMPLES + i0];
        int b = skirts[edge * FarPlaneConstants.TILE_SAMPLES + i0 + 1];
        float va = high ? a >>> 16 : a & 0xFFFF;
        float vb = high ? b >>> 16 : b & 0xFFFF;
        return va + (vb - va) * frac;
    }

    /** The bilinear reading of four samples, as TileMesh.heightAt reads the heights. / Билинейное чтение четырёх отсчётов, как TileMesh.heightAt читает высоты. */
    private static float lerp2(float v00, float v10, float v01, float v11, float tx, float tz) {
        float top = v00 + (v10 - v00) * tx;
        float bottom = v01 + (v11 - v01) * tx;
        return top + (bottom - top) * tz;
    }

    /** Whether there is a seam to draw. / Есть ли шов, который можно рисовать. */
    boolean hasMesh() {
        return !this.empty;
    }

    /** The seam's tile block for this frame; null while there is no seam. / Блок тайла шва на этот кадр; null, пока шва нет. */
    @Nullable GpuBufferSlice blockSlice() {
        return this.blockSlice;
    }

    /** The seam's own-format vertices as a slice. / Вершины шва в собственном формате срезом. */
    @Nullable GpuBufferSlice ownVertices() {
        return this.ownSlice;
    }

    @Nullable GpuBuffer ownBuffer() {
        return this.ownBuffer;
    }

    int ownIndexCount() {
        return this.ownIndexCount;
    }

    /** Where the seam's own-format indices start, in indices. / Где начинаются индексы шва в собственном формате, в индексах. */
    int ownFirstIndex() {
        return this.ownVertexBytes / 4;
    }

    /** The seam for a shader pack; null while there is no seam. / Шов для шейдерного пака; null, пока шва нет. */
    @Nullable PackMesh packMesh() {
        if (this.empty || this.packBuffer == null) {
            return null;
        }
        return new PackMesh(this.packBuffer, this.packVertexBytes, this.packIndexCount, this.packOriginX, this.packBaseY, this.packOriginZ);
    }

    @Override
    public void close() {
        if (this.ownBuffer != null) {
            this.ownBuffer.close();
            this.ownBuffer = null;
        }
        if (this.packBuffer != null) {
            this.packBuffer.close();
            this.packBuffer = null;
        }
        if (this.blocks != null) {
            this.blocks.close();
            this.blocks = null;
        }
        this.ownSlice = null;
        this.blockSlice = null;
        this.empty = true;
        this.builtVersion = -1;
        this.builtPrint = Long.MIN_VALUE;
    }
}
