package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneDebug;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Work of the tile server for the performance log of the debug build ({@link FarPlaneDebug}): how many tiles, saved chunks and
 * virtual regions were made and how long they took. Written every few seconds by the server thread while there was work.
 */
final class ServerPerf {
    static final boolean ON = FarPlaneDebug.ENABLED;
    private static final Logger LOGGER = LogUtils.getLogger();

    static final Kind ROUGH = new Kind("rough tiles");
    static final Kind DETAIL = new Kind("detail tiles");
    /** Processor time of rough tiles, against their time on the clock: the difference is waiting (locks, disk, pauses). */
    static final Kind ROUGH_CPU = new Kind("rough cpu");
    static final Kind ROUGH_PAUSED = new Kind("rough paused");
    /** Why the server stopped handing out tiles to the threads, counted per stop, see TileServer#pump. */
    static final AtomicLong STOP_ROUGH_LIMIT = new AtomicLong();
    static final AtomicLong STOP_REFINE_ONE = new AtomicLong();
    static final AtomicLong STOP_DETAIL_LIMIT = new AtomicLong();
    static final AtomicLong STOP_VIRTUAL_BUSY = new AtomicLong();
    /** Tiles in flight summed over the server ticks since the last line, and the ticks, for the average; and the limits. */
    static final AtomicLong ROUGH_IN_FLIGHT = new AtomicLong();
    static final AtomicLong DETAIL_IN_FLIGHT = new AtomicLong();
    static final AtomicLong DETAIL_LIMIT = new AtomicLong();
    static final AtomicLong IN_FLIGHT_TICKS = new AtomicLong();
    private static long lastSummary = System.nanoTime();
    static final Kind DETAIL_LIVE = new Kind("detail live summaries");
    static final Kind DETAIL_SAVED = new Kind("detail saved summaries");
    static final Kind DETAIL_VIRTUAL = new Kind("detail virtual fetch");
    static final Kind SAVED_CHUNKS = new Kind("saved chunks read");
    static final Kind VIRTUAL_REGIONS = new Kind("virtual areas made");
    static final AtomicLong VIRTUAL_CHUNKS = new AtomicLong();
    /** Virtual chunks made since the start, and the different ones of them found to exist for real (loaded or saved) later on. */
    static final AtomicLong VIRTUAL_CHUNKS_TOTAL = new AtomicLong();
    static final java.util.Set<Long> VIRTUAL_BUT_REAL = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int VIRTUAL_BUT_REAL_LIMIT = 1 << 20;
    /** Processor time of the parts of making rough tiles, without the pauses for the game's frames, see {@link #cpu}. */
    static final Kind ROUGH_NEAR = new Kind("cpu near");
    static final Kind ROUGH_ESTIMATED = new Kind("cpu coarse estimated");
    static final Kind ROUGH_EXACT = new Kind("cpu coarse exact");
    static final Kind ICEBERGS = new Kind("cpu icebergs");
    /** Exact searches for the surface, the costly part of rough tiles. */
    static final AtomicLong SURFACE_SEARCHES = new AtomicLong();
    /** Columns of chunk summaries scanned and the time it took, see {@link ChunkSummary}. */
    static final AtomicLong SCAN_COLUMNS = new AtomicLong();
    static final AtomicLong SCAN_NANOS = new AtomicLong();
    /** Picking which segments of the cells of a detailed tile can be seen, see {@link DetailGenerator}. */
    static final Kind DETAIL_TRIM = new Kind("detail trim");
    /** Parts of a detailed tile in wall time: finding its chunks (virtual ones made too), collecting the columns, building the data. */
    static final Kind DETAIL_PREPARE = new Kind("detail prepare");
    static final Kind DETAIL_COLLECT = new Kind("detail collect");
    static final Kind DETAIL_BUILD = new Kind("detail build");
    /** Processor time of a whole detailed tile, and the time it paused for the game's frames. */
    static final Kind DETAIL_CPU = new Kind("detail cpu");
    static final Kind DETAIL_PAUSED = new Kind("detail paused");
    static final Kind DETAIL_ENCODE = new Kind("detail encode");
    /** Segments of detailed tiles before and after leaving out what cannot be seen, and the size of what is sent. */
    static final AtomicLong DETAIL_CELLS = new AtomicLong();
    static final AtomicLong DETAIL_SEGMENTS_SCANNED = new AtomicLong();
    static final AtomicLong DETAIL_SEGMENTS_SENT = new AtomicLong();
    static final AtomicLong DETAIL_PACKETS = new AtomicLong();
    static final AtomicLong DETAIL_PACKET_BYTES = new AtomicLong();
    static final AtomicLong DETAIL_PACKET_MAX = new AtomicLong();
    static final AtomicLong DETAIL_RAW_BYTES = new AtomicLong();
    /** Cells of the margin around detailed tiles, and how many of them had a chunk with data already. */
    static final AtomicLong DETAIL_HALO_CELLS = new AtomicLong();
    static final AtomicLong DETAIL_HALO_KNOWN = new AtomicLong();
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    /** Processor time of this thread so far, 0 outside the debug build. */
    static long cpu() {
        return ON ? THREADS.getCurrentThreadCpuTime() : 0L;
    }

