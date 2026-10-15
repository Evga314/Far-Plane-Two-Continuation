package com.Evga314.farplanetwocontinuation.tile;

/**
 * The order detailed tiles are loaded in, the same on client and server: like vanilla builds the sections around the camera first and
 * then what is in view, a tile's score is its distance, made larger the further it lies from the direction the player looks.
 * Smaller scores load first.
 */
public final class LoadOrder {
    /** Tiles this close are part of the player's surroundings and load first in every direction. */
    private static final double SURROUNDINGS = 64.0;
    /** How much later a tile right behind the player loads than one at the same distance straight ahead. */
    private static final double BEHIND_PENALTY = 1.5;

    private LoadOrder() {
    }

    /**
     * @param lookX horizontal look direction, normalized; both 0 when looking straight up or down
     */
    public static double score(TilePos pos, double x, double z, double lookX, double lookZ) {
        double distance = pos.distanceTo(x, z);
        if (distance < SURROUNDINGS || lookX == 0.0 && lookZ == 0.0) {
            return distance;
        }
        double half = pos.size() * 0.5;
        double toX = pos.minX() + half - x;
        double toZ = pos.minZ() + half - z;
        double length = Math.sqrt(toX * toX + toZ * toZ);
        double cos = length < 1.0 ? 1.0 : (toX * lookX + toZ * lookZ) / length;
        // 1 straight ahead, 1 + penalty / 2 to the side, 1 + penalty behind.
        return distance * (1.0 + BEHIND_PENALTY * 0.5 * (1.0 - cos));
    }
}
