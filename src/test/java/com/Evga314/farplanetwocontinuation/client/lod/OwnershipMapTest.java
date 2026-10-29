package com.Evga314.farplanetwocontinuation.client.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class OwnershipMapTest {
    private static final int HALF = 4;
    private static final int SIDE = 2 * HALF + 1;

    /** Vanilla draws the chunks within {@code radius} of the middle. */
    private static boolean[] vanillaWithin(int radius) {
        boolean[] drawn = new boolean[SIDE * SIDE];
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                drawn[x + z * SIDE] = Math.abs(x - HALF) <= radius && Math.abs(z - HALF) <= radius;
            }
        }
        return drawn;
    }

    private static boolean[] none() {
        return new boolean[SIDE * SIDE];
    }

    @Test
    void aColumnVanillaStartsDrawingIsLeftAloneInTheSameFrame() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, none(), none(), 1000L);
        assertEquals(OwnershipMap.OWN, map.part(0, 0));
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1016L);
        assertEquals(OwnershipMap.NONE, map.part(0, 0), "vanilla draws it this frame");
        assertEquals(OwnershipMap.HANDOVER, map.owner(0, 0));
    }

    @Test
    void vanillaGetsAColumnAloneOnlyAfterDrawingItForAWhile() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1000L);
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1000L + OwnershipMap.HANDOVER_MS - 1);
        assertEquals(OwnershipMap.NONE, map.part(0, 0));
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1000L + OwnershipMap.HANDOVER_MS);
        assertEquals(OwnershipMap.VANILLA, map.owner(0, 0));
        assertEquals(OwnershipMap.NONE, map.part(0, 0));
    }

    @Test
    void theRoughTerrainTakesAColumnBackAtOnce() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 0L);
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1000L);
        assertNotEquals(OwnershipMap.OWN, map.part(2, 0));
        map.update(HALF, 0, 0, vanillaWithin(1), none(), 1001L);
        assertEquals(OwnershipMap.OWN, map.part(2, 0), "vanilla stopped drawing it");
        // Drawn again: left alone at once, the rough terrain's again only after the whole wait if vanilla stops once more.
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1002L);
        assertEquals(OwnershipMap.NONE, map.part(2, 0));
        assertEquals(OwnershipMap.HANDOVER, map.owner(2, 0));
    }

    @Test
    void aColumnVanillaDrawsOnAndOffNeverDrawsTwiceInFull() {
        OwnershipMap map = new OwnershipMap();
        for (long t = 0; t < 2000; t += 16) {
            boolean drawn = (t / 16) % 2 == 0;
            map.update(HALF, 0, 0, drawn ? vanillaWithin(2) : vanillaWithin(1), none(), t);
            // While vanilla draws it the rough terrain draws nothing of it; while it does not, the rough terrain draws it.
            assertEquals(drawn ? OwnershipMap.NONE : OwnershipMap.OWN, map.part(2, 0), "at " + t);
        }
    }

    @Test
    void vanillasColumnsAreLeftAloneUpToTheirEdge() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 0L);
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 1000L);
        assertEquals(OwnershipMap.NONE, map.part(2, 0));
        assertEquals(OwnershipMap.NONE, map.part(2, 2), "corners too");
        assertEquals(OwnershipMap.NONE, map.part(1, 0));
        assertEquals(OwnershipMap.NONE, map.part(0, 0));
        assertEquals(OwnershipMap.OWN, map.part(3, 0));
        assertEquals(OwnershipMap.OWN, map.part(100, 100));
    }

    @Test
    void nothingUnderTheDetailedTiles() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, none(), vanillaWithin(1), 0L);
        assertEquals(OwnershipMap.DETAILED, map.owner(1, 0));
        assertEquals(OwnershipMap.NONE, map.part(1, 0));
        assertEquals(OwnershipMap.OWN, map.part(2, 0));
    }

    @Test
    void movingWithTheCameraKeepsHowLongVanillaDrewAColumn() {
        OwnershipMap map = new OwnershipMap();
        map.update(HALF, 0, 0, vanillaWithin(2), none(), 0L);
        boolean[] shifted = new boolean[SIDE * SIDE];
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                int worldX = 1 + x - HALF;
                int worldZ = z - HALF;
                shifted[x + z * SIDE] = Math.abs(worldX) <= 2 && Math.abs(worldZ) <= 2;
            }
        }
        int before = map.version();
        map.update(HALF, 1, 0, shifted, none(), OwnershipMap.HANDOVER_MS);
        assertEquals(OwnershipMap.VANILLA, map.owner(0, 0));
        assertEquals(OwnershipMap.VANILLA, map.owner(-2, 0));
        assertNotEquals(before, map.version());
    }

    @Test
    void nothingKnownLeavesEverythingToTheRoughTerrain() {
        OwnershipMap map = new OwnershipMap();
        map.update(-1, 0, 0, none(), none(), 0L);
        assertEquals(OwnershipMap.OWN, map.part(0, 0));
    }
}