    private ServerPerf() {
    }

    /** A chunk found to exist for real although a virtual one was made for it. */
    static void noteVirtualButReal(long chunk) {
        if (VIRTUAL_BUT_REAL.size() < VIRTUAL_BUT_REAL_LIMIT) {
            VIRTUAL_BUT_REAL.add(chunk);
        }
    }

    /**
     * How busy the threads were since the last line, and what held them back: the share of the time the tile threads of each kind ran
     * tiles, the tiles in flight on average against how many threads there are, and how often handing out tiles stopped at each limit.
     */
    static void threads(int roughThreads, int detailThreads, int maxRoughInFlight) {
        long now = System.nanoTime();
        double window = Math.max(1L, now - lastSummary);
        lastSummary = now;
        long ticks = Math.max(1L, IN_FLIGHT_TICKS.getAndSet(0L));
        LOGGER.info(String.format(Locale.ROOT,
            "[FarPlane perf] server threads: rough busy %.0f%% of %d threads, in flight avg %.2f (limit %d) | detail busy %.0f%% of %d threads,"
                + " in flight avg %.2f (limit avg %.2f) | stops: rough at limit %d, refining at its limit %d, detail at limit %d, waiting for a virtual area %d",
            100.0 * ROUGH.windowNanos() / window / roughThreads, roughThreads, (double) ROUGH_IN_FLIGHT.getAndSet(0L) / ticks, maxRoughInFlight,
            100.0 * DETAIL.windowNanos() / window / detailThreads, detailThreads, (double) DETAIL_IN_FLIGHT.getAndSet(0L) / ticks,
            (double) DETAIL_LIMIT.getAndSet(0L) / ticks, STOP_ROUGH_LIMIT.getAndSet(0L), STOP_REFINE_ONE.getAndSet(0L),
            STOP_DETAIL_LIMIT.getAndSet(0L), STOP_VIRTUAL_BUSY.getAndSet(0L)));
    }

