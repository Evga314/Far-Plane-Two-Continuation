package com.Evga314.farplanetwocontinuation.client;

/**
 * How detailed the blocks of the detailed terrain look. Every step adds one kind of cost: thin blocks, then texture resolution, then
 * gaps in leaves, then plant geometry.
 */
public enum BlockDetail {
    /** Boxes in flat colors, taken from the block textures with the biome tint. */
    OFF,
    /** Blurred vanilla textures; thin blocks (snow layers, carpets, slabs) are as thin as they are; translucent water. */
    LOW,
    /** Vanilla textures, blurred with distance like vanilla does. */
    MEDIUM,
    /** Leaves and other textures with gaps show them. */
    HIGH,
    /** Grass, flowers and other plants as crossed planes on the finest tiles. */
    ULTRA;

    public static BlockDetail of(int level) {
        BlockDetail[] values = values();
        return values[Math.clamp(level, 0, values.length - 1)];
    }

    public boolean textured() {
        return this != OFF;
    }

    public boolean thinBlocks() {
        return this != OFF;
    }

    /** Texture level drawn at every distance, or -1 for vanilla's own choice by distance. */
    public float fixedTextureLevel() {
        return this == LOW ? 2.0F : -1.0F;
    }

    public boolean cutout() {
        return this == HIGH || this == ULTRA;
    }

    public boolean plants() {
        return this == ULTRA;
    }

    /** Water lets the ground under it show through, like vanilla's, so it matches vanilla's water where the two meet. */
    public boolean translucentWater() {
        return this.textured();
    }
}
