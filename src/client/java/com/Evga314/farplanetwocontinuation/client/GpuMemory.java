package com.Evga314.farplanetwocontinuation.client;

import net.minecraft.util.Mth;

/** Steps of the video memory sliders and how extra memory turns into extra detail. */
public final class GpuMemory {
    /** Slider steps in megabytes, finer at the low end where weak devices need them. */
    public static final int[] STEPS_MB = {16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192};
    public static final int MAX_MB = 8192;

    private GpuMemory() {
    }

    /** The slider step closest to a value in megabytes. */
    public static int step(int megabytes) {
        int best = 0;
        for (int i = 1; i < STEPS_MB.length; i++) {
            if (Math.abs(STEPS_MB[i] - megabytes) < Math.abs(STEPS_MB[best] - megabytes)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * How much finer tiles may get when more memory is given than the default: every doubling adds a quarter, up to twice the
     * detail at 16 times the default. Less memory than the default never lowers it; the budget itself limits that case.
     */
    public static float qualityBoost(double megabytes, double defaultMegabytes) {
        double ratio = megabytes / defaultMegabytes;
        if (ratio <= 1.0) {
            return 1.0F;
        }
        return (float) Mth.clamp(1.0 + Math.log(ratio) / Math.log(2.0) / 4.0, 1.0, 2.0);
    }
}
