package com.Evga314.farplanetwocontinuation;

import java.util.concurrent.locks.LockSupport;

/**
 * Lets the background work of the mod make room for the game's frames. When the client sees frames running late, background threads
 * (tile generation, virtual chunks, mesh building) pause for a moment at their next checkpoint for a short while, so the render
 * thread finds a free core. On phones, where every core is busy and the render thread otherwise waits for one, this is what keeps
 * input responsive. With nothing late, a checkpoint costs a clock read.
 * <p>
 * How much it yields depends on the urgency of loading: walking around, terrain can come in slowly and frames come first; flying
 * fast, terrain has to keep up and background work does not yield at all.
 */
public final class WorkThrottle {
    /** How long background work keeps yielding after a late frame. */
    private static final long HOLD_NS = 250_000_000L;
    /** The pause at a checkpoint of a thread that is no {@link Worker}, while frames are late. */
    private static final long PAUSE_NS = 3_000_000L;
    /**
     * Share of the time a {@link Worker} pauses while frames are late and loading is not urgent: it works for a while, then pauses
     * for this share of it. Pauses go by the time worked, not by checkpoints, which lie far apart in some work and close together in
     * other.
     */
    private static final float PAUSE_SHARE = 0.3F;
    /** A worker pauses only after working at least this long, so the pauses are not too short to give the frame a core. */
    private static final long SLICE_NS = 2_000_000L;
    /** The longest single pause, so the work goes on soon enough. */
    private static final long MAX_PAUSE_NS = 3_000_000L;

    private static volatile long slowUntil;
    /** 0: smooth frames first, 1: load as fast as possible; set by the client from the player's speed and the settings. */
    private static volatile float urgency;

    private WorkThrottle() {
    }

    /** Called by the client when a frame was late. */
    public static void frameLate() {
        slowUntil = System.nanoTime() + HOLD_NS;
    }

    public static void setUrgency(float value) {
        urgency = Math.clamp(value, 0.0F, 1.0F);
    }

    public static float urgency() {
        return urgency;
    }

    /**
     * A thread of the mod's background work, stopped by {@link #cancel} instead of an interrupt. An interrupt that hits a thread while
     * it reads a file closes the file for every thread: if the file is a mod jar (structure templates of virtual chunks are read from
     * one), Minecraft can no longer read the data packs in it and takes every world for damaged until the game is restarted.
     */
    public static final class Worker extends Thread {
        private volatile boolean cancelled;
        /** Time this thread spent pausing for frames so far; only read and written by the thread itself. */
        private long pausedNanos;
        /** When this thread last paused, or frames were last on time: the time worked since is what the next pause depends on. */
        private long workingSince;

        public Worker(Runnable task, String name) {
            super(task, name);
        }

        /** The work running on this thread ends at its next checkpoint, and so does any work given to it later. */
        public void cancel() {
            this.cancelled = true;
        }
    }

    /** Thrown at a checkpoint of a cancelled {@link Worker}. */
    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Background work cancelled", null, false, false);
        }
    }

    /**
     * A checkpoint of background work: pauses briefly while frames are late, less the more urgent loading is. Work of a cancelled
     * {@link Worker} ends here with {@link Cancelled}.
     */
    public static void yieldToFrames() {
        Worker worker = Thread.currentThread() instanceof Worker thread ? thread : null;
        if (worker != null && worker.cancelled) {
            throw new Cancelled();
        }
        long now = System.nanoTime();
        float share = PAUSE_SHARE * (1.0F - urgency);
        if (now - slowUntil >= 0L || share <= 0.0F) {
            if (worker != null) {
                worker.workingSince = now;
            }
            return;
        }
        if (worker == null) {
            LockSupport.parkNanos((long) (PAUSE_NS * (1.0F - urgency)));
            return;
        }
        long worked = now - worker.workingSince;
        if (worked < SLICE_NS) {
            return;
        }
        LockSupport.parkNanos(Math.min(MAX_PAUSE_NS, (long) (worked * share / (1.0F - share))));
        long resumed = System.nanoTime();
        worker.pausedNanos += resumed - now;
        worker.workingSince = resumed;
    }

    /** Time the current thread spent pausing for frames so far, 0 on threads that are no {@link Worker}. */
    public static long pausedNanos() {
        return Thread.currentThread() instanceof Worker worker ? worker.pausedNanos : 0L;
    }
}
