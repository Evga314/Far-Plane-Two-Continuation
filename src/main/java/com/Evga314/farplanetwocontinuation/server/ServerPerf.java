package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneDebug;
import com.mojang.logging.LogUtils;
import java.util.Locale;
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
    static final Kind SAVED_CHUNKS = new Kind("saved chunks read");
    static final Kind VIRTUAL_REGIONS = new Kind("virtual areas made");
    static final AtomicLong VIRTUAL_CHUNKS = new AtomicLong();
    /** Processor time of the parts of making rough tiles, without the pauses for the game's frames, see {@link #cpu}. */
    static final Kind ROUGH_NEAR = new Kind("cpu near");
    static final Kind ROUGH_ESTIMATED = new Kind("cpu coarse estimated");
    static final Kind ROUGH_EXACT = new Kind("cpu coarse exact");
    static final Kind ICEBERGS = new Kind("cpu icebergs");
    /** Exact searches for the surface, the costly part of rough tiles. */
    static final AtomicLong SURFACE_SEARCHES = new AtomicLong();
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    /** Processor time of this thread so far, 0 outside the debug build. */
    static long cpu() {
        return ON ? THREADS.getCurrentThreadCpuTime() : 0L;
    }

    private ServerPerf() {
    }

    static void summary(double serverTickMs, int roughQueued, int detailQueued) {
        StringBuilder line = new StringBuilder(256);
        boolean any = false;
        for (Kind kind : new Kind[] {ROUGH, ROUGH_NEAR, ROUGH_ESTIMATED, ROUGH_EXACT, ICEBERGS, DETAIL, SAVED_CHUNKS, VIRTUAL_REGIONS}) {
            any |= kind.append(line);
        }
        line.append(" | surface searches ").append(SURFACE_SEARCHES.getAndSet(0L));
        long chunks = VIRTUAL_CHUNKS.getAndSet(0L);
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

        void add(long nanos) {
            this.count.incrementAndGet();
            this.nanos.addAndGet(nanos);
            this.max.accumulateAndGet(nanos, Math::max);
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
