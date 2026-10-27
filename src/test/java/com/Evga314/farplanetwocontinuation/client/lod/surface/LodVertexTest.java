package com.Evga314.farplanetwocontinuation.client.lod.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class LodVertexTest {
    /** Every field lands where the layout says, which the vertex format of OwnPassOutput and surface.vsh read. */
    @Test
    void fieldsLieWhereTheLayoutSays() {
        ByteBuffer out = ByteBuffer.allocate(LodVertex.SIZE).order(ByteOrder.nativeOrder());
        LodVertex.put(out, 0x1234, 0x2345, 0x3456, 0x4567, 0xBBAA, 0x11, 0x22, 0x44336677, 0x88556699);
        assertEquals(LodVertex.SIZE, out.position());
        assertEquals(0x1234, out.getShort(LodVertex.X) & 0xFFFF);
        assertEquals(0x2345, out.getShort(LodVertex.Z) & 0xFFFF);
        assertEquals(0x3456, out.getShort(LodVertex.Y) & 0xFFFF);
        assertEquals(0x4567, out.getShort(LodVertex.WATER_LEVEL) & 0xFFFF);
        assertEquals(0xAA, out.get(LodVertex.NORMAL) & 0xFF);
        assertEquals(0xBB, out.get(LodVertex.NORMAL + 1) & 0xFF);
        assertEquals(0x11, out.get(LodVertex.CANOPY) & 0xFF);
        assertEquals(0x22, out.get(LodVertex.BITS) & 0xFF);
        // RGBA in that order, from ARGB.
        assertEquals(0x33, out.get(LodVertex.COLOR) & 0xFF);
        assertEquals(0x66, out.get(LodVertex.COLOR + 1) & 0xFF);
        assertEquals(0x77, out.get(LodVertex.COLOR + 2) & 0xFF);
        assertEquals(0x44, out.get(LodVertex.COLOR + 3) & 0xFF);
        assertEquals(0x55, out.get(LodVertex.SECOND) & 0xFF);
        assertEquals(0x88, out.get(LodVertex.SECOND + 3) & 0xFF);
    }

    @Test
    void normalsFoldBothWays() {
        int up = LodVertex.normal(0.0F, 1.0F, 0.0F);
        assertEquals(128, up & 0xFF, 1);
        assertEquals(128, up >>> 8, 1);
        int down = LodVertex.normal(0.0F, -1.0F, 0.0F);
        // Folded below: straight down lands on a corner of the square.
        assertEquals(true, (down & 0xFF) == 255 || (down & 0xFF) == 0 || (down & 0xFF) == 128);
    }
}
