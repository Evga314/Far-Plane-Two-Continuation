package com.Evga314.farplanetwocontinuation.dimension;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The three kinds of worlds the mod tells apart, everywhere it has to: how distant terrain is generated, lit and faded into the
 * sky, and which detailed terrain settings apply. Dimensions from data packs count as the vanilla dimension they look like.
 */
public enum DimensionKind {
    /** Solid ground under an open sky. */
    OVERWORLD("overworld"),
    /** Closed in by a ceiling; there is no surface to see from far away, so the mod draws nothing there yet. */
    NETHER("nether"),
    /** Islands floating over the void under the End sky. */
    END("end");

    private final String id;

    DimensionKind(String id) {
        this.id = id;
    }

    public static DimensionKind of(Level level) {
        return of(level.dimensionType());
    }

    public static DimensionKind of(DimensionType type) {
        if (type.skybox() == DimensionType.Skybox.END) {
            return END;
        }
        return type.hasCeiling() ? NETHER : OVERWORLD;
    }

    /** Name used in config files and translation keys. */
    public String id() {
        return this.id;
    }

    /** Whether there is distant terrain to draw at all. */
    public boolean hasLod() {
        return this != NETHER;
    }
}
