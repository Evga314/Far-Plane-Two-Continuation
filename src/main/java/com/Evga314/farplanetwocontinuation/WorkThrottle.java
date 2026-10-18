package com.Evga314.farplanetwocontinuation;

import java.util.concurrent.locks.LockSupport;

/**
 * Lets the background work of the mod make room for the game's frames. When the client sees frames running late, background threads
 * (tile generation, virtual chunks, mesh building) pause for a moment at their next checkpoint for a short while, so the render
 * thread finds a free core. On phones, where every core is busy and the render thread otherwise waits for one, this is what keeps
 * input responsive. With nothing late, a checkpoint costs one volatile read.
 * <p>
 * How much it yields depends on the urgency of loading: walking around, terrain can come in slowly and frames come first; flying
 * fast, terrain has to keep up and background work does not yield at all.
 */
public final class WorkThrottle {
    /** How long background work keeps yielding after a late frame. */
    private static final long HOLD_NS = 250_000_000L;
    /** The pause at each checkpoint while frames are late; work goes on between checkpoints, at a lower pace. */
    private static final long PAUSE_NS = 3_000_000L;

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
        if (Thread.currentThread() instanceof Worker worker && worker.cancelled) {
            throw new Cancelled();
        }
        if (System.nanoTime() - slowUntil < 0L) {
            long pause = (long) (PAUSE_NS * (1.0F - urgency));
            if (pause > 0L) {
                LockSupport.parkNanos(pause);
            }
        }
    }
}