    static void summary(double serverTickMs, int roughQueued, int detailQueued) {
        StringBuilder line = new StringBuilder(256);
        boolean any = false;
        for (Kind kind : new Kind[] {ROUGH, ROUGH_CPU, ROUGH_PAUSED, ROUGH_NEAR, ROUGH_ESTIMATED, ROUGH_EXACT, ICEBERGS, DETAIL, DETAIL_LIVE, DETAIL_SAVED, DETAIL_VIRTUAL, DETAIL_PREPARE, DETAIL_COLLECT, DETAIL_TRIM, DETAIL_BUILD, DETAIL_ENCODE, DETAIL_CPU, DETAIL_PAUSED, SAVED_CHUNKS, VIRTUAL_REGIONS}) {
            any |= kind.append(line);
        }
        line.append(" | surface searches ").append(SURFACE_SEARCHES.getAndSet(0L));
        long columns = SCAN_COLUMNS.getAndSet(0L);
        long scanNanos = SCAN_NANOS.getAndSet(0L);
        if (columns > 0L) {
            any = true;
            line.append(String.format(Locale.ROOT, " | scan %d columns %.1f ms (%.2f us each)", columns, scanNanos / 1e6, scanNanos / 1e3 / columns));
        }
        long built = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_TERRAIN_BUILT.getAndSet(0L);
        long reused = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_TERRAIN_REUSED.getAndSet(0L);
        long prepare = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_PREPARE_NANOS.getAndSet(0L);
        long terrain = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_TERRAIN_NANOS.getAndSet(0L);
        long decorate = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_DECORATE_NANOS.getAndSet(0L);
        long bands = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_BANDS_NANOS.getAndSet(0L);
        long biomes = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_BIOMES_NANOS.getAndSet(0L);
        long starts = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_STARTS_NANOS.getAndSet(0L);
        long references = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_REFERENCES_NANOS.getAndSet(0L);
        long noise = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_NOISE_NANOS.getAndSet(0L);
        long surface = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_SURFACE_NANOS.getAndSet(0L);
        long structures = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_STRUCTURES_NANOS.getAndSet(0L);
        long features = com.Evga314.farplanetwocontinuation.server.virtual.VirtualTerrain.PERF_FEATURES_NANOS.getAndSet(0L);
        if (built + reused > 0L) {
            line.append(String.format(Locale.ROOT, " | virtual cpu: prepare %.0f ms, terrain %.0f ms, decorate %.0f ms; terrain built %d, reused from cache %d (%.0f%%)",
                prepare / 1e6, terrain / 1e6, decorate / 1e6, built, reused, 100.0 * reused / (built + reused)));
            line.append(String.format(Locale.ROOT, " | virtual stages: bands %.0f ms, biomes %.0f ms, starts %.0f ms, references %.0f ms, noise %.0f ms, surface %.0f ms, structures %.0f ms, features %.0f ms",
                bands / 1e6, biomes / 1e6, starts / 1e6, references / 1e6, noise / 1e6, surface / 1e6, structures / 1e6, features / 1e6));
        }
        long cells = DETAIL_CELLS.getAndSet(0L);
        long scanned = DETAIL_SEGMENTS_SCANNED.getAndSet(0L);
        long sent = DETAIL_SEGMENTS_SENT.getAndSet(0L);
        long packets = DETAIL_PACKETS.getAndSet(0L);
        long packetBytes = DETAIL_PACKET_BYTES.getAndSet(0L);
        long packetMax = DETAIL_PACKET_MAX.getAndSet(0L);
        long rawBytes = DETAIL_RAW_BYTES.getAndSet(0L);
        long haloCells = DETAIL_HALO_CELLS.getAndSet(0L);
        long haloKnown = DETAIL_HALO_KNOWN.getAndSet(0L);
        if (haloCells > 0L) {
            line.append(String.format(Locale.ROOT, " | halo known %.0f%%", 100.0 * haloKnown / haloCells));
        }
        if (cells > 0L) {
            line.append(String.format(Locale.ROOT, " | segments per cell %.1f scanned, %.1f sent", (double) scanned / cells, (double) sent / cells));
        }
        if (packets > 0L) {
            line.append(String.format(Locale.ROOT, " | detail packets %d, avg %.0f KB (raw %.0f KB) max %.0f KB",
                packets, packetBytes / 1024.0 / packets, rawBytes / 1024.0 / packets, packetMax / 1024.0));
        }
        long chunks = VIRTUAL_CHUNKS.getAndSet(0L);
        if (!DirtyChunks.CHANGES.isEmpty()) {
            // The most frequent block changes since the last line, which make detailed tiles be built again.
            java.util.List<Map.Entry<String, Long>> changes = new java.util.ArrayList<>();
            DirtyChunks.CHANGES.forEach((kind, count) -> changes.add(Map.entry(kind, count.get())));
            DirtyChunks.CHANGES.clear();
            changes.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            line.append(" | block changes");
            for (int i = 0; i < Math.min(8, changes.size()); i++) {
                line.append(' ').append(changes.get(i).getKey()).append(' ').append(changes.get(i).getValue());
            }
            any = true;
        }
        if (VIRTUAL_CHUNKS_TOTAL.get() > 0L) {
            line.append(String.format(Locale.ROOT, " | virtual chunks made in all %d, of them real %d", VIRTUAL_CHUNKS_TOTAL.get(), VIRTUAL_BUT_REAL.size()));
        }
        if (!any && roughQueued == 0 && detailQueued == 0) {
            return;
        }
        LOGGER.info(String.format(Locale.ROOT, "[FarPlane perf] server: tick %.1f ms, queued rough %d detail %d%s, virtual chunks made %d",
            serverTickMs, roughQueued, detailQueued, line, chunks));
    }

    static final class Kind {
        private final String name;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong max = new AtomicLong();

        Kind(String name) {
            this.name = name;
        }

        /** Time of all runs since the last {@link #windowNanos}, for how busy the threads were. */
        private final AtomicLong window = new AtomicLong();

        void add(long nanos) {
            this.count.incrementAndGet();
            this.nanos.addAndGet(nanos);
            this.max.accumulateAndGet(nanos, Math::max);
            this.window.addAndGet(nanos);
        }

        long windowNanos() {
            return this.window.getAndSet(0L);
        }

        boolean append(StringBuilder line) {
            long count = this.count.getAndSet(0L);
            long nanos = this.nanos.getAndSet(0L);
            long max = this.max.getAndSet(0L);
            if (count == 0L) {
                return false;
            }
            line.append(String.format(Locale.ROOT, " | %s %d, avg %.0f max %.0f ms", this.name, count, nanos / 1e6 / count, max / 1e6));
            return true;
        }
    }
}
