package com.Evga314.farplanetwocontinuation.client;

/**
 * How detailed terrain loads while the player moves: smooth frames first, fast loading first, or by speed. Loading fast takes more of
 * the CPU, which on phones shows as lower and less steady FPS and input lag.
 */
public enum LoadSpeed {
    /** Smooth while walking, fast while flying; in between by speed. */
    AUTO,
    /** Always smooth frames first; terrain may lag behind fast flight. */
    SMOOTH,
    /** Always as fast as possible. */
    FAST;

    /** Speeds in blocks per second between which {@link #AUTO} goes from smooth to fast; sprinting is about 5.6, elytra 30 and more. */
    private static final float CALM_SPEED = 10.0F;
    private static final float FAST_SPEED = 20.0F;

    public static LoadSpeed of(int value) {
        LoadSpeed[] values = values();
        return values[Math.clamp(value, 0, values.length - 1)];
    }

    /** How urgent loading is at a speed in blocks per second, from 0 to 1. */
    public float urgency(double speed) {
        return switch (this) {
            case SMOOTH -> 0.0F;
            case FAST -> 1.0F;
            case AUTO -> (float) Math.clamp((speed - CALM_SPEED) / (FAST_SPEED - CALM_SPEED), 0.0, 1.0);
        };
    }
}
