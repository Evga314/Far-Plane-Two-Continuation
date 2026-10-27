package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.FarPlaneDebug;
import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.Evga314.farplanetwocontinuation.config.ConfigFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Performance and settings log of the debug build ({@link FarPlaneDebug}). Everything here is a no-op in normal builds. Measures what
 * the mod does on the render thread every frame (uploading meshes, the LOD pass, its draw calls and, where the driver supports
 * timestamps, its GPU time), the client tick, mesh building, garbage collection and the CPU time of every thread, and writes:
 * <ul>
 *   <li>a line for every frame much longer than usual, with what happened in it;</li>
 *   <li>a summary every few seconds;</li>
 *   <li>the device, and all settings at the start and whenever one of them changes.</li>
 * </ul>
 * All lines start with {@code [FarPlane perf]} or {@code [FarPlane settings]}, so they are easy to find in latest.log.
 */
public final class PerfLog {
    public static final boolean ON = FarPlaneDebug.ENABLED;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SUMMARY_NS = 5_000_000_000L;
    /** A frame is a spike when it takes this long and much longer than the frames before it. */
    private static final long SPIKE_MIN_NS = 45_000_000L;
    private static final int MAX_SPIKES_PER_SUMMARY = 25;
    private static final int GPU_SLOTS = 8;
    private static final java.util.concurrent.ExecutorService BACKGROUND = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FarPlane perf log");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    // Render thread.
    private static long lastFrame;
    private static double averageFrame = 16_000_000.0;
    private static long[] frames = new long[1024];
    private static int frameCount;
    private static int spikes;
    private static int spikesLogged;
    private static long uploadNs;
    private static int uploads;
    private static long uploadBytes;
    private static long renderNs;
    private static int draws;
    /** Triangles drawn this frame, by every pass of the LOD terrain. */
    private static long triangles;
    /** Passes of the LOD terrain, for the triangles drawn in each. */
    public static final String[] PASSES = {"rough", "under detail", "detail", "fade", "water", "depth"};
    private static int pass;
    private static final long[] passTriangles = new long[PASSES.length];
    /** Parts of the LOD pass on the render thread, timed separately; see LodRenderer.renderLod. */
    public static final String[] RENDER_PHASES = {"edge check", "cull", "sort", "transforms", "mask", "draw", "water", "depth"};
    private static final long[] renderPhaseNs = new long[RENDER_PHASES.length];
    private static final long[] renderPhaseNsSum = new long[RENDER_PHASES.length];
    // Parts of the uploads: making the empty buffers, writing the data into them, and freeing the buffers of old tiles.
    private static long createNs;
    private static int creates;
    private static long createdBytes;
    private static long freeNs;
    private static int frees;
    private static long createNsSum;
    private static long createNsMax;
    private static int createsSum;
    private static long freeNsSum;
    private static long freeNsMax;
    private static int freesSum;
    /** The client tick within the frame, which the spike lines show too. */
    private static long frameTickNs;
    private static long trianglesSum;
    private static long uploadNsSum;
    private static long uploadNsMax;
    private static int uploadsSum;
    private static long uploadBytesSum;
    private static long renderNsSum;
    private static long renderNsMax;
    private static long drawsSum;
    private static int drawsMax;
    private static long summaryStart;
    private static long lastGcCount;
    private static long lastGcTime;
    private static long frameGcCount;
    private static long frameGcTime;
    private static long summaryGcCount;
    private static long summaryGcTime;
    private static boolean deviceLogged;
    private static @Nullable GpuQueryPool gpuPool;
    private static boolean gpuFailed;
    private static int gpuSlot;
    private static final boolean[] gpuPending = new boolean[GPU_SLOTS];
    private static long gpuNsSum;
    private static long gpuNsMax;
    private static int gpuSamples;
    // Client tick.
    private static long tickNsSum;
    private static long tickNsMax;
    private static long horizonNsSum;
    private static long horizonNsMax;
    private static boolean occlusionOff;
    private static long occlusionCheckedAt;
    private static int ticks;
    private static @Nullable Map<String, String> lastSettings;
    // Mesh builder threads, by layer: 0 = rough, 1 = detail.
    // RU: потоки меш билдера, 0 - дальний, 1 - детальный
    private static final AtomicLong[] meshNs = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] meshes = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong[] meshNsMax = {new AtomicLong(), new AtomicLong()};
    private static final AtomicLong changedRefreshes = new AtomicLong();
    private static final AtomicLong changedRefreshesPutOff = new AtomicLong();
    private static final AtomicLong changedRefreshesMixed = new AtomicLong();
    private static final AtomicLong[] meshPausedNs = {new AtomicLong(), new AtomicLong()};
    // CPU time of every thread at the last summary.
    // RU: процессорное время каждого ядра в последней сводке.
    private static final Map<Long, Long> threadCpu = new HashMap<>();
    private static final Map<Long, Long> threadAlloc = new HashMap<>();

    private PerfLog() {
    }

    /** Start of a frame, before meshes are uploaded: closes the measurements of the frame before. */
    public static void frameStart() {
        long now = System.nanoTime();
        if (summaryStart == 0L) {
            summaryStart = now;
            readGc();
            frameGcCount = 0L;
            frameGcTime = 0L;
            summaryGcCount = lastGcCount;
            summaryGcTime = lastGcTime;
        }
        if (!deviceLogged) {
            deviceLogged = true;
            logDevice();
        }
        if (lastFrame != 0L) {
            long frame = now - lastFrame;
            readGc();
            if (frameCount == frames.length) {
                frames = Arrays.copyOf(frames, frames.length * 2);
            }
            frames[frameCount++] = frame;
            if (frame > SPIKE_MIN_NS && frame > averageFrame * 2.0) {
                spikes++;
                if (spikesLogged < MAX_SPIKES_PER_SUMMARY) {
                    spikesLogged++;
                    logSpike(frame);
                }
            }
            averageFrame = averageFrame * 0.95 + Math.min(frame, 200_000_000L) * 0.05;
        }
        lastFrame = now;
        uploadNs = 0L;
        uploads = 0;
        uploadBytes = 0L;
        renderNs = 0L;
        draws = 0;
        triangles = 0L;
        frameGcCount = 0L;
        frameGcTime = 0L;
        Arrays.fill(renderPhaseNs, 0L);
        createNs = 0L;
        creates = 0;
        createdBytes = 0L;
        freeNs = 0L;
        frees = 0;
        frameTickNs = 0L;
        if (now - summaryStart >= SUMMARY_NS) {
            summary(now);
        }
    }

    public static void uploadTime(long nanos) {
        uploadNs += nanos;
        uploadNsSum += nanos;
        uploadNsMax = Math.max(uploadNsMax, uploadNs);
    }

    /** A buffer made for tiles' meshes, see VertexArena. */
    public static void bufferCreated(long nanos, long bytes) {
        createdBytes += bytes;
        creates++;
        createsSum++;
        createNs += nanos;
        createNsSum += nanos;
        createNsMax = Math.max(createNsMax, createNs);
    }

    /** The buffer of a tile's mesh freed, see TileMesh.close. */
    public static void bufferFreed(long nanos) {
        frees++;
        freesSum++;
        freeNs += nanos;
        freeNsSum += nanos;
        freeNsMax = Math.max(freeNsMax, freeNs);
    }

    /** Adds the time since {@code start} to a part of the LOD pass, see {@link #RENDER_PHASES}; returns the time now. */
    public static long renderPhase(int phase, long start) {
        long now = System.nanoTime();
        renderPhaseNs[phase] += now - start;
        renderPhaseNsSum[phase] += now - start;
        return now;
    }

    public static void uploaded(long bytes) {
        uploads++;
        uploadsSum++;
        uploadBytes += bytes;
        uploadBytesSum += bytes;
    }

    public static void renderTime(long nanos) {
        renderNs += nanos;
        renderNsSum += nanos;
        renderNsMax = Math.max(renderNsMax, renderNs);
        drawsSum += draws;
        trianglesSum += triangles;
        drawsMax = Math.max(drawsMax, draws);
    }

    public static void draw(int indices) {
        draws++;
        triangles += indices / 3;
        passTriangles[pass] += indices / 3;
    }

    /** The pass of the LOD terrain the next draws belong to, see {@link #PASSES}. */
    public static void pass(int index) {
        pass = index;
    }

    public static void tickTime(long nanos) {
        ticks++;
        frameTickNs += nanos;
        tickNsSum += nanos;
        tickNsMax = Math.max(tickNsMax, nanos);
    }

    /** Parts of the client tick, timed separately to see where it goes; see LodManager.tickLod. */
    public static final String[] TICK_PHASES = {"view", "detail", "rough", "mask", "cull", "ranges", "rest", "(rough: plan", "walk", "tiles checked", "quiet ticks"};
    private static final long[] tickPhaseNs = new long[TICK_PHASES.length];

    public static void tickPhase(int phase, long nanos) {
        tickPhaseNs[phase] += nanos;
    }

    /**
     * Whether terrain occlusion culling is turned off to compare: with -Dfarplane.noOcclusion=true, or in the debug build while a file
     * {@code config/farplane-no-occlusion} exists, which can be created and removed while the game runs. The file is looked for once a
     * second.
     */
    public static boolean occlusionOff() {
        if (Boolean.getBoolean("farplane.noOcclusion")) {
            return true;
        }
        if (!ON) {
            return false;
        }
        long now = System.nanoTime();
        if (now - occlusionCheckedAt > 1_000_000_000L) {
            occlusionCheckedAt = now;
            boolean off = java.nio.file.Files.exists(FabricLoader.getInstance().getConfigDir().resolve("farplane-no-occlusion"));
            if (off != occlusionOff) {
                occlusionOff = off;
                LOGGER.info("[FarPlane perf] terrain occlusion culling {}", off ? "OFF" : "ON");
            }
        }
        return occlusionOff;
    }

    /** Time of the terrain occlusion culling in a tick, see Horizon. */
    public static void horizonTime(long nanos) {
        horizonNsSum += nanos;
        horizonNsMax = Math.max(horizonNsMax, nanos);
    }

    private static long flickerWindow;
    private static int flickerLines;

    /** Something that can make the rough terrain flash over vanilla's chunks, see LodManager.watchMask; at most 20 lines a second. */
    public static void flicker(String what) {
        long now = System.nanoTime();
        if (now - flickerWindow > 1_000_000_000L) {
            flickerWindow = now;
            flickerLines = 0;
        }
        if (flickerLines++ < 20) {
            LOGGER.info("[FarPlane flicker] {}", what);
        }
    }

    /**
     * A detailed tile with changed blocks under it: asked for again ({@code putOff} false), or put off because all of them lie inside
     * what vanilla draws. Each tile counts once per kind until its new data arrives.
     */
    public static void changedRefresh(boolean putOff) {
        (putOff ? changedRefreshesPutOff : changedRefreshes).incrementAndGet();
    }

    private static final java.util.Map<String, Long> FOG_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The fog at a stage of its setup, at most every few seconds per stage; see FogLogMixin. */
    public static void fog(String stage, net.minecraft.client.renderer.fog.FogData fog, String fluid) {
        long now = System.nanoTime();
        Long last = FOG_LOGGED.get(stage);
        if (last != null && now - last < SUMMARY_NS) {
            return;
        }
        FOG_LOGGED.put(stage, now);
        LOGGER.info(String.format(Locale.ROOT,
            "[FarPlane fog] %s in %s: environmental %.0f..%.0f, render distance %.0f..%.0f, sky %.0f, clouds %.0f",
            stage, fluid, fog.environmentalStart, fog.environmentalEnd, fog.renderDistanceStart, fog.renderDistanceEnd, fog.skyEnd, fog.cloudEnd));
    }

    /** A tile asked for again for changed blocks of which some lie inside vanilla's drawing and some outside. */
    public static void changedRefreshMixed() {
        changedRefreshesMixed.incrementAndGet();
    }

    private static final AtomicLong[] surface = new AtomicLong[7];

    static {
        for (int i = 0; i < surface.length; i++) {
            surface[i] = new AtomicLong();
        }
    }

    /** A rough tile's mesh for the new renderer, built beside the old one: its time, bytes, vertices, indices and the old one's bytes. */
    public static void surfaceBuilt(long nanos, long bytes, int vertices, int indices, long oldBytes) {
        surface[0].incrementAndGet();
        surface[1].addAndGet(nanos);
        surface[2].accumulateAndGet(nanos, Math::max);
        surface[3].addAndGet(bytes);
        surface[4].addAndGet(vertices);
        surface[5].addAndGet(indices);
        surface[6].addAndGet(oldBytes);
    }

    public static void meshBuilt(boolean detail, long nanos, long pausedNanos) {
        int index = detail ? 1 : 0;
        meshPausedNs[index].addAndGet(pausedNanos);
        meshes[index].incrementAndGet();
        meshNs[index].addAndGet(nanos);
        meshNsMax[index].accumulateAndGet(nanos, Math::max);
    }

    /** GPU timestamp at the start ({@code end} false) or end of the LOD pass. */
    public static void gpuMark(CommandEncoder encoder, boolean end) {
        if (gpuFailed) {
            return;
        }
        try {
            if (gpuPool == null) {
                gpuPool = RenderSystem.getDevice().createTimestampQueryPool(GPU_SLOTS * 2);
            }
            if (!end) {
                gpuSlot = (gpuSlot + 1) % GPU_SLOTS;
                if (gpuPending[gpuSlot]) {
                    // Not answered after a full round: the driver is far behind, the old result is dropped.
                    readGpu(gpuSlot);
                    gpuPending[gpuSlot] = false;
                }
            }
            encoder.writeTimestamp(gpuPool, gpuSlot * 2 + (end ? 1 : 0));
            if (end) {
                gpuPending[gpuSlot] = true;
            }
        } catch (Throwable e) {
            gpuFailed = true;
            LOGGER.info("[FarPlane perf] GPU timestamps are not available on this device: {}", e.toString());
        }
    }

    private static void readGpu(int slot) {
        if (gpuPool == null) {
            return;
        }
        OptionalLong[] values = gpuPool.getValues(slot * 2, 2);
        if (values[0].isPresent() && values[1].isPresent()) {
            long nanos = (long) ((values[1].getAsLong() - values[0].getAsLong()) * RenderSystem.getDevice().getDeviceInfo().timestampPeriod());
            if (nanos >= 0L && nanos < 10_000_000_000L) {
                gpuNsSum += nanos;
                gpuNsMax = Math.max(gpuNsMax, nanos);
                gpuSamples++;
            }
        }
    }

    private static void collectGpu() {
        if (gpuPool == null || gpuFailed) {
            return;
        }
        try {
            for (int slot = 0; slot < GPU_SLOTS; slot++) {
                if (gpuPending[slot] && slot != gpuSlot) {
                    OptionalLong[] values = gpuPool.getValues(slot * 2, 2);
                    if (values[0].isPresent() && values[1].isPresent()) {
                        readGpu(slot);
                        gpuPending[slot] = false;
                    }
                }
            }
        } catch (Throwable e) {
            gpuFailed = true;
        }
    }

    private static void readGc() {
        long count = 0L;
        long time = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount());
            time += Math.max(0L, bean.getCollectionTime());
        }
        frameGcCount += count - lastGcCount;
        frameGcTime += time - lastGcTime;
        lastGcCount = count;
        lastGcTime = time;
    }

    private static void logSpike(long frame) {
        LodManager manager = com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient.manager();
        Runtime runtime = Runtime.getRuntime();
        StringBuilder phases = new StringBuilder();
        for (int phase = 0; phase < RENDER_PHASES.length; phase++) {
            phases.append(String.format(Locale.ROOT, " %s %.1f", RENDER_PHASES[phase], renderPhaseNs[phase] / 1e6));
        }
        LOGGER.info(String.format(Locale.ROOT,
            "[FarPlane perf] spike %.1f ms (usual %.1f): upload %d tiles %.2f MB in %.1f ms (new buffers %d of %.0f MB in %.1f ms, freed %d in %.1f ms), "
                + "lod pass cpu %.1f ms with %d draws (%s), client tick %.1f ms, gc %d (%d ms), heap %d/%d MB, %s",
            frame / 1e6, averageFrame / 1e6, uploads, uploadBytes / 1048576.0, uploadNs / 1e6, creates, createdBytes / 1048576.0, createNs / 1e6, frees, freeNs / 1e6,
            renderNs / 1e6, draws, phases.toString().trim(), frameTickNs / 1e6, frameGcCount, frameGcTime,
            (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20, manager == null ? "" : manager.debugStats()));
    }

    private static void summary(long now) {
        collectGpu();
        double seconds = (now - summaryStart) / 1e9;
        long[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        int over33 = 0;
        for (long frame : sorted) {
            if (frame > 33_333_333L) {
                over33++;
            }
        }
        StringBuilder line = new StringBuilder(512);
        line.append(String.format(Locale.ROOT, "[FarPlane perf] %.1f s: %d frames, %.1f fps, frame p50 %.1f p95 %.1f p99 %.1f max %.1f ms, "
                + "%d over 33 ms, %d spikes",
            seconds, frameCount, frameCount / seconds, percentile(sorted, 0.5), percentile(sorted, 0.95), percentile(sorted, 0.99),
            sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1e6, over33, spikes));
        int frames = Math.max(1, frameCount);
        line.append(String.format(Locale.ROOT, " | lod pass cpu avg %.2f max %.1f ms, draws avg %d max %d, triangles avg %dk",
            renderNsSum / 1e6 / frames, renderNsMax / 1e6, drawsSum / frames, drawsMax, trianglesSum / frames / 1000));
        line.append(" (by pass");
        for (int p = 0; p < PASSES.length; p++) {
            line.append(' ').append(PASSES[p]).append(' ').append(passTriangles[p] / frames / 1000).append('k');
            passTriangles[p] = 0L;
        }
        line.append(')');
        line.append(" (cpu by part avg");
        for (int phase = 0; phase < RENDER_PHASES.length; phase++) {
            line.append(String.format(Locale.ROOT, " %s %.2f", RENDER_PHASES[phase], renderPhaseNsSum[phase] / 1e6 / frames));
            renderPhaseNsSum[phase] = 0L;
        }
        line.append(" ms)");
        if (gpuSamples > 0) {
            line.append(String.format(Locale.ROOT, ", gpu avg %.2f max %.1f ms", gpuNsSum / 1e6 / gpuSamples, gpuNsMax / 1e6));
        } else if (!gpuFailed) {
            line.append(", gpu n/a");
        }
        line.append(String.format(Locale.ROOT, " | upload %d tiles %.1f MB, max %.1f ms/frame | client tick avg %.2f max %.1f ms",
            uploadsSum, uploadBytesSum / 1048576.0, uploadNsMax / 1e6, ticks == 0 ? 0.0 : tickNsSum / 1e6 / ticks, tickNsMax / 1e6));
        line.append(String.format(Locale.ROOT, " (new buffers %d avg %.2f ms, max %.1f ms/frame, freed %d avg %.2f ms, max %.1f ms/frame)",
            createsSum, createsSum == 0 ? 0.0 : createNsSum / 1e6 / createsSum, createNsMax / 1e6,
            freesSum, freesSum == 0 ? 0.0 : freeNsSum / 1e6 / freesSum, freeNsMax / 1e6));
        line.append(String.format(Locale.ROOT, " (occlusion %s avg %.2f max %.1f ms)", occlusionOff ? "OFF" : "on",
            ticks == 0 ? 0.0 : horizonNsSum / 1e6 / ticks, horizonNsMax / 1e6));
        line.append(" tick parts avg");
        for (int phase = 0; phase < TICK_PHASES.length; phase++) {
            // The last two are counts, not times: tiles checked per tick, and the share of ticks without a walk.
            double value = ticks == 0 ? 0.0 : tickPhaseNs[phase] / (phase >= TICK_PHASES.length - 2 ? 1.0 : 1e6) / ticks;
            line.append(String.format(Locale.ROOT, " %s %.2f", TICK_PHASES[phase], value));
            tickPhaseNs[phase] = 0L;
        }
        line.append(")");
        String[] meshKinds = {"rough", "detail"};
        for (int index = 0; index < 2; index++) {
            long built = meshes[index].getAndSet(0L);
            long nanos = meshNs[index].getAndSet(0L);
            long max = meshNsMax[index].getAndSet(0L);
            long paused = meshPausedNs[index].getAndSet(0L);
            line.append(String.format(Locale.ROOT, " | %s meshes %d, avg %.1f max %.1f ms, paused avg %.1f ms",
                    meshKinds[index], built, built == 0 ? 0.0 : nanos / 1e6 / built, max / 1e6, built == 0 ? 0.0 : paused / 1e6 / built));
        }
        long surfaces = surface[0].getAndSet(0L);
        if (surfaces > 0) {
            line.append(String.format(Locale.ROOT, " | surface meshes %d, avg %.2f max %.1f ms, avg %d vertices %d indices drawn, own %.1f KB (old %.1f KB)",
                surfaces, surface[1].getAndSet(0L) / 1e6 / surfaces, surface[2].getAndSet(0L) / 1e6, surface[4].getAndSet(0L) / surfaces,
                surface[5].getAndSet(0L) / surfaces, surface[3].getAndSet(0L) / 1024.0 / surfaces, surface[6].getAndSet(0L) / 1024.0 / surfaces));
        }
        line.append(String.format(Locale.ROOT, " | changed blocks: tiles refreshed %d (with changes under vanilla too %d), put off under vanilla %d",
            changedRefreshes.getAndSet(0L), changedRefreshesMixed.getAndSet(0L), changedRefreshesPutOff.getAndSet(0L)));
        Runtime runtime = Runtime.getRuntime();
        line.append(String.format(Locale.ROOT, " | gc %d (%d ms), heap %d/%d MB",
            lastGcCount - summaryGcCount, lastGcTime - summaryGcTime, (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20));
        LodManager manager = com.Evga314.farplanetwocontinuation.client.FarplanetwocontinuationClient.manager();
        line.append(String.format(Locale.ROOT, " | load urgency %.2f", com.Evga314.farplanetwocontinuation.WorkThrottle.urgency()));
        if (manager != null) {
            line.append(" | ").append(manager.debugStats());
        }
        // Thread CPU times and the system files are slow to read on phones; that part runs on its own thread, so the log itself
        // never makes a frame longer.
        BACKGROUND.execute(() -> {
            line.append(" | threads ").append(threadCpu(seconds));
            String system = systemState();
            if (!system.isEmpty()) {
                line.append(" | ").append(system);
            }
            LOGGER.info(line.toString());
        });

        summaryStart = now;
        summaryGcCount = lastGcCount;
        summaryGcTime = lastGcTime;
        frameCount = 0;
        spikes = 0;
        spikesLogged = 0;
        uploadNsSum = 0L;
        uploadNsMax = 0L;
        uploadsSum = 0;
        uploadBytesSum = 0L;
        createNsSum = 0L;
        createNsMax = 0L;
        createsSum = 0;
        freeNsSum = 0L;
        freeNsMax = 0L;
        freesSum = 0;
        renderNsSum = 0L;
        renderNsMax = 0L;
        drawsSum = 0L;
        trianglesSum = 0L;
        drawsMax = 0;
        gpuNsSum = 0L;
        gpuNsMax = 0L;
        gpuSamples = 0;
        tickNsSum = 0L;
        tickNsMax = 0L;
        horizonNsSum = 0L;
        horizonNsMax = 0L;
        ticks = 0;
    }

    private static double percentile(long[] sorted, double share) {
        if (sorted.length == 0) {
            return 0.0;
        }
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * share))] / 1e6;
    }

    /** CPU use of the busiest groups of threads since the last summary, in percent of one core; numbered threads are summed. */
    private static String threadCpu(double seconds) {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isThreadCpuTimeSupported()) {
                return "cpu time not supported";
            }
            if (!bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(true);
            }
            long[] ids = bean.getAllThreadIds();
            ThreadInfo[] infos = bean.getThreadInfo(ids);
            // Memory every thread allocated, where the JVM counts it (HotSpot does): what makes the garbage collector run.
            long[] allocated = null;
            if (bean instanceof com.sun.management.ThreadMXBean counting && counting.isThreadAllocatedMemorySupported()) {
                if (!counting.isThreadAllocatedMemoryEnabled()) {
                    counting.setThreadAllocatedMemoryEnabled(true);
                }
                allocated = counting.getThreadAllocatedBytes(ids);
            }
            Map<String, double[]> groups = new TreeMap<>();
            Map<String, double[]> allocGroups = new TreeMap<>();
            Map<Long, Long> seen = new HashMap<>();
            Map<Long, Long> seenAlloc = new HashMap<>();
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] == null) {
                    continue;
                }
                String group = infos[i].getThreadName().replaceAll("[#-]?\\d+$", "").trim();
                if (allocated != null && allocated[i] >= 0L) {
                    seenAlloc.put(ids[i], allocated[i]);
                    Long before = threadAlloc.get(ids[i]);
                    allocGroups.computeIfAbsent(group, key -> new double[2])[0] += before == null ? 0L : allocated[i] - before;
                }
                long cpu = bean.getThreadCpuTime(ids[i]);
                if (cpu < 0L) {
                    continue;
                }
                seen.put(ids[i], cpu);
                Long before = threadCpu.get(ids[i]);
                long used = before == null ? 0L : cpu - before;
                double[] sum = groups.computeIfAbsent(group, key -> new double[2]);
                sum[0] += used;
                sum[1]++;
            }
            threadCpu.clear();
            threadCpu.putAll(seen);
            threadAlloc.clear();
            threadAlloc.putAll(seenAlloc);
            String alloc = allocated == null ? "" : allocation(allocGroups, seconds);
            List<Map.Entry<String, double[]>> busiest = new ArrayList<>(groups.entrySet());
            busiest.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < Math.min(10, busiest.size()); i++) {
                Map.Entry<String, double[]> entry = busiest.get(i);
                double percent = entry.getValue()[0] / 1e9 / seconds * 100.0;
                if (percent < 1.0) {
                    break;
                }
                text.append(String.format(Locale.ROOT, "%s%s %.0f%%%s", i == 0 ? "" : ", ", entry.getKey(), percent,
                    entry.getValue()[1] > 1 ? " (" + (int) entry.getValue()[1] + ")" : ""));
            }
            return text.append(alloc).toString();
        } catch (Throwable e) {
            return "cpu time failed: " + e;
        }
    }

    /** Memory allocated per second by all threads and by the groups allocating most, at least 1 MB/s. */
    private static String allocation(Map<String, double[]> groups, double seconds) {
        double total = 0.0;
        for (double[] sum : groups.values()) {
            total += sum[0];
        }
        List<Map.Entry<String, double[]>> busiest = new ArrayList<>(groups.entrySet());
        busiest.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, " | allocated %.0f MB/s:", total / 1048576.0 / seconds));
        for (int i = 0; i < Math.min(10, busiest.size()); i++) {
            double perSecond = busiest.get(i).getValue()[0] / 1048576.0 / seconds;
            if (perSecond < 1.0) {
                break;
            }
            text.append(String.format(Locale.ROOT, "%s %s %.0f", i == 0 ? "" : ",", busiest.get(i).getKey(), perSecond));
        }
        return text.toString();
    }

    /** CPU core frequencies and the hottest thermal zone, where the system lets them be read (Linux and Android). */
    private static String systemState() {
        StringBuilder text = new StringBuilder();
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            StringBuilder frequencies = new StringBuilder();
            for (int core = 0; core < Math.max(cores, 8); core++) {
                Path file = Path.of("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_cur_freq");
                if (!Files.isReadable(file)) {
                    continue;
                }
                long khz = Long.parseLong(Files.readString(file).trim());
                frequencies.append(frequencies.isEmpty() ? "" : " ").append(khz / 1000);
            }
            if (!frequencies.isEmpty()) {
                text.append("cpu MHz [").append(frequencies).append(']');
            }
            Path thermal = Path.of("/sys/class/thermal");
            if (Files.isDirectory(thermal)) {
                double hottest = Double.NaN;
                double skin = Double.NaN;
                String hottestType = "";
                try (var zones = Files.list(thermal)) {
                    for (Path zone : zones.filter(path -> path.getFileName().toString().startsWith("thermal_zone")).toList()) {
                        Path temp = zone.resolve("temp");
                        if (!Files.isReadable(temp)) {
                            continue;
                        }
                        double value = Long.parseLong(Files.readString(temp).trim());
                        value = value > 1000.0 ? value / 1000.0 : value;
                        String zoneType = Files.isReadable(zone.resolve("type")) ? Files.readString(zone.resolve("type")).trim() : "";
                        if (zoneType.contains("skin")) {
                            skin = value;
                        }
                        // Battery charger sensors report nonsense values.
                        if (zoneType.contains("ibat") || value > 150.0) {
                            continue;
                        }
                        if (Double.isNaN(hottest) || value > hottest) {
                            hottest = value;
                            Path type = zone.resolve("type");
                            hottestType = Files.isReadable(type) ? Files.readString(type).trim() : zone.getFileName().toString();
                        }
                    }
                }
                if (!Double.isNaN(hottest)) {
                    text.append(String.format(Locale.ROOT, "%shottest sensor %.0f C (%s)", text.isEmpty() ? "" : ", ", hottest, hottestType));
                }
                if (!Double.isNaN(skin)) {
                    text.append(String.format(Locale.ROOT, ", skin %.0f C", skin));
                }
                // The highest frequency the system allows right now: power profiles and heat lower it.
                StringBuilder limits = new StringBuilder();
                for (int core = 0; core < Math.max(Runtime.getRuntime().availableProcessors(), 8); core++) {
                    Path file = Path.of("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_max_freq");
                    if (Files.isReadable(file)) {
                        limits.append(limits.isEmpty() ? "" : " ").append(Long.parseLong(Files.readString(file).trim()) / 1000);
                    }
                }
                if (!limits.isEmpty()) {
                    text.append(", cpu limit MHz [").append(limits).append(']');
                }
            }
        } catch (Throwable e) {
            // Not readable on this system.
        }
        return text.toString();
    }

    private static void logDevice() {
        StringBuilder text = new StringBuilder("[FarPlane settings] device: ");
        text.append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch"));
        text.append(", java ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version"));
        text.append(", ").append(Runtime.getRuntime().availableProcessors()).append(" cores, heap max ").append(Runtime.getRuntime().maxMemory() >> 20)
            .append(" MB");
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            text.append(", gpu ").append(info.name()).append(" / ").append(info.vendorName()).append(", driver ").append(info.driverInfo())
                .append(", backend ").append(info.backendName());
        } catch (Throwable e) {
            text.append(", gpu unknown");
        }
        text.append(", sodium ").append(FabricLoader.getInstance().isModLoaded("sodium")).append(", mods ").append(FabricLoader.getInstance().getAllMods().size());
        LOGGER.info(text.toString());
    }

    /** Logs all settings the first time and every one that changed afterwards. Cheap enough to call every tick. */
    public static void checkSettings(ClientConfig config, Minecraft minecraft) {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            flatten("", JsonParser.parseString(ConfigFiles.toCompactJson(config)), settings);
            Options options = minecraft.options;
            settings.put("vanilla.renderDistance", String.valueOf(options.renderDistance().get()));
            settings.put("vanilla.simulationDistance", String.valueOf(options.simulationDistance().get()));
            settings.put("vanilla.maxFps", String.valueOf(options.framerateLimit().get()));
            settings.put("vanilla.vsync", String.valueOf(options.enableVsync().get()));
            settings.put("vanilla.graphics", String.valueOf(options.graphicsPreset().get()));
            settings.put("vanilla.clouds", options.cloudStatus().get() + " " + options.cloudRange().get());
            settings.put("vanilla.mipmaps", String.valueOf(options.mipmapLevels().get()));
            settings.put("vanilla.filtering", options.textureFiltering().get() + " " + options.maxAnisotropyBit().get());
            settings.put("vanilla.biomeBlend", String.valueOf(options.biomeBlendRadius().get()));
            settings.put("window", minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight());
        } catch (Throwable e) {
            settings.put("error", e.toString());
        }
        Map<String, String> before = lastSettings;
        lastSettings = settings;
        if (before == null) {
            StringBuilder text = new StringBuilder("[FarPlane settings] all:");
            settings.forEach((key, value) -> text.append(' ').append(key).append('=').append(value));
            LOGGER.info(text.toString());
            return;
        }
        if (before.equals(settings)) {
            return;
        }
        StringBuilder text = new StringBuilder("[FarPlane settings] changed:");
        settings.forEach((key, value) -> {
            String old = before.get(key);
            if (!value.equals(old)) {
                text.append(' ').append(key).append(": ").append(old).append(" -> ").append(value).append(';');
            }
        });
        before.keySet().stream().filter(key -> !settings.containsKey(key)).forEach(key -> text.append(' ').append(key).append(" removed;"));
        LOGGER.info(text.toString());
    }

    private static void flatten(String prefix, JsonElement element, Map<String, String> out) {
        if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                flatten(prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue(), out);
            }
        } else {
            out.put(prefix, element.toString());
        }
    }
}
